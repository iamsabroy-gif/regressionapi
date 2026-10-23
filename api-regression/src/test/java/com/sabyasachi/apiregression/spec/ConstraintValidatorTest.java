package com.sabyasachi.apiregression.spec;

import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sabyasachi.apiregression.payload.ConstraintPolicy;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.NumberSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class ConstraintValidatorTest {

    @Test
    void m5_enumCaseSensitive() {
        Schema<String> schema = new StringSchema();
        schema.setEnum(List.of("ACTIVE", "INACTIVE"));

        // Exact match
        ConstraintValidator.ValidationResult okRes = ConstraintValidator.validate(
                schema, TextNode.valueOf("ACTIVE"), "ACTIVE", ConstraintPolicy.STRICT, false);
        assertTrue(okRes.isValid());

        // Case difference must be rejected per M5 exact comparison
        ConstraintValidator.ValidationResult failRes = ConstraintValidator.validate(
                schema, TextNode.valueOf("active"), "active", ConstraintPolicy.STRICT, false);
        assertFalse(failRes.isValid());
        assertTrue(failRes.getErrorMessage().contains("not in allowed enum values"));
    }

    @Test
    void m5_enumNumericTypedComparison() {
        @SuppressWarnings("rawtypes")
        Schema schema = new NumberSchema();
        schema.setEnum(List.of(1, 2, 3));

        // 1 vs 1.0 matches by BigDecimal comparison
        ConstraintValidator.ValidationResult okRes = ConstraintValidator.validate(
                schema, new DecimalNode(new BigDecimal("1.0")), "1.0", ConstraintPolicy.STRICT, false);
        assertTrue(okRes.isValid());

        // 4 not in enum
        ConstraintValidator.ValidationResult failRes = ConstraintValidator.validate(
                schema, new IntNode(4), "4", ConstraintPolicy.STRICT, false);
        assertFalse(failRes.isValid());
    }

    @Test
    void m5_patternUnanchored_matchesSubstring() {
        Schema<String> schema = new StringSchema();
        schema.setPattern("[A-Z]{2}\\d{4}");

        ConstraintValidator.ValidationResult res = ConstraintValidator.validate(
                schema, TextNode.valueOf("CODE-AB1234-END"), "CODE-AB1234-END", ConstraintPolicy.STRICT, false);
        assertTrue(res.isValid());
    }

    @Test
    void m5_missingNullable_nullIsViolation_warnOrStrictError() {
        Schema<String> schema = new StringSchema();
        // nullable is null (missing), meaning NOT nullable

        ConstraintValidator.ValidationResult strictRes = ConstraintValidator.validate(
                schema, NullNode.getInstance(), "<null>", ConstraintPolicy.STRICT, false);
        assertFalse(strictRes.isValid());
        assertTrue(strictRes.getErrorMessage().contains("non-nullable field"));

        ConstraintValidator.ValidationResult warnRes = ConstraintValidator.validate(
                schema, NullNode.getInstance(), "<null>", ConstraintPolicy.WARN, false);
        assertTrue(warnRes.isValid());
        assertNotNull(warnRes.getWarningMessage());
    }

    @Test
    void m5_oas31NullInTypeArray_allowsNull() {
        Schema<String> schema = new StringSchema();
        schema.setTypes(Set.of("string", "null"));

        ConstraintValidator.ValidationResult res = ConstraintValidator.validate(
                schema, NullNode.getInstance(), "<null>", ConstraintPolicy.STRICT, false);
        assertTrue(res.isValid(), "OAS 3.1 schema with 'null' in types must allow null");
    }

    @Test
    void m5_exclusiveMinimum30And31() {
        // OAS 3.0 style: minimum=10, exclusiveMinimum=true
        @SuppressWarnings("rawtypes")
        Schema schema30 = new NumberSchema();
        schema30.setMinimum(new BigDecimal("10"));
        schema30.setExclusiveMinimum(true);

        ConstraintValidator.ValidationResult res30Equal = ConstraintValidator.validate(
                schema30, new IntNode(10), "10", ConstraintPolicy.STRICT, false);
        assertFalse(res30Equal.isValid(), "Value equal to exclusive minimum must fail");

        ConstraintValidator.ValidationResult res30Greater = ConstraintValidator.validate(
                schema30, new IntNode(11), "11", ConstraintPolicy.STRICT, false);
        assertTrue(res30Greater.isValid());

        // OAS 3.1 style: exclusiveMinimumValue=10
        @SuppressWarnings("rawtypes")
        Schema schema31 = new NumberSchema();
        schema31.setExclusiveMinimumValue(new BigDecimal("10"));

        ConstraintValidator.ValidationResult res31Equal = ConstraintValidator.validate(
                schema31, new IntNode(10), "10", ConstraintPolicy.STRICT, false);
        assertFalse(res31Equal.isValid(), "Value equal to exclusiveMinimumValue must fail");

        ConstraintValidator.ValidationResult res31Greater = ConstraintValidator.validate(
                schema31, new IntNode(11), "11", ConstraintPolicy.STRICT, false);
        assertTrue(res31Greater.isValid());
    }

    @Test
    void m5_int32Overflow() {
        Schema<Number> schema = new IntegerSchema();
        schema.setFormat("int32");

        BigDecimal overflow = BigDecimal.valueOf(Integer.MAX_VALUE).add(BigDecimal.ONE);
        ConstraintValidator.ValidationResult res = ConstraintValidator.validate(
                schema, new DecimalNode(overflow), overflow.toString(), ConstraintPolicy.STRICT, false);
        assertFalse(res.isValid());
        assertTrue(res.getErrorMessage().contains("out of int32 range"));
    }

    @Test
    void s4_strictPolicyTimeoutIsWarningNotError() {
        Schema<String> schema = new StringSchema();
        schema.setPattern("^(.*a){20}$");

        String input = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaab";
        ConstraintValidator.ValidationResult res = ConstraintValidator.validate(
                schema, TextNode.valueOf(input), input, ConstraintPolicy.STRICT, false);

        assertTrue(res.isValid(), "Regex timeout must be treated as warning, not error, even under STRICT");
        assertNotNull(res.getWarningMessage());
        assertTrue(res.getWarningMessage().contains("timed out"));
    }
}
