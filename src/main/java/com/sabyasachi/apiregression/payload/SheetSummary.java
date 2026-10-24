package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SheetSummary {

    private String sheet;
    private String targetTestCase;
    private String matchedBy; // NAME | OPERATION_ID | METHOD_PATH
    private int modified;
    private int added;
    private int skipped;
    private int errors;
    private int warnings;
    private String status = "NOT_APPLIED"; // APPLIED | PARTIAL | NOT_APPLIED | UNMATCHED | CONFLICT
    private final java.util.List<String> sheetWarnings = new java.util.ArrayList<>();

    public java.util.List<String> getSheetWarnings() {
        return sheetWarnings;
    }

    public void addSheetWarning(String warning) {
        if (warning != null && !warning.isBlank()) {
            this.sheetWarnings.add(warning);
            this.warnings++;
        }
    }

    public SheetSummary() {
    }

    public SheetSummary(String sheet, String targetTestCase) {
        this.sheet = sheet;
        this.targetTestCase = targetTestCase;
    }

    public String getSheet() {
        return sheet;
    }

    public void setSheet(String sheet) {
        this.sheet = sheet;
    }

    public String getTargetTestCase() {
        return targetTestCase;
    }

    public void setTargetTestCase(String targetTestCase) {
        this.targetTestCase = targetTestCase;
    }

    public String getMatchedBy() {
        return matchedBy;
    }

    public void setMatchedBy(String matchedBy) {
        this.matchedBy = matchedBy;
    }

    public int getModified() {
        return modified;
    }

    public void setModified(int modified) {
        this.modified = modified;
    }

    public int getAdded() {
        return added;
    }

    public void setAdded(int added) {
        this.added = added;
    }

    public int getSkipped() {
        return skipped;
    }

    public void setSkipped(int skipped) {
        this.skipped = skipped;
    }

    public int getErrors() {
        return errors;
    }

    public void setErrors(int errors) {
        this.errors = errors;
    }

    public int getWarnings() {
        return warnings;
    }

    public void setWarnings(int warnings) {
        this.warnings = warnings;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
