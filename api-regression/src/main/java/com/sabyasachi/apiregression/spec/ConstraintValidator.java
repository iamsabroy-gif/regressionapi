package com.sabyasachi.apiregression.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.sabyasachi.apiregression.payload.ConstraintPolicy;
import io.swagger.v3.oas.models.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.regex.Pattern;

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

        // 2. Nullable check
        if (valueNode.isNull()) {
            Boolean nullable = schema.getNullable();
            if (Boolean.FALSE.equals(nullable)) {
                String msg = "CONSTRAINT_VIOLATION: Setting null on a non-nullable field.";
                return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
            }
            return ValidationResult.ok();
        }

        // 3. Enum check
        List enums = schema.getEnum();
        if (enums != null && !enums.isEmpty()) {
            boolean matched = false;
            String textVal = valueNode.asText();
            for (Object e : enums) {
                if (String.valueOf(e).equalsIgnoreCase(textVal)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                String msg = "CONSTRAINT_VIOLATION: Value '" + textVal + "' is not in allowed enum values: " + enums;
                return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
            }
        }

        // 4. String length & pattern checks
        if (valueNode.isTextual()) {
            String text = valueNode.asText();
            Integer minLen = schema.getMinLength();
            Integer maxLen = schema.getMaxLength();

            if (minLen != null && text.length() < minLen) {
                String msg = "CONSTRAINT_VIOLATION: String length " + text.length() + " < minLength " + minLen;
                return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
            }
            if (maxLen != null && text.length() > maxLen) {
                String msg = "CONSTRAINT_VIOLATION: String length " + text.length() + " > maxLength " + maxLen;
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
                        java.util.UUID.fromString(text);
                    } catch (Exception ex) {
                        String msg = "CONSTRAINT_VIOLATION: String '" + text + "' is not a valid UUID format.";
                        return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                    }
                }
                if ("date".equals(f) && !text.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    String msg = "CONSTRAINT_VIOLATION: String '" + text + "' is not a valid date format (expected yyyy-MM-dd).";
                    return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                }
            }

            String pattern = schema.getPattern();
            if (pattern != null && !pattern.isBlank()) {
                try {
                    Pattern p = Pattern.compile(pattern);
                    if (!p.matcher(text).matches()) {
                        String msg = "CONSTRAINT_VIOLATION: String '" + text + "' does not match regex pattern: " + pattern;
                        return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
                    }
                } catch (Exception e) {
                    return ValidationResult.warning("PATTERN_CHECK_SKIPPED: Pattern '" + pattern + "' is not compilable by java.util.regex: " + e.getMessage());
                }
            }
        }

        // 5. Numeric ranges (minimum/maximum & int32/int64 format)
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

            if (min != null && num.compareTo(min) < 0) {
                String msg = "CONSTRAINT_VIOLATION: Number " + num + " < minimum " + min;
                return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
            }
            if (max != null && num.compareTo(max) > 0) {
                String msg = "CONSTRAINT_VIOLATION: Number " + num + " > maximum " + max;
                return isStrict ? ValidationResult.error(msg) : ValidationResult.warning(msg);
            }
        }

        return ValidationResult.ok();
    }
}
