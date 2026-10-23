package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OverrideEntry {

    private String sheet;
    private int row; // 1-based row number
    private String parameter;
    private String resolvedPath;
    private ParamLocation location;
    private String rawValue;
    private JsonNode oldValue;
    private JsonNode coercedValue;
    private OverrideAction action;
    private String reasonCode;
    private String reason;
    private String specPointer;
    private List<String> candidates = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();

    public OverrideEntry() {
    }

    public String getSheet() {
        return sheet;
    }

    public void setSheet(String sheet) {
        this.sheet = sheet;
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

    public String getResolvedPath() {
        return resolvedPath;
    }

    public void setResolvedPath(String resolvedPath) {
        this.resolvedPath = resolvedPath;
    }

    public ParamLocation getLocation() {
        return location;
    }

    public void setLocation(ParamLocation location) {
        this.location = location;
    }

    public String getRawValue() {
        return rawValue;
    }

    public void setRawValue(String rawValue) {
        this.rawValue = rawValue;
    }

    public JsonNode getOldValue() {
        return oldValue;
    }

    public void setOldValue(JsonNode oldValue) {
        this.oldValue = oldValue;
    }

    public JsonNode getCoercedValue() {
        return coercedValue;
    }

    public void setCoercedValue(JsonNode coercedValue) {
        this.coercedValue = coercedValue;
    }

    public OverrideAction getAction() {
        return action;
    }

    public void setAction(OverrideAction action) {
        this.action = action;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public void setReasonCode(ReasonCode code) {
        this.reasonCode = (code != null) ? code.name() : null;
    }

    public ReasonCode getReasonCodeEnum() {
        if (reasonCode == null) return null;
        try {
            return ReasonCode.valueOf(reasonCode);
        } catch (IllegalArgumentException e) {
            return ReasonCode.INTERNAL_ERROR;
        }
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getSpecPointer() {
        return specPointer;
    }

    public void setSpecPointer(String specPointer) {
        this.specPointer = specPointer;
    }

    public List<String> getCandidates() {
        return candidates;
    }

    public void setCandidates(List<String> candidates) {
        this.candidates = candidates != null ? candidates : new ArrayList<>();
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings != null ? warnings : new ArrayList<>();
    }

    public void addWarning(String warning) {
        if (warning != null && !warning.isBlank()) {
            this.warnings.add(warning);
        }
    }
}
