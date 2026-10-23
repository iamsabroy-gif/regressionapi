package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

public class TypeCoercer {

    public static class CoercionResult {
        private final JsonNode node;
        private final List<String> warnings;

        public CoercionResult(JsonNode node, List<String> warnings) {
            this.node = node;
            this.warnings = warnings != null ? warnings : List.of();
        }

        public JsonNode getNode() {
            return node;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }

    private final ObjectMapper mapper;

    public TypeCoercer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public CoercionResult coerce(String rawValue, String explicitType, String specType, String specFormat,
                                 JsonNode existingNode, String cellKind, List<String> warningCollector) {
        // 1. Literal <null>
        if ("<null>".equalsIgnoreCase(rawValue)) {
            return new CoercionResult(NullNode.getInstance(), warningCollector);
        }

        // 2. Literal <empty>
        if ("<empty>".equalsIgnoreCase(rawValue)) {
            return new CoercionResult(TextNode.valueOf(""), warningCollector);
        }

        // 3. Blank cell
        if (rawValue == null) {
            return new CoercionResult(NullNode.getInstance(), warningCollector);
        }

        // M4: TYPE_CHANGED warning if explicitType differs from specType
        if (explicitType != null && !explicitType.isBlank() && specType != null && !specType.isBlank()) {
            if (!explicitType.trim().equalsIgnoreCase(specType.trim())) {
                warningCollector.add("TYPE_CHANGED: Explicit type '" + explicitType + "' differs from spec type '" + specType + "'.");
            }
        }

        // Determine target type (Precedence: explicitType > specType > existingNode type)
        String targetType = determineTargetType(explicitType, specType, existingNode, cellKind);

        // If value contains placeholder:
        if (rawValue.contains("{{") && rawValue.contains("}}")) {
            if ("integer".equalsIgnoreCase(targetType) || "number".equalsIgnoreCase(targetType) || "boolean".equalsIgnoreCase(targetType)) {
                // Emit unquoted placeholder sentinel
                String sentinel = JsonTemplateCodec.createSentinel(rawValue.trim());
                return new CoercionResult(TextNode.valueOf(sentinel), warningCollector);
            }
            return new CoercionResult(TextNode.valueOf(rawValue), warningCollector);
        }

        // Handle DATE cell kind and date/date-time spec formats
        boolean isDateCell = "DATE".equalsIgnoreCase(cellKind);
        boolean isDateFormat = "date".equalsIgnoreCase(specFormat) || "date".equalsIgnoreCase(explicitType) || "date".equalsIgnoreCase(targetType);
        boolean isDateTimeFormat = "date-time".equalsIgnoreCase(specFormat) || "date-time".equalsIgnoreCase(explicitType) || "date-time".equalsIgnoreCase(targetType);

        if (isDateCell || isDateFormat || isDateTimeFormat) {
            String cleanDate = rawValue.trim();
            if (isDateFormat) {
                // Truncate to yyyy-MM-dd
                if (cleanDate.length() >= 10) {
                    cleanDate = cleanDate.substring(0, 10);
                }
                try {
                    LocalDate.parse(cleanDate, DateTimeFormatter.ISO_LOCAL_DATE);
                    return new CoercionResult(TextNode.valueOf(cleanDate), warningCollector);
                } catch (DateTimeParseException e) {
                    throw new OverrideException(ReasonCode.CONSTRAINT_FORMAT,
                            "Invalid date format, expected yyyy-MM-dd: '" + rawValue + "'");
                }
            } else {
                // date-time: format as yyyy-MM-dd'T'HH:mm:ss (no zone)
                if (cleanDate.length() == 10) {
                    cleanDate = cleanDate + "T00:00:00";
                } else if (cleanDate.length() > 19) {
                    // strip trailing milliseconds or zone if present
                    if (cleanDate.contains("T")) {
                        cleanDate = cleanDate.substring(0, 19);
                    }
                }
                try {
                    java.time.LocalDateTime.parse(cleanDate);
                    return new CoercionResult(TextNode.valueOf(cleanDate), warningCollector);
                } catch (DateTimeParseException e) {
                    throw new OverrideException(ReasonCode.CONSTRAINT_FORMAT,
                            "Invalid date-time format, expected ISO-8601 yyyy-MM-dd'T'HH:mm:ss: '" + rawValue + "'");
                }
            }
        }

        try {
            switch (targetType.toLowerCase()) {
                case "boolean":
                    String cleanBool = rawValue.trim();
                    if ("true".equalsIgnoreCase(cleanBool)) {
                        return new CoercionResult(BooleanNode.TRUE, warningCollector);
                    } else if ("false".equalsIgnoreCase(cleanBool)) {
                        return new CoercionResult(BooleanNode.FALSE, warningCollector);
                    } else {
                        throw new OverrideException(ReasonCode.TYPE_UNCOERCIBLE,
                                "Cannot coerce '" + rawValue + "' to boolean (expected true or false).");
                    }

                case "integer":
                    String cleanInt = rawValue.trim();
                    if (cleanInt.startsWith("0") && cleanInt.length() > 1 && !cleanInt.startsWith("0.")) {
                        warningCollector.add("LEADING_ZEROS_DROPPED: Numeric integer conversion dropped leading zeros from '" + cleanInt + "'.");
                    }
                    try {
                        BigDecimal bdInt = new BigDecimal(cleanInt);
                        return new CoercionResult(new BigIntegerNode(bdInt.toBigIntegerExact()), warningCollector);
                    } catch (Exception e) {
                        throw new OverrideException(ReasonCode.TYPE_UNCOERCIBLE,
                                "Cannot coerce '" + rawValue + "' to integer: " + e.getMessage(), e);
                    }

                case "number":
                    String cleanNum = rawValue.trim();
                    try {
                        BigDecimal bdNum = new BigDecimal(cleanNum);
                        return new CoercionResult(new DecimalNode(bdNum), warningCollector);
                    } catch (Exception e) {
                        throw new OverrideException(ReasonCode.TYPE_UNCOERCIBLE,
                                "Cannot coerce '" + rawValue + "' to number: " + e.getMessage(), e);
                    }

                case "object":
                case "array":
                case "json":
                    try {
                        JsonNode parsedJson = mapper.readTree(rawValue);
                        if ("object".equalsIgnoreCase(targetType) && !parsedJson.isObject()) {
                            throw new OverrideException(ReasonCode.TYPE_UNCOERCIBLE,
                                    "Expected JSON object for type 'object' but got " + parsedJson.getNodeType());
                        }
                        if ("array".equalsIgnoreCase(targetType) && !parsedJson.isArray()) {
                            throw new OverrideException(ReasonCode.TYPE_UNCOERCIBLE,
                                    "Expected JSON array for type 'array' but got " + parsedJson.getNodeType());
                        }
                        return new CoercionResult(parsedJson, warningCollector);
                    } catch (JsonProcessingException e) {
                        throw new OverrideException(ReasonCode.INVALID_JSON,
                                "Failed to parse JSON for type '" + targetType + "': " + e.getOriginalMessage(), e);
                    }

                case "string":
                default:
                    // If spec type is object/array, trigger JSON parse
                    if ("object".equalsIgnoreCase(specType) || "array".equalsIgnoreCase(specType)) {
                        try {
                            return new CoercionResult(mapper.readTree(rawValue), warningCollector);
                        } catch (JsonProcessingException e) {
                            throw new OverrideException(ReasonCode.INVALID_JSON,
                                    "Failed to parse JSON for spec type '" + specType + "': " + e.getOriginalMessage(), e);
                        }
                    }

                    // If existing node is json object/array and raw value looks like JSON, parse it
                    if ("json".equalsIgnoreCase(explicitType) ||
                            (existingNode != null && (existingNode.isObject() || existingNode.isArray()))) {
                        String trimmed = rawValue.trim();
                        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                            try {
                                return new CoercionResult(mapper.readTree(rawValue), warningCollector);
                            } catch (JsonProcessingException e) {
                                throw new OverrideException(ReasonCode.INVALID_JSON,
                                        "Failed to parse JSON: " + e.getOriginalMessage(), e);
                            }
                        }
                    }
                    return new CoercionResult(TextNode.valueOf(rawValue), warningCollector);
            }
        } catch (OverrideException oe) {
            throw oe;
        } catch (Exception e) {
            throw new OverrideException(ReasonCode.TYPE_UNCOERCIBLE,
                    "Failed to coerce value '" + rawValue + "' to type '" + targetType + "': " + e.getMessage(), e);
        }
    }

    private String determineTargetType(String explicitType, String specType, JsonNode existingNode, String cellKind) {
        if (explicitType != null && !explicitType.isBlank()) {
            return explicitType.trim().toLowerCase();
        }
        if (specType != null && !specType.isBlank()) {
            return specType.trim().toLowerCase();
        }
        if (existingNode != null) {
            if (existingNode.isBoolean()) return "boolean";
            if (existingNode.isInt() || existingNode.isLong() || existingNode.isBigInteger()) return "integer";
            if (existingNode.isNumber()) return "number";
            if (existingNode.isObject() || existingNode.isArray()) return "json";
            if (existingNode.isTextual()) {
                // If it is a sentinel, do not treat as string
                if (!JsonTemplateCodec.isSentinel(existingNode.asText())) {
                    return "string";
                }
            }
        }
        if ("BOOLEAN".equalsIgnoreCase(cellKind)) return "boolean";
        if ("NUMERIC".equalsIgnoreCase(cellKind)) return "number";
        return "string";
    }
}
