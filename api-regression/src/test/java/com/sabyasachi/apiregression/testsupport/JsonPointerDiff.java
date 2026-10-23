package com.sabyasachi.apiregression.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class JsonPointerDiff {

    private static final ObjectMapper mapper = new ObjectMapper();

    public static void assertUntouchedExcept(String beforeJson, String afterJson, Set<String> allowedPointers) {
        try {
            JsonNode beforeNode = (beforeJson == null || beforeJson.isBlank()) ? null : mapper.readTree(beforeJson);
            JsonNode afterNode = (afterJson == null || afterJson.isBlank()) ? null : mapper.readTree(afterJson);
            assertUntouchedExcept(beforeNode, afterNode, allowedPointers);
        } catch (Exception e) {
            fail("Failed to parse JSON for untouched check: " + e.getMessage());
        }
    }

    public static void assertUntouchedExcept(JsonNode beforeNode, JsonNode afterNode, Set<String> allowedPointers) {
        if (beforeNode == null && afterNode == null) return;
        if (beforeNode == null || afterNode == null) {
            if (allowedPointers.contains("") || allowedPointers.contains("/")) return;
            fail("One node is null while other is not. Before: " + beforeNode + ", After: " + afterNode);
        }

        Map<String, JsonNode> beforePointers = new LinkedHashMap<>();
        Map<String, JsonNode> afterPointers = new LinkedHashMap<>();

        collectPointers("", beforeNode, beforePointers);
        collectPointers("", afterNode, afterPointers);

        // Normalize allowed pointers (ensure leading slash)
        Set<String> normalizedAllowed = new HashSet<>();
        for (String p : allowedPointers) {
            if (!p.startsWith("/") && !p.isEmpty()) {
                normalizedAllowed.add("/" + p.replace('.', '/'));
            } else {
                normalizedAllowed.add(p);
            }
        }

        for (Map.Entry<String, JsonNode> entry : beforePointers.entrySet()) {
            String ptr = entry.getKey();
            if (isCoveredByAllowed(ptr, normalizedAllowed)) {
                continue;
            }
            assertTrue(afterPointers.containsKey(ptr), "Pointer missing in updated tree: " + ptr);
            assertEquals(entry.getValue(), afterPointers.get(ptr), "Untouched pointer value changed at: " + ptr);
        }
    }

    private static boolean isCoveredByAllowed(String ptr, Set<String> allowed) {
        for (String a : allowed) {
            if (ptr.equals(a)) {
                return true;
            }
            if (ptr.startsWith(a + "/") || ptr.startsWith(a + "[")) {
                return true;
            }
            if (ptr.equals("/") || ptr.isEmpty() || a.startsWith(ptr + "/") || a.startsWith(ptr + "[")) {
                return true;
            }
        }
        return false;
    }

    private static void collectPointers(String currentPtr, JsonNode node, Map<String, JsonNode> out) {
        out.put(currentPtr.isEmpty() ? "/" : currentPtr, node);
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                collectPointers(currentPtr + "/" + field.getKey(), field.getValue(), out);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                collectPointers(currentPtr + "[" + i + "]", node.get(i), out);
            }
        }
    }
}
