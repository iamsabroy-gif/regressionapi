package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OverrideReport {

    private String workbook;
    private String workbookSha256;
    private String specPath;
    private String specVersion;
    private String specSha256;
    private ConstraintPolicy policy = ConstraintPolicy.WARN;
    private boolean dryRun = true;
    private Instant createdAt = Instant.now();
    private List<SheetSummary> sheets = new ArrayList<>();
    private List<OverrideEntry> entries = new ArrayList<>();
    private List<String> specMessages = new ArrayList<>();

    public OverrideReport() {
    }

    public int getTotalModified() {
        return sheets.stream().mapToInt(SheetSummary::getModified).sum();
    }

    public int getTotalAdded() {
        return sheets.stream().mapToInt(SheetSummary::getAdded).sum();
    }

    public int getTotalSkipped() {
        return sheets.stream().mapToInt(SheetSummary::getSkipped).sum();
    }

    public int getTotalErrors() {
        return sheets.stream().mapToInt(SheetSummary::getErrors).sum();
    }

    public int getTotalWarnings() {
        return sheets.stream().mapToInt(SheetSummary::getWarnings).sum();
    }

    public boolean hasErrors() {
        return getTotalErrors() > 0;
    }

    public String getWorkbook() {
        return workbook;
    }

    public void setWorkbook(String workbook) {
        this.workbook = workbook;
    }

    public String getWorkbookSha256() {
        return workbookSha256;
    }

    public void setWorkbookSha256(String workbookSha256) {
        this.workbookSha256 = workbookSha256;
    }

    public String getSpecPath() {
        return specPath;
    }

    public void setSpecPath(String specPath) {
        this.specPath = specPath;
    }

    public String getSpecVersion() {
        return specVersion;
    }

    public void setSpecVersion(String specVersion) {
        this.specVersion = specVersion;
    }

    public String getSpecSha256() {
        return specSha256;
    }

    public void setSpecSha256(String specSha256) {
        this.specSha256 = specSha256;
    }

    public ConstraintPolicy getPolicy() {
        return policy;
    }

    public void setPolicy(ConstraintPolicy policy) {
        this.policy = policy;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt != null ? createdAt : Instant.now();
    }

    public List<SheetSummary> getSheets() {
        return sheets;
    }

    public void setSheets(List<SheetSummary> sheets) {
        this.sheets = sheets != null ? sheets : new ArrayList<>();
    }

    public List<OverrideEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<OverrideEntry> entries) {
        this.entries = entries != null ? entries : new ArrayList<>();
    }

    public List<String> getSpecMessages() {
        return specMessages;
    }

    public void setSpecMessages(List<String> specMessages) {
        this.specMessages = specMessages != null ? specMessages : new ArrayList<>();
    }
}
