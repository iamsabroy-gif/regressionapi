package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class JsonTemplateCodecTest {

    @Test
    void parseAndRenderStandardJson() {
        String json = "{\"name\":\"Alice\",\"age\":30}";
        JsonNode node = JsonTemplateCodec.readTree(json);
        assertNotNull(node);
        assertEquals("Alice", node.get("name").asText());
        assertEquals(30, node.get("age").asInt());

        String rendered = JsonTemplateCodec.writeTree(node, json);
        assertTrue(rendered.contains("\"name\" : \"Alice\"") || rendered.contains("\"name\":\"Alice\""));
    }

    @Test
    void parseAndRenderJsonWithUnquotedPlaceholders() {
        String template = "{\n  \"username\": \"admin\",\n  \"userId\": {{generatedId}},\n  \"active\": {{isActive}}\n}";
        JsonNode node = JsonTemplateCodec.readTree(template);
        assertNotNull(node);
        assertTrue(node.has("userId"));
        assertTrue(node.has("active"));

        String rendered = JsonTemplateCodec.writeTree(node, template);
        assertTrue(rendered.contains("{{generatedId}}"));
        assertTrue(rendered.contains("{{isActive}}"));
        assertFalse(rendered.contains("\"{{generatedId}}\""));
        assertFalse(rendered.contains("\"{{isActive}}\""));
    }

    @Test
    void preservesBigDecimalPrecision() {
        String json = "{\"amount\": 123456789012345678.90123456789}";
        JsonNode node = JsonTemplateCodec.readTree(json);
        assertNotNull(node);
        assertTrue(node.get("amount").isBigDecimal());
        assertEquals(new BigDecimal("123456789012345678.90123456789"), node.get("amount").decimalValue());

        String rendered = JsonTemplateCodec.writeTree(node, json);
        assertTrue(rendered.contains("123456789012345678.90123456789"));
    }

    @Test
    void preservesKeyOrdering() {
        String json = "{\"z\":1,\"a\":2,\"m\":3}";
        JsonNode node = JsonTemplateCodec.readTree(json);
        assertNotNull(node);
        var fieldNames = node.fieldNames();
        assertEquals("z", fieldNames.next());
        assertEquals("a", fieldNames.next());
        assertEquals("m", fieldNames.next());
    }
}
