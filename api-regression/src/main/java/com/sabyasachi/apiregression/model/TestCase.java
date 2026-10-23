package com.sabyasachi.apiregression.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.*;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TestCase {

    private String name;
    private String method = "GET";
    private String url;
    private Map<String, String> headers = new LinkedHashMap<>();
    private Auth auth;
    private String bodyTemplate;
    private int expectedStatus = 200;
    private String expectedBodyTemplate;
    private List<String> ignorePaths = new ArrayList<>();
    private String category;

    // --- token-provider (Authman) support ---
    private boolean tokenProvider = false;
    private String tokenJsonPath;
    private String tokenHeaderSource;
    private String tokenVariableName;
    private List<String> usesTokenFrom = new ArrayList<>();

    // --- Excel parameter override / provider spec support ---
    private String operationId;
    private RequestSnapshot baseline;
    private OverrideProvenance lastOverride;

    public TestCase() {
    }

    public TestCase(String name, String method, String url) {
        this.name = name;
        this.method = method;
        this.url = url;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method != null ? method.toUpperCase() : "GET";
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers != null ? headers : new LinkedHashMap<>();
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    public String getBodyTemplate() {
        return bodyTemplate;
    }

    public void setBodyTemplate(String bodyTemplate) {
        this.bodyTemplate = bodyTemplate;
    }

    public int getExpectedStatus() {
        return expectedStatus;
    }

    public void setExpectedStatus(int expectedStatus) {
        this.expectedStatus = expectedStatus;
    }

    public String getExpectedBodyTemplate() {
        return expectedBodyTemplate;
    }

    public void setExpectedBodyTemplate(String expectedBodyTemplate) {
        this.expectedBodyTemplate = expectedBodyTemplate;
    }

    public List<String> getIgnorePaths() {
        return ignorePaths;
    }

    public void setIgnorePaths(List<String> ignorePaths) {
        this.ignorePaths = ignorePaths != null ? ignorePaths : new ArrayList<>();
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public boolean isTokenProvider() {
        return tokenProvider;
    }

    public void setTokenProvider(boolean tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    public String getTokenJsonPath() {
        return tokenJsonPath;
    }

    public void setTokenJsonPath(String tokenJsonPath) {
        this.tokenJsonPath = tokenJsonPath;
    }

    public String getTokenHeaderSource() {
        return tokenHeaderSource;
    }

    public void setTokenHeaderSource(String tokenHeaderSource) {
        this.tokenHeaderSource = tokenHeaderSource;
    }

    public String getTokenVariableName() {
        return tokenVariableName;
    }

    public void setTokenVariableName(String tokenVariableName) {
        this.tokenVariableName = tokenVariableName;
    }

    public List<String> getUsesTokenFrom() {
        return usesTokenFrom;
    }

    public void setUsesTokenFrom(List<String> usesTokenFrom) {
        this.usesTokenFrom = usesTokenFrom != null ? usesTokenFrom : new ArrayList<>();
    }

    public String getOperationId() {
        return operationId;
    }

    public void setOperationId(String operationId) {
        this.operationId = operationId;
    }

    public RequestSnapshot getBaseline() {
        return baseline;
    }

    public void setBaseline(RequestSnapshot baseline) {
        this.baseline = baseline;
    }

    public OverrideProvenance getLastOverride() {
        return lastOverride;
    }

    public void setLastOverride(OverrideProvenance lastOverride) {
        this.lastOverride = lastOverride;
    }

    public void revertToBaseline() {
        if (baseline != null) {
            this.url = baseline.getUrl();
            this.headers = new LinkedHashMap<>(baseline.getHeaders());
            this.bodyTemplate = baseline.getBodyTemplate();
            this.lastOverride = null;
        }
    }

    public void promoteToBaseline() {
        this.baseline = RequestSnapshot.from(this);
        this.lastOverride = null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TestCase testCase)) return false;
        return expectedStatus == testCase.expectedStatus &&
                tokenProvider == testCase.tokenProvider &&
                Objects.equals(name, testCase.name) &&
                Objects.equals(method, testCase.method) &&
                Objects.equals(url, testCase.url) &&
                Objects.equals(headers, testCase.headers) &&
                Objects.equals(auth, testCase.auth) &&
                Objects.equals(bodyTemplate, testCase.bodyTemplate) &&
                Objects.equals(expectedBodyTemplate, testCase.expectedBodyTemplate) &&
                Objects.equals(ignorePaths, testCase.ignorePaths) &&
                Objects.equals(category, testCase.category) &&
                Objects.equals(tokenJsonPath, testCase.tokenJsonPath) &&
                Objects.equals(tokenHeaderSource, testCase.tokenHeaderSource) &&
                Objects.equals(tokenVariableName, testCase.tokenVariableName) &&
                Objects.equals(usesTokenFrom, testCase.usesTokenFrom);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, method, url, headers, auth, bodyTemplate, expectedStatus,
                expectedBodyTemplate, ignorePaths, category, tokenProvider, tokenJsonPath,
                tokenHeaderSource, tokenVariableName, usesTokenFrom);
    }

    @Override
    public String toString() {
        return (category != null && !category.isBlank() ? "[" + category + "] " : "") +
                (method != null ? method : "GET") + " " + (name != null ? name : url);
    }
}
