package com.sabyasachi.apiregression.runner;

import com.sabyasachi.apiregression.client.HttpRunner;
import com.sabyasachi.apiregression.client.Substitutor;
import com.sabyasachi.apiregression.compare.JsonComparator;
import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestResult;
import com.sabyasachi.apiregression.model.TestSuite;

import java.time.Instant;
import java.util.*;

public class SuiteRunner {

    public interface SuiteRunListener {
        default void onTestStarted(int currentIndex, int totalCount, TestCase testCase) {}
        default void onTestCompleted(int currentIndex, int totalCount, TestResult result) {}
        default void onSuiteFinished(List<TestResult> allResults) {}
    }

    private final HttpRunner httpRunner;
    private final TokenProviderResolver tokenResolver;

    public SuiteRunner() {
        this(new HttpRunner(), new TokenProviderResolver());
    }

    public SuiteRunner(HttpRunner httpRunner, TokenProviderResolver tokenResolver) {
        this.httpRunner = httpRunner;
        this.tokenResolver = tokenResolver;
    }

    public List<TestResult> run(TestSuite suite) {
        return run(suite, null);
    }

    public List<TestResult> run(TestSuite suite, SuiteRunListener listener) {
        List<TestResult> results = new ArrayList<>();
        if (suite == null || suite.getTestCases() == null || suite.getTestCases().isEmpty()) {
            if (listener != null) listener.onSuiteFinished(results);
            return results;
        }

        // 1. Initialize run context variables from suite variables and baseUrl
        Map<String, String> runVariables = new LinkedHashMap<>();
        if (suite.getBaseUrl() != null && !suite.getBaseUrl().isBlank()) {
            runVariables.put("baseUrl", suite.getBaseUrl());
        }
        if (suite.getVariables() != null) {
            runVariables.putAll(suite.getVariables());
        }

        // 2. Resolve token providers first
        TokenProviderResolver.TokenResolutionOutcome tokenOutcome = tokenResolver.resolve(suite, runVariables);
        results.addAll(tokenOutcome.getProviderResults());

        int totalCount = suite.getTestCases().size();
        int currentIndex = 0;

        // If listener is present, notify about the token provider results
        if (listener != null) {
            for (TestResult pr : tokenOutcome.getProviderResults()) {
                currentIndex++;
                listener.onTestCompleted(currentIndex, totalCount, pr);
            }
        } else {
            currentIndex = tokenOutcome.getProviderResults().size();
        }

        // 3. Run regular (non-token-provider) TestCases
        for (TestCase tc : suite.getTestCases()) {
            if (tc.isTokenProvider()) {
                continue; // Already executed
            }

            currentIndex++;
            if (listener != null) {
                listener.onTestStarted(currentIndex, totalCount, tc);
            }

            TestResult result = new TestResult(tc.getName());
            result.setCategory(tc.getCategory());
            result.setMethod(tc.getMethod());
            result.setExpectedStatus(tc.getExpectedStatus());
            result.setExecutedAt(Instant.now());

            // Check if this test case depends on a token provider that failed
            String failedDependencyReason = null;
            if (tc.getUsesTokenFrom() != null) {
                for (String depToken : tc.getUsesTokenFrom()) {
                    if (tokenOutcome.getFailedTokens().containsKey(depToken)) {
                        failedDependencyReason = "Token resolution failed for required token [" + depToken + "]: "
                                + tokenOutcome.getFailedTokens().get(depToken);
                        break;
                    }
                }
            }

            if (failedDependencyReason != null) {
                result.setStatusPassed(false);
                result.setBodyPassed(false);
                result.setErrorMessage(failedDependencyReason);
                result.setDiffSummary(failedDependencyReason);
                results.add(result);
                if (listener != null) {
                    listener.onTestCompleted(currentIndex, totalCount, result);
                }
                continue;
            }

            // Execute regular test case
            try {
                HttpRunner.ExecutionResponse response = httpRunner.execute(tc, runVariables);
                result.setActualStatus(response.getStatusCode());
                result.setLatencyMs(response.getLatencyMs());
                result.setResolvedUrl(response.getResolvedUrl());
                result.setActualBody(response.getBody());
                result.setActualHeaders(response.getHeaders());

                boolean statusPassed = (response.getStatusCode() == tc.getExpectedStatus());
                result.setStatusPassed(statusPassed);

                // Compare JSON body (substituting any suite/token variables in expected template)
                String resolvedExpectedBody = null;
                if (tc.getExpectedBodyTemplate() != null && !tc.getExpectedBodyTemplate().isBlank()) {
                    resolvedExpectedBody = Substitutor.substitute(tc.getExpectedBodyTemplate(), runVariables).getResult();
                }

                JsonComparator.ComparisonResult compResult = JsonComparator.compare(
                        resolvedExpectedBody,
                        response.getBody(),
                        tc.getIgnorePaths()
                );
                result.setBodyPassed(compResult.isPassed());
                result.setDiffSummary(compResult.getDiffSummary());

            } catch (Exception e) {
                result.setStatusPassed(false);
                result.setBodyPassed(false);
                result.setErrorMessage("Execution error: " + e.getMessage());
                result.setDiffSummary("Execution error: " + e.getMessage());
            }

            results.add(result);
            if (listener != null) {
                listener.onTestCompleted(currentIndex, totalCount, result);
            }
        }

        if (listener != null) {
            listener.onSuiteFinished(results);
        }

        return results;
    }
}
