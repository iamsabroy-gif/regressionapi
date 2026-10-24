package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OverrideOptions {

    private ConstraintPolicy constraintPolicy = ConstraintPolicy.WARN;
    private boolean skipHiddenSheets = true;
    private int maxRowsPerSheet = 5000;
    private boolean applyWithErrors = false;

    public OverrideOptions() {
    }

    public ConstraintPolicy getConstraintPolicy() {
        return constraintPolicy != null ? constraintPolicy : ConstraintPolicy.WARN;
    }

    public void setConstraintPolicy(ConstraintPolicy constraintPolicy) {
        this.constraintPolicy = constraintPolicy != null ? constraintPolicy : ConstraintPolicy.WARN;
    }

    public boolean isSkipHiddenSheets() {
        return skipHiddenSheets;
    }

    public void setSkipHiddenSheets(boolean skipHiddenSheets) {
        this.skipHiddenSheets = skipHiddenSheets;
    }

    public int getMaxRowsPerSheet() {
        return maxRowsPerSheet;
    }

    public void setMaxRowsPerSheet(int maxRowsPerSheet) {
        this.maxRowsPerSheet = maxRowsPerSheet;
    }

    public boolean isApplyWithErrors() {
        return applyWithErrors;
    }

    public void setApplyWithErrors(boolean applyWithErrors) {
        this.applyWithErrors = applyWithErrors;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OverrideOptions that)) return false;
        return skipHiddenSheets == that.skipHiddenSheets &&
                maxRowsPerSheet == that.maxRowsPerSheet &&
                applyWithErrors == that.applyWithErrors &&
                constraintPolicy == that.constraintPolicy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(constraintPolicy, skipHiddenSheets, maxRowsPerSheet, applyWithErrors);
    }
}
