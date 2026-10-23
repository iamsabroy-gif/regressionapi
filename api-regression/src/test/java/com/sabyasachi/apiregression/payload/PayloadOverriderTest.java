package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PayloadOverriderTest {

    @Test
    void testModifyExistingFields() {
        String original = "{\"user\":{\"name\":\"Alice\",\"age\":25},\"status\":\"active\"}";
        ObjectNode root = JsonTemplateCodec.readTree(original);

        ParamPath path = ParamPath.parse("user.name");
        PayloadOverrider.applyOverride(root, path, TextNode.valueOf("Bob"));

        assertEquals("Bob", root.at("/user/name").asText());
        assertEquals(25, root.at("/user/age").asInt());
        assertEquals("active", root.at("/status").asText());
    }

    @Test
    void testCreateIntermediateObject() {
        String original = "{\"title\":\"Article\"}";
        ObjectNode root = JsonTemplateCodec.readTree(original);

        ParamPath path = ParamPath.parse("meta.author.name");
        PayloadOverrider.applyOverride(root, path, TextNode.valueOf("Charlie"));

        assertEquals("Charlie", root.at("/meta/author/name").asText());
        assertEquals("Article", root.at("/title").asText());
    }

    @Test
    void testArrayIndexingAndAppend() {
        String original = "{\"tags\":[\"news\",\"tech\"]}";
        ObjectNode root = JsonTemplateCodec.readTree(original);

        // Replace element 0
        PayloadOverrider.applyOverride(root, ParamPath.parse("tags[0]"), TextNode.valueOf("breaking"));
        assertEquals("breaking", root.at("/tags/0").asText());
        assertEquals("tech", root.at("/tags/1").asText());

        // Append to array
        PayloadOverrider.applyOverride(root, ParamPath.parse("tags[]"), TextNode.valueOf("finance"));
        assertEquals(3, root.at("/tags").size());
        assertEquals("finance", root.at("/tags/2").asText());
    }

    @Test
    void testDeleteField() {
        String original = "{\"user\":{\"name\":\"Alice\",\"secret\":\"hidden\"}}";
        ObjectNode root = JsonTemplateCodec.readTree(original);

        PayloadOverrider.removeOverride(root, ParamPath.parse("user.secret"));
        assertFalse(root.at("/user").has("secret"));
        assertTrue(root.at("/user").has("name"));
    }

    @Test
    void testPreserveUntouchedFields() {
        String original = "{\"id\":999,\"details\":{\"color\":\"blue\",\"count\":42}}";
        ObjectNode root = JsonTemplateCodec.readTree(original);

        PayloadOverrider.applyOverride(root, ParamPath.parse("details.color"), TextNode.valueOf("green"));
        assertEquals("green", root.at("/details/color").asText());
        assertEquals(42, root.at("/details/count").asInt());
        assertEquals(999, root.at("/id").asInt());
    }
}
