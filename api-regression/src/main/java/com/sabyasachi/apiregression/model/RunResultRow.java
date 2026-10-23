package com.sabyasachi.apiregression.model;

public class RunResultRow {

    public static boolean isPartialOverride(TestCase testCase) {
        if (testCase == null || testCase.getLastOverride() == null) {
            return false;
        }
        return testCase.getLastOverride().getErrors() > 0;
    }
}
