package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.DecimalNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TypeCoercerTest {

    private final TypeCoercer coercer = new TypeCoercer(new ObjectMapper());

    @Test
    public void m4_placeholderIntoInteger_unquoted() {
        List<String> warnings = new ArrayList<>();
        TypeCoercer.CoercionResult res = coercer.coerce("{{amt}}", null, "integer", null, null, null, warnings);
        assertNotNull(res.getNode());
        assertTrue(res.getNode().isTextual());
        String val = res.getNode().asText();
        assertTrue(JsonTemplateCodec.isSentinel(val), "Placeholder into integer target must produce unquoted sentinel");
    }

    @Test
    public void m4_existingUnquotedPlaceholder_notTreatedAsString() {
        // If existingNode is a sentinel, determineTargetType should not classify it as "string"
        String sentinel = JsonTemplateCodec.createSentinel("{{var}}");
        TextNode existingNode = TextNode.valueOf(sentinel);

        List<String> warnings = new ArrayList<>();
        // With cellKind NUMERIC, target should become number, not string
        TypeCoercer.CoercionResult res = coercer.coerce("42", null, null, null, existingNode, "NUMERIC", warnings);
        assertNotNull(res.getNode());
        assertTrue(res.getNode().isNumber(), "Should be coerced to number based on cellKind when existingNode is sentinel");
    }

    @Test
    public void m4_specObjectTypeParsesJson() {
        List<String> warnings = new ArrayList<>();
        TypeCoercer.CoercionResult res = coercer.coerce("{\"foo\": \"bar\"}", null, "object", null, null, null, warnings);
        assertNotNull(res.getNode());
        assertTrue(res.getNode().isObject());
        assertEquals("bar", res.getNode().get("foo").asText());

        // Invalid JSON for spec object type should throw INVALID_JSON
        assertThrows(OverrideException.class, () ->
                coercer.coerce("not-json", null, "object", null, null, null, warnings));
    }

    @Test
    public void m4_specArrayTypeParsesJson() {
        List<String> warnings = new ArrayList<>();
        TypeCoercer.CoercionResult res = coercer.coerce("[\"a\", \"b\"]", null, "array", null, null, null, warnings);
        assertNotNull(res.getNode());
        assertTrue(res.getNode().isArray());
        assertEquals(2, res.getNode().size());

        // Invalid JSON for spec array type should throw INVALID_JSON
        assertThrows(OverrideException.class, () ->
                coercer.coerce("not-json", null, "array", null, null, null, warnings));
    }

    @Test
    public void m4_formatDate_dateOnlyOutput() {
        List<String> warnings = new ArrayList<>();
        // Date cell formatted with timestamp should be truncated to yyyy-MM-dd when spec format is date
        TypeCoercer.CoercionResult res = coercer.coerce("2023-05-18T14:30:00", null, "string", "date", null, "DATE", warnings);
        assertNotNull(res.getNode());
        assertEquals("2023-05-18", res.getNode().asText());
    }

    @Test
    public void m4_formatDateTime_noZone() {
        List<String> warnings = new ArrayList<>();
        // Date cell with spec format date-time (or null) should be formatted as yyyy-MM-dd'T'HH:mm:ss without zone
        TypeCoercer.CoercionResult res = coercer.coerce("2023-05-18T14:30:00Z", null, "string", "date-time", null, "DATE", warnings);
        assertNotNull(res.getNode());
        assertEquals("2023-05-18T14:30:00", res.getNode().asText());

        // Date-only input into date-time target should append T00:00:00
        TypeCoercer.CoercionResult res2 = coercer.coerce("2023-05-18", null, "string", "date-time", null, "DATE", warnings);
        assertNotNull(res2.getNode());
        assertEquals("2023-05-18T00:00:00", res2.getNode().asText());
    }

    @Test
    public void m4_leadingZerosDroppedWarning_intoInteger() {
        List<String> warnings = new ArrayList<>();
        TypeCoercer.CoercionResult res = coercer.coerce("0123", null, "integer", null, null, null, warnings);
        assertNotNull(res.getNode());
        assertEquals(123, res.getNode().asInt());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("LEADING_ZEROS_DROPPED")));
    }

    @Test
    public void m4_typeColumnOverrides_withTypeChangedWarning() {
        List<String> warnings = new ArrayList<>();
        // Explicit type 'string' overriding spec type 'integer'
        TypeCoercer.CoercionResult res = coercer.coerce("123", "string", "integer", null, null, null, warnings);
        assertNotNull(res.getNode());
        assertTrue(res.getNode().isTextual());
        assertEquals("123", res.getNode().asText());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("TYPE_CHANGED")));
    }
}
