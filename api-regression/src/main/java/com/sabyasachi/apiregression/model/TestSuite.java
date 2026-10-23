package com.sabyasachi.apiregression.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.*;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TestSuite {

    private String name = "New Test Suite";
    private String baseUrl = "";
    private Map<String, String> variables = new LinkedHashMap<>();
    private List<TestCase> testCases = new ArrayList<>();
    private String specPath;
    private transient String filePath;
    private com.sabyasachi.apiregression.payload.OverrideOptions overrideOptions = new com.sabyasachi.apiregression.payload.OverrideOptions();

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public TestSuite() {
    }

    public TestSuite(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl != null ? baseUrl : "";
    }

    public Map<String, String> getVariables() {
        return variables;
    }

    public void setVariables(Map<String, String> variables) {
        this.variables = variables != null ? variables : new LinkedHashMap<>();
    }

    public List<TestCase> getTestCases() {
        return testCases;
    }

    public void setTestCases(List<TestCase> testCases) {
        this.testCases = testCases != null ? testCases : new ArrayList<>();
    }

    public void addTestCase(TestCase testCase) {
        if (testCase != null) {
            this.testCases.add(testCase);
        }
    }

    public String getSpecPath() {
        return specPath;
    }

    public void setSpecPath(String specPath) {
        this.specPath = specPath;
    }

    public com.sabyasachi.apiregression.payload.OverrideOptions getOverrideOptions() {
        return overrideOptions;
    }

    public void setOverrideOptions(com.sabyasachi.apiregression.payload.OverrideOptions overrideOptions) {
        this.overrideOptions = overrideOptions != null ? overrideOptions : new com.sabyasachi.apiregression.payload.OverrideOptions();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TestSuite testSuite)) return false;
        return Objects.equals(name, testSuite.name) &&
                Objects.equals(baseUrl, testSuite.baseUrl) &&
                Objects.equals(variables, testSuite.variables) &&
                Objects.equals(testCases, testSuite.testCases);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name, baseUrl, variables, testCases);
    }
}
