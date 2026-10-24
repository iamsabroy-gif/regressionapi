package com.sabyasachi.apiregression.runner;

import com.sabyasachi.apiregression.client.HttpRunner;
import com.sabyasachi.apiregression.client.Substitutor;
import com.sabyasachi.apiregression.compare.JsonComparator;
import com.sabyasachi.apiregression.model.RunOptions;
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

        RunOptions runOptions = suite.getRunOptions() != null ? suite.getRunOptions() : new RunOptions();
        HttpRunner effectiveHttpRunner = createRunnerForRun(runOptions);
        int effectiveRetryCount = (runOptions.getRetryCount() > 0)
                ? runOptions.getRetryCount()
                : (effectiveHttpRunner != null ? effectiveHttpRunner.getRetryCount() : 0);

        // 3. Collect regular (non-token-provider) TestCases
        List<TestCase> regularCases = new ArrayList<>();
        for (TestCase tc : suite.getTestCases()) {
            if (!tc.isTokenProvider()) {
                regularCases.add(tc);
            }
        }

        int poolSize = runOptions.getThreads();
        if (poolSize <= 1 || regularCases.size() <= 1) {
            for (TestCase tc : regularCases) {
                currentIndex++;
                if (listener != null) {
                    listener.onTestStarted(currentIndex, totalCount, tc);
                }
                TestResult result = executeSingleTestCase(tc, runVariables, tokenOutcome, effectiveHttpRunner, effectiveRetryCount);
                results.add(result);
                if (listener != null) {
                    listener.onTestCompleted(currentIndex, totalCount, result);
                }
            }
        } else {
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(poolSize);
            try {
                List<java.util.concurrent.Future<TestResult>> futures = new ArrayList<>();
                for (TestCase tc : regularCases) {
                    futures.add(pool.submit(() -> executeSingleTestCase(tc, runVariables, tokenOutcome, effectiveHttpRunner, effectiveRetryCount)));
                }

                for (int i = 0; i < futures.size(); i++) {
                    currentIndex++;
                    TestCase tc = regularCases.get(i);
                    try {
                        TestResult result = futures.get(i).get();
                        results.add(result);
                        if (listener != null) {
                            listener.onTestCompleted(currentIndex, totalCount, result);
                        }
                    } catch (Exception ex) {
                        TestResult errResult = new TestResult(tc != null && tc.getName() != null ? tc.getName() : "Execution Error");
                        if (tc != null) {
                            errResult.setCategory(tc.getCategory());
                            errResult.setMethod(tc.getMethod());
                            errResult.setExpectedStatus(tc.getExpectedStatus());
                            errResult.setResolvedUrl(tc.getUrl());
                        }
                        errResult.setStatusPassed(false);
                        errResult.setBodyPassed(false);
                        errResult.setExecutedAt(Instant.now());

                        Throwable cause = (ex instanceof java.util.concurrent.ExecutionException && ex.getCause() != null)
                                ? ex.getCause()
                                : ex;
                        String errorMsg = (cause.getMessage() != null && !cause.getMessage().isBlank())
                                ? cause.getMessage()
                                : cause.getClass().getSimpleName();

                        errResult.setErrorMessage("Execution error: " + errorMsg);
                        errResult.setDiffSummary("Execution error: " + errorMsg);
                        results.add(errResult);
                        if (listener != null) {
                            listener.onTestCompleted(currentIndex, totalCount, errResult);
                        }
                    }
                }
            } finally {
                pool.shutdown();
            }
        }

        if (listener != null) {
            listener.onSuiteFinished(results);
        }

        return results;
    }

    private HttpRunner createRunnerForRun(RunOptions runOptions) {
        if (this.httpRunner == null) {
            return new HttpRunner();
        }
        if (this.httpRunner.getClass() != HttpRunner.class) {
            return this.httpRunner;
        }
        int retries = (runOptions != null && runOptions.getRetryCount() > 0)
                ? runOptions.getRetryCount()
                : this.httpRunner.getRetryCount();
        java.time.Duration timeout = (runOptions != null && runOptions.getTimeoutSeconds() > 0)
                ? java.time.Duration.ofSeconds(runOptions.getTimeoutSeconds())
                : this.httpRunner.getTimeout();
        HttpRunner runner = new HttpRunner(this.httpRunner.getHttpClient(), timeout, retries, this.httpRunner.getMaxBodyBytes());
        runner.setFollowRedirects(this.httpRunner.isFollowRedirects());
        runner.setRedirectsCrossOriginAllowed(this.httpRunner.isRedirectsCrossOriginAllowed());
        return runner;
    }

    private TestResult executeSingleTestCase(TestCase tc, Map<String, String> runVariables,
                                            TokenProviderResolver.TokenResolutionOutcome tokenOutcome,
                                            HttpRunner runner,
                                            int effectiveRetryCount) {
        TestResult result = new TestResult(tc.getName());
        result.setCategory(tc.getCategory());
        result.setMethod(tc.getMethod());
        result.setExpectedStatus(tc.getExpectedStatus());
        result.setExecutedAt(Instant.now());

        // Check token dependencies
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
            return result;
        }

        try {
            HttpRunner.ExecutionResponse response = runner.execute(tc, runVariables, effectiveRetryCount);
            result.setActualStatus(response.getStatusCode());
            result.setLatencyMs(response.getLatencyMs());
            result.setResolvedUrl(response.getResolvedUrl());
            result.setActualBody(response.getBody());
            result.setActualHeaders(response.getHeaders());

            boolean statusPassed = (response.getStatusCode() == tc.getExpectedStatus());
            result.setStatusPassed(statusPassed);

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
            String msg = (e.getMessage() != null && !e.getMessage().isBlank())
                    ? e.getMessage()
                    : e.getClass().getSimpleName();
            result.setErrorMessage("Execution error: " + msg);
            result.setDiffSummary("Execution error: " + msg);
        }

        return result;
    }
}
