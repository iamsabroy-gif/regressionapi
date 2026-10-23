package com.sabyasachi.apiregression.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OverrideProvenance {

    private String workbookFile;
    private String workbookSha256;
    private String sheet;
    private String specSha256;
    private Instant appliedAt = Instant.now();
    private int modified;
    private int added;
    private int errors;
    private int warnings;

    public OverrideProvenance() {
    }

    public String getWorkbookFile() {
        return workbookFile;
    }

    public void setWorkbookFile(String workbookFile) {
        this.workbookFile = workbookFile;
    }

    public String getWorkbookSha256() {
        return workbookSha256;
    }

    public void setWorkbookSha256(String workbookSha256) {
        this.workbookSha256 = workbookSha256;
    }

    public String getSheet() {
        return sheet;
    }

    public void setSheet(String sheet) {
        this.sheet = sheet;
    }

    public String getSpecSha256() {
        return specSha256;
    }

    public void setSpecSha256(String specSha256) {
        this.specSha256 = specSha256;
    }

    public Instant getAppliedAt() {
        return appliedAt;
    }

    public void setAppliedAt(Instant appliedAt) {
        this.appliedAt = appliedAt != null ? appliedAt : Instant.now();
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

    public String getSourceSheet() {
        return sheet;
    }

    public String getSourceWorkbook() {
        return workbookFile;
    }

    public int getModifications() {
        return modified;
    }

    public int getAdditions() {
        return added;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OverrideProvenance that)) return false;
        return modified == that.modified &&
                added == that.added &&
                errors == that.errors &&
                warnings == that.warnings &&
                Objects.equals(workbookFile, that.workbookFile) &&
                Objects.equals(workbookSha256, that.workbookSha256) &&
                Objects.equals(sheet, that.sheet) &&
                Objects.equals(specSha256, that.specSha256) &&
                Objects.equals(appliedAt, that.appliedAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(workbookFile, workbookSha256, sheet, specSha256, appliedAt, modified, added, errors, warnings);
    }
}
