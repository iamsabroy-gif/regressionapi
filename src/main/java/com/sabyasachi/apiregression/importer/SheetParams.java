package com.sabyasachi.apiregression.importer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SheetParams {

    private String sheetName;
    private String target;
    private String method;
    private String path;
    private String defaultLocation;
    private String status;
    private String skipReason;
    private final Map<String, String> metadata = new LinkedHashMap<>();
    private final List<ParamRow> rows = new ArrayList<>();
    private final List<String> sheetWarnings = new ArrayList<>();

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    public SheetParams() {
    }

    public SheetParams(String sheetName) {
        this(sheetName, sheetName);
    }

    public SheetParams(String sheetName, String target) {
        this.sheetName = sheetName;
        this.target = target != null ? target : sheetName;
        parseMethodAndPath(this.target);
    }

    private void parseMethodAndPath(String text) {
        if (text == null) return;
        String trimmed = text.trim();
        String[] parts = trimmed.split("\\s+", 2);
        if (parts.length == 2) {
            String m = parts[0].toUpperCase();
            if (m.equals("GET") || m.equals("POST") || m.equals("PUT") || m.equals("DELETE") ||
                    m.equals("PATCH") || m.equals("HEAD") || m.equals("OPTIONS")) {
                this.method = m;
                this.path = parts[1].replace('_', '/');
                if (!this.path.startsWith("/")) {
                    this.path = "/" + this.path;
                }
            }
        }
    }

    public String getSheetName() {
        return sheetName;
    }

    public void setSheetName(String sheetName) {
        this.sheetName = sheetName;
        if (this.target == null) {
            parseMethodAndPath(sheetName);
        }
    }

    public String getTarget() {
        return target != null && !target.isBlank() ? target : sheetName;
    }

    public void setTarget(String target) {
        this.target = target;
        parseMethodAndPath(target);
    }

    public String getOperationId() {
        return getTarget();
    }

    public void setOperationId(String opId) {
        setTarget(opId);
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public void setRows(List<ParamRow> newRows) {
        this.rows.clear();
        if (newRows != null) this.rows.addAll(newRows);
    }

    public String getDefaultLocation() {
        return defaultLocation;
    }

    public void setDefaultLocation(String defaultLocation) {
        this.defaultLocation = defaultLocation;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public List<ParamRow> getRows() {
        return rows;
    }

    public List<String> getSheetWarnings() {
        return sheetWarnings;
    }

    public void addSheetWarning(String warning) {
        if (warning != null && !warning.isBlank()) {
            this.sheetWarnings.add(warning);
        }
    }
}
