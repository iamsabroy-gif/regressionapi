package com.sabyasachi.apiregression.importer;

import com.sabyasachi.apiregression.payload.OverrideAction;

import java.util.ArrayList;
import java.util.List;

public class ParamRow {

    private int row; // 1-based row number
    private String parameter;
    private String rawValue;
    private String cellKind = "TEXT";
    private String explicitType;
    private String explicitLocation;
    private String op = "replace";
    private boolean enabled = true;
    private String notes;
    private final List<String> warnings = new ArrayList<>();

    public ParamRow() {
    }

    public ParamRow(int row, String parameter, String rawValue) {
        this.row = row;
        this.parameter = parameter;
        this.rawValue = rawValue;
    }

    public ParamRow(String parameter, String rawValue, String explicitType, String op, int row) {
        this.parameter = parameter;
        this.rawValue = rawValue;
        this.explicitType = explicitType;
        this.op = op != null ? op : "replace";
        this.row = row;
    }

    public int getRow() {
        return row;
    }

    public void setRow(int row) {
        this.row = row;
    }

    public String getParameter() {
        return parameter;
    }

    public void setParameter(String parameter) {
        this.parameter = parameter;
    }

    public String getRawValue() {
        return rawValue;
    }

    public void setRawValue(String rawValue) {
        this.rawValue = rawValue;
    }

    public String getCellKind() {
        return cellKind;
    }

    public void setCellKind(String cellKind) {
        this.cellKind = cellKind;
    }

    public String getExplicitType() {
        return explicitType;
    }

    public void setExplicitType(String explicitType) {
        this.explicitType = explicitType;
    }

    public String getExplicitLocation() {
        return explicitLocation;
    }

    public void setExplicitLocation(String explicitLocation) {
        this.explicitLocation = explicitLocation;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void addWarning(String warning) {
        if (warning != null && !warning.isBlank()) {
            this.warnings.add(warning);
        }
    }

    public String getPath() {
        return parameter;
    }

    public void setPath(String path) {
        this.parameter = path;
    }

    public String getOp() {
        return op != null ? op : "replace";
    }

    public void setOp(String op) {
        this.op = op;
    }

    public String getType() {
        return explicitType;
    }

    public void setType(String type) {
        this.explicitType = type;
    }
}
