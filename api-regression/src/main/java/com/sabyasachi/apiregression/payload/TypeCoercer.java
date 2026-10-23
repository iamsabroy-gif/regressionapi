package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;

import java.math.BigDecimal;
import java.math.BigInteger;
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

        // Determine target type (Precedence: explicitType > specType > existingNode type)
        String targetType = determineTargetType(explicitType, specType, existingNode, cellKind);

        // If value contains placeholder, pass through as text node (or unquoted placeholder if integer/boolean)
        if (rawValue.contains("{{") && rawValue.contains("}}")) {
            return new CoercionResult(TextNode.valueOf(rawValue), warningCollector);
        }

        try {
            switch (targetType.toLowerCase()) {
                case "boolean":
                    if ("true".equalsIgnoreCase(rawValue.trim())) {
                        return new CoercionResult(BooleanNode.TRUE, warningCollector);
                    } else if ("false".equalsIgnoreCase(rawValue.trim())) {
                        return new CoercionResult(BooleanNode.FALSE, warningCollector);
                    } else {
                        throw new IllegalArgumentException("Cannot coerce '" + rawValue + "' to boolean (expected true/false).");
                    }

                case "integer":
                    String cleanInt = rawValue.trim();
                    if (cleanInt.startsWith("0") && cleanInt.length() > 1 && !cleanInt.startsWith("0.")) {
                        warningCollector.add("LEADING_ZEROS_DROPPED: Numeric integer conversion dropped leading zeros from '" + cleanInt + "'.");
                    }
                    BigDecimal bdInt = new BigDecimal(cleanInt);
                    return new CoercionResult(new BigIntegerNode(bdInt.toBigIntegerExact()), warningCollector);

                case "number":
                    String cleanNum = rawValue.trim();
                    BigDecimal bdNum = new BigDecimal(cleanNum);
                    return new CoercionResult(new DecimalNode(bdNum), warningCollector);

                case "json":
                    JsonNode parsedJson = mapper.readTree(rawValue);
                    return new CoercionResult(parsedJson, warningCollector);

                case "string":
                case "date":
                case "date-time":
                default:
                    // If target was json or object/array in existing node, parse JSON
                    if ("json".equalsIgnoreCase(explicitType) ||
                            (existingNode != null && (existingNode.isObject() || existingNode.isArray()))) {
                        if (rawValue.trim().startsWith("{") || rawValue.trim().startsWith("[")) {
                            return new CoercionResult(mapper.readTree(rawValue), warningCollector);
                        }
                    }
                    return new CoercionResult(TextNode.valueOf(rawValue), warningCollector);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to coerce value '" + rawValue + "' to type '" + targetType + "': " + e.getMessage(), e);
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
            if (existingNode.isTextual()) return "string";
        }
        if ("BOOLEAN".equalsIgnoreCase(cellKind)) return "boolean";
        if ("NUMERIC".equalsIgnoreCase(cellKind)) return "number";
        return "string";
    }
}
