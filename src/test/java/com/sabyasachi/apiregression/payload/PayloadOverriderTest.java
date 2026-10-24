package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PayloadOverriderTest {

    @Test
    void testModifyExistingFields() {
        String original = "{\"user\":{\"name\":\"Alice\",\"age\":25},\"status\":\"active\"}";
        JsonNode root = JsonTemplateCodec.readTree(original);

        ParamPath path = ParamPath.parse("user.name");
        PayloadOverrider.applyOverride(root, path, TextNode.valueOf("Bob"));

        assertEquals("Bob", root.at("/user/name").asText());
        assertEquals(25, root.at("/user/age").asInt());
        assertEquals("active", root.at("/status").asText());
    }

    @Test
    void testCreateIntermediateObject() {
        String original = "{\"title\":\"Article\"}";
        JsonNode root = JsonTemplateCodec.readTree(original);

        ParamPath path = ParamPath.parse("meta.author.name");
        PayloadOverrider.applyOverride(root, path, TextNode.valueOf("Charlie"));

        assertEquals("Charlie", root.at("/meta/author/name").asText());
        assertEquals("Article", root.at("/title").asText());
    }

    @Test
    void testArrayIndexingAndAppend() {
        String original = "{\"tags\":[\"news\",\"tech\"]}";
        JsonNode root = JsonTemplateCodec.readTree(original);

        // Replace element 0
        PayloadOverrider.applyOverride(root, ParamPath.parse("tags[0]"), TextNode.valueOf("breaking"));
        assertEquals("breaking", root.at("/tags/0").asText());
        assertEquals("tech", root.at("/tags/1").asText());

        // Append to array at index 2
        PayloadOverrider.applyOverride(root, ParamPath.parse("tags[2]"), TextNode.valueOf("finance"));
        assertEquals(3, root.at("/tags").size());
        assertEquals("finance", root.at("/tags/2").asText());
    }

    @Test
    void testArrayRootNode() {
        String original = "[{\"id\":1,\"name\":\"Item 1\"},{\"id\":2,\"name\":\"Item 2\"}]";
        JsonNode root = JsonTemplateCodec.readTree(original);
        assertTrue(root.isArray());

        PayloadOverrider.applyOverride(root, ParamPath.parse("$[0].name"), TextNode.valueOf("Updated"));
        assertEquals("Updated", root.at("/0/name").asText());
        assertEquals("Item 2", root.at("/1/name").asText());
    }

    @Test
    void testNothingIsEverDeleted() {
        String original = "{\"user\":{\"name\":\"Alice\",\"secret\":\"hidden\"}}";
        JsonNode root = JsonTemplateCodec.readTree(original);

        // Applying override on user.name only updates, does not delete secret
        PayloadOverrider.applyOverride(root, ParamPath.parse("user.name"), TextNode.valueOf("Bob"));
        assertTrue(root.at("/user").has("secret"));
        assertTrue(root.at("/user").has("name"));
        assertEquals("hidden", root.at("/user/secret").asText());
    }

    @Test
    void testApplyOnCopyLeavesOriginalUntouchedOnFailure() {
        String original = "{\"user\":{\"name\":\"Alice\"}}";
        JsonNode root = JsonTemplateCodec.readTree(original);

        // Out-of-bounds array access on non-existent array
        assertThrows(OverrideException.class, () -> {
            PayloadOverrider.apply(root, ParamPath.parse("user.items[5].id"), 100, OverrideAction.ADDED);
        });

        // Verify root is completely untouched
        assertEquals("Alice", root.at("/user/name").asText());
        assertFalse(root.at("/user").has("items"));
    }

    @Test
    void testPreserveUntouchedFields() {
        String original = "{\"id\":999,\"details\":{\"color\":\"blue\",\"count\":42}}";
        JsonNode root = JsonTemplateCodec.readTree(original);

        PayloadOverrider.applyOverride(root, ParamPath.parse("details.color"), TextNode.valueOf("green"));
        assertEquals("green", root.at("/details/color").asText());
        assertEquals(42, root.at("/details/count").asInt());
        assertEquals(999, root.at("/id").asInt());
    }
}
