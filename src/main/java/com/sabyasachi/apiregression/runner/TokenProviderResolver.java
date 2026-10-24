package com.sabyasachi.apiregression.runner;

import com.jayway.jsonpath.JsonPath;
import com.sabyasachi.apiregression.client.HttpRunner;
import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestResult;
import com.sabyasachi.apiregression.model.TestSuite;

import java.time.Instant;
import java.util.*;

public class TokenProviderResolver {

    public static class TokenResolutionOutcome {
        private final Map<String, String> resolvedTokens = new LinkedHashMap<>();
        private final Map<String, String> failedTokens = new LinkedHashMap<>(); // varName -> error reason
        private final List<TestResult> providerResults = new ArrayList<>();

        public Map<String, String> getResolvedTokens() {
            return resolvedTokens;
        }

        public Map<String, String> getFailedTokens() {
            return failedTokens;
        }

        public List<TestResult> getProviderResults() {
            return providerResults;
        }

        public boolean hasFailures() {
            return !failedTokens.isEmpty();
        }
    }

    private final HttpRunner httpRunner;

    public TokenProviderResolver() {
        this(new HttpRunner());
    }

    public TokenProviderResolver(HttpRunner httpRunner) {
        this.httpRunner = httpRunner;
    }

    public TokenResolutionOutcome resolve(TestSuite suite, Map<String, String> runContextVariables) {
        TokenResolutionOutcome outcome = new TokenResolutionOutcome();
        if (suite == null || suite.getTestCases() == null) {
            return outcome;
        }

        List<TestCase> tokenProviders = new ArrayList<>();
        for (TestCase tc : suite.getTestCases()) {
            if (tc.isTokenProvider()) {
                tokenProviders.add(tc);
            }
        }

        for (TestCase provider : tokenProviders) {
            String varName = provider.getTokenVariableName();
            if (varName == null || varName.isBlank()) {
                varName = "token_" + System.identityHashCode(provider);
            }

            TestResult result = new TestResult(provider.getName());
            result.setCategory(provider.getCategory());
            result.setMethod(provider.getMethod());
            result.setTokenProvider(true);
            result.setExecutedAt(Instant.now());

            try {
                // Execute token provider request with current run variables
                HttpRunner.ExecutionResponse response = httpRunner.execute(provider, runContextVariables);
                result.setActualStatus(response.getStatusCode());
                result.setLatencyMs(response.getLatencyMs());
                result.setResolvedUrl(response.getResolvedUrl());
                // Do not retain raw body/headers on token provider result to prevent leaking credentials

                boolean statusOk = response.getStatusCode() >= 200 && response.getStatusCode() < 300;
                result.setStatusPassed(statusOk);
                result.setExpectedStatus(provider.getExpectedStatus() > 0 ? provider.getExpectedStatus() : 200);

                if (!statusOk) {
                    String err = "Token provider HTTP call failed with status: " + response.getStatusCode();
                    result.setErrorMessage(err);
                    result.setBodyPassed(false);
                    result.setDiffSummary(err);
                    outcome.getFailedTokens().put(varName, err);
                } else {
                    // Extract token
                    String extractedToken = null;
                    if (provider.getTokenHeaderSource() != null && !provider.getTokenHeaderSource().isBlank()) {
                        extractedToken = response.getFirstHeader(provider.getTokenHeaderSource().trim());
                    } else {
                        String jsonPathStr = provider.getTokenJsonPath();
                        if (jsonPathStr == null || jsonPathStr.isBlank()) {
                            jsonPathStr = "$.access_token";
                        }
                        try {
                            Object val = JsonPath.read(response.getBody(), jsonPathStr.trim());
                            if (val != null) {
                                extractedToken = String.valueOf(val);
                            }
                        } catch (Exception e) {
                            String err = "Failed to extract token via JSONPath [" + jsonPathStr + "]: " + e.getMessage();
                            result.setErrorMessage(err);
                            result.setBodyPassed(false);
                            result.setDiffSummary(err);
                            outcome.getFailedTokens().put(varName, err);
                        }
                    }

                    if (extractedToken != null && !extractedToken.isBlank()) {
                        result.setBodyPassed(true);
                        result.setTokenExtracted(extractedToken);
                        result.setDiffSummary("Token successfully extracted for variable: " + varName);
                        outcome.getResolvedTokens().put(varName, extractedToken);
                        runContextVariables.put(varName, extractedToken);
                    } else if (!outcome.getFailedTokens().containsKey(varName)) {
                        String err = "Extracted token was null or blank";
                        result.setErrorMessage(err);
                        result.setBodyPassed(false);
                        result.setDiffSummary(err);
                        outcome.getFailedTokens().put(varName, err);
                    }
                }
            } catch (Exception e) {
                String err = "Token provider execution exception: " + e.getMessage();
                result.setErrorMessage(err);
                result.setStatusPassed(false);
                result.setBodyPassed(false);
                result.setDiffSummary(err);
                outcome.getFailedTokens().put(varName, err);
            }

            outcome.getProviderResults().add(result);
        }

        return outcome;
    }

    private String mask(String token) {
        if (token == null) return "null";
        if (token.length() <= 8) return "***";
        return token.substring(0, 4) + "..." + token.substring(token.length() - 4);
    }
}
