package com.sabyasachi.apiregression.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.sabyasachi.apiregression.payload.ConstraintPolicy;
import com.sabyasachi.apiregression.util.SafeRegex;
import io.swagger.v3.oas.models.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@SuppressWarnings("rawtypes")
public class ConstraintValidator {

    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;
        private final String warningMessage;

        public ValidationResult(boolean valid, String errorMessage, String warningMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.warningMessage = warningMessage;
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, null, null);
        }

        public static ValidationResult warning(String msg) {
            return new ValidationResult(true, null, msg);
        }

        public static ValidationResult error(String msg) {
            return new ValidationResult(false, msg, null);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public String getWarningMessage() {
            return warningMessage;
        }
    }

    public static ValidationResult validate(Schema schema, JsonNode valueNode, String rawValue,
                                            ConstraintPolicy policy, boolean isAdded) {
        if (schema == null || valueNode == null) {
            return ValidationResult.ok();
        }

        // Values containing {{var}} placeholders skip constraint checks
        if (rawValue != null && rawValue.contains("{{") && rawValue.contains("}}")) {
            return ValidationResult.ok();
        }

        boolean isStrict = (policy == ConstraintPolicy.STRICT);

        // 1. readOnly property check
        Boolean readOnly = schema.getReadOnly();
        if (Boolean.TRUE.equals(readOnly)) {
            if (isAdded) {
                return ValidationResult.error("CONSTRAINT_VIOLATION: Cannot ADD field because it is marked 'readOnly' in the spec.");
            } else {
                String msg = "CONSTRAINT_WARNING: Modifying a field marked 'readOnly' in the spec.";
                return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
            }
        }

        // 2. Nullable check (OAS 3.0: nullable: true; OAS 3.1: "null" in types array; default: false)
        if (valueNode.isNull()) {
            boolean nullable = Boolean.TRUE.equals(schema.getNullable())
                    || (schema.getTypes() != null && schema.getTypes().contains("null"));
            if (!nullable) {
                String msg = "CONSTRAINT_VIOLATION: Setting null on a non-nullable field.";
                return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
            }
            return ValidationResult.ok();
        }

        // 3. Enum check - exact, case-sensitive, typed comparison
        List enums = schema.getEnum();
        if (enums != null && !enums.isEmpty()) {
            boolean matched = false;
            for (Object e : enums) {
                if (e == null) {
                    if (valueNode.isNull()) {
                        matched = true;
                        break;
                    }
                } else if (valueNode.isNumber() && e instanceof Number) {
                    try {
                        BigDecimal nodeNum = new BigDecimal(valueNode.asText());
                        BigDecimal enumNum = new BigDecimal(e.toString());
                        if (nodeNum.compareTo(enumNum) == 0) {
                            matched = true;
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                } else if (valueNode.isBoolean() && e instanceof Boolean) {
                    if (valueNode.asBoolean() == (Boolean) e) {
                        matched = true;
                        break;
                    }
                } else if (valueNode.isTextual()) {
                    if (e.toString().equals(valueNode.asText())) {
                        matched = true;
                        break;
                    }
                } else {
                    if (Objects.equals(e.toString(), valueNode.asText())) {
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) {
                String msg = "CONSTRAINT_VIOLATION: Value '" + valueNode.asText() + "' is not in allowed enum values: " + enums;
                return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
            }
        }

        // 4. String length & pattern checks
        if (valueNode.isTextual()) {
            String text = valueNode.asText();
            int codePoints = text.codePointCount(0, text.length());
            Integer minLen = schema.getMinLength();
            Integer maxLen = schema.getMaxLength();

            if (minLen != null && codePoints < minLen) {
                String msg = "CONSTRAINT_VIOLATION: String length " + codePoints + " < minLength " + minLen;
                return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
            }
            if (maxLen != null && codePoints > maxLen) {
                String msg = "CONSTRAINT_VIOLATION: String length " + codePoints + " > maxLength " + maxLen;
                return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
            }

            String format = schema.getFormat();
            if (format != null && !format.isBlank()) {
                String f = format.trim().toLowerCase();
                if ("email".equals(f) && (!text.contains("@") || !text.contains("."))) {
                    String msg = "CONSTRAINT_VIOLATION: String '" + text + "' is not a valid email format.";
                    return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                }
                if ("uuid".equals(f)) {
                    try {
                        UUID.fromString(text);
                    } catch (Exception ex) {
                        String msg = "CONSTRAINT_VIOLATION: String '" + text + "' is not a valid UUID format.";
                        return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                    }
                }
                if ("date".equals(f) && !text.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    String msg = "CONSTRAINT_VIOLATION: String '" + text + "' is not a valid date format (expected yyyy-MM-dd).";
                    return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                }
                if ("date-time".equals(f) && !text.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2})?")) {
                    String msg = "CONSTRAINT_VIOLATION: String '" + text + "' is not a valid date-time format.";
                    return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                }
            }

            String pattern = schema.getPattern();
            if (pattern != null && !pattern.isBlank()) {
                SafeRegex.SafeRegexResult sres = SafeRegex.find(pattern, text);
                if (sres.isSkipped()) {
                    // SafeRegex skip is a warning under both WARN and STRICT policy
                    return ValidationResult.warning(sres.getWarning());
                } else if (!sres.isMatched()) {
                    String msg = "CONSTRAINT_VIOLATION: String '" + text + "' does not match regex pattern: " + pattern;
                    return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                }
            }
        }

        // 5. Numeric ranges (minimum/maximum & exclusiveMinimum/exclusiveMaximum for 3.0 and 3.1)
        if (valueNode.isNumber()) {
            BigDecimal num = new BigDecimal(valueNode.asText());
            BigDecimal min = schema.getMinimum();
            BigDecimal max = schema.getMaximum();

            String format = schema.getFormat();
            if ("int32".equalsIgnoreCase(format)) {
                if (num.compareTo(BigDecimal.valueOf(Integer.MIN_VALUE)) < 0 || num.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
                    String msg = "CONSTRAINT_VIOLATION: Number " + num + " out of int32 range.";
                    return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                }
            } else if ("int64".equalsIgnoreCase(format)) {
                if (num.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0 || num.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
                    String msg = "CONSTRAINT_VIOLATION: Number " + num + " out of int64 range.";
                    return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                }
            }

            // Minimum check (3.0 boolean flag vs 3.1 numeric value)
            BigDecimal exMinValue = schema.getExclusiveMinimumValue();
            if (exMinValue != null) {
                if (num.compareTo(exMinValue) <= 0) {
                    String msg = "CONSTRAINT_VIOLATION: Number " + num + " <= exclusiveMinimum " + exMinValue;
                    return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                }
            } else if (Boolean.TRUE.equals(schema.getExclusiveMinimum()) && min != null) {
                if (num.compareTo(min) <= 0) {
                    String msg = "CONSTRAINT_VIOLATION: Number " + num + " <= exclusiveMinimum " + min;
                    return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                }
            } else if (min != null) {
                if (num.compareTo(min) < 0) {
                    String msg = "CONSTRAINT_VIOLATION: Number " + num + " < minimum " + min;
                    return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                }
            }

            // Maximum check (3.0 boolean flag vs 3.1 numeric value)
            BigDecimal exMaxValue = schema.getExclusiveMaximumValue();
            if (exMaxValue != null) {
                if (num.compareTo(exMaxValue) >= 0) {
                    String msg = "CONSTRAINT_VIOLATION: Number " + num + " >= exclusiveMaximum " + exMaxValue;
                    return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                }
            } else if (Boolean.TRUE.equals(schema.getExclusiveMaximum()) && max != null) {
                if (num.compareTo(max) >= 0) {
                    String msg = "CONSTRAINT_VIOLATION: Number " + num + " >= exclusiveMaximum " + max;
                    return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                }
            } else if (max != null) {
                if (num.compareTo(max) > 0) {
                    String msg = "CONSTRAINT_VIOLATION: Number " + num + " > maximum " + max;
                    return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                }
            }
        }

        return ValidationResult.ok();
    }
}
