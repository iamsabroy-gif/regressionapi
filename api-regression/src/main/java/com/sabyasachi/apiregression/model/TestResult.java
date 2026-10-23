package com.sabyasachi.apiregression.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TestResult {

    private String testCaseName;
    private String category;
    private String method;
    private String resolvedUrl;
    private boolean statusPassed;
    private boolean bodyPassed;
    private int expectedStatus;
    private int actualStatus;
    private long latencyMs;
    private String diffSummary;
    private Instant executedAt = Instant.now();
    private String actualBody;
    private Map<String, List<String>> actualHeaders;
    private String errorMessage;
    private boolean tokenProvider;
    private String tokenExtracted;

    public TestResult() {
    }

    public TestResult(String testCaseName) {
        this.testCaseName = testCaseName;
    }

    public boolean isPassed() {
        return errorMessage == null && statusPassed && bodyPassed;
    }

    public String getTestCaseName() {
        return testCaseName;
    }

    public void setTestCaseName(String testCaseName) {
        this.testCaseName = testCaseName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getResolvedUrl() {
        return resolvedUrl;
    }

    public void setResolvedUrl(String resolvedUrl) {
        this.resolvedUrl = resolvedUrl;
    }

    public boolean isStatusPassed() {
        return statusPassed;
    }

    public void setStatusPassed(boolean statusPassed) {
        this.statusPassed = statusPassed;
    }

    public boolean isBodyPassed() {
        return bodyPassed;
    }

    public void setBodyPassed(boolean bodyPassed) {
        this.bodyPassed = bodyPassed;
    }

    public int getExpectedStatus() {
        return expectedStatus;
    }

    public void setExpectedStatus(int expectedStatus) {
        this.expectedStatus = expectedStatus;
    }

    public int getActualStatus() {
        return actualStatus;
    }

    public void setActualStatus(int actualStatus) {
        this.actualStatus = actualStatus;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getDiffSummary() {
        return diffSummary;
    }

    public void setDiffSummary(String diffSummary) {
        this.diffSummary = diffSummary;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public void setExecutedAt(Instant executedAt) {
        this.executedAt = executedAt != null ? executedAt : Instant.now();
    }

    public String getActualBody() {
        return actualBody;
    }

    public void setActualBody(String actualBody) {
        this.actualBody = actualBody;
    }

    public Map<String, List<String>> getActualHeaders() {
        return actualHeaders;
    }

    public void setActualHeaders(Map<String, List<String>> actualHeaders) {
        this.actualHeaders = actualHeaders;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public boolean isTokenProvider() {
        return tokenProvider;
    }

    public void setTokenProvider(boolean tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    public String getTokenExtracted() {
        return tokenExtracted;
    }

    public void setTokenExtracted(String tokenExtracted) {
        this.tokenExtracted = tokenExtracted;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TestResult that)) return false;
        return statusPassed == that.statusPassed &&
                bodyPassed == that.bodyPassed &&
                expectedStatus == that.expectedStatus &&
                actualStatus == that.actualStatus &&
                latencyMs == that.latencyMs &&
                tokenProvider == that.tokenProvider &&
                Objects.equals(testCaseName, that.testCaseName) &&
                Objects.equals(category, that.category) &&
                Objects.equals(method, that.method) &&
                Objects.equals(resolvedUrl, that.resolvedUrl) &&
                Objects.equals(diffSummary, that.diffSummary) &&
                Objects.equals(executedAt, that.executedAt) &&
                Objects.equals(actualBody, that.actualBody) &&
                Objects.equals(actualHeaders, that.actualHeaders) &&
                Objects.equals(errorMessage, that.errorMessage) &&
                Objects.equals(tokenExtracted, that.tokenExtracted);
    }

    @Override
    public int hashCode() {
        return Objects.hash(testCaseName, category, method, resolvedUrl, statusPassed,
                bodyPassed, expectedStatus, actualStatus, latencyMs, diffSummary,
                executedAt, actualBody, actualHeaders, errorMessage, tokenProvider, tokenExtracted);
    }
}
