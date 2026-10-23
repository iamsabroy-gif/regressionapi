package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

public class PayloadOverrider {

    public static class ApplyResult {
        private final JsonNode oldValue;
        private final boolean isAdded;
        private final JsonNode resultRoot;

        public ApplyResult(JsonNode oldValue, boolean isAdded, JsonNode resultRoot) {
            this.oldValue = oldValue;
            this.isAdded = isAdded;
            this.resultRoot = resultRoot;
        }

        public JsonNode getOldValue() {
            return oldValue;
        }

        public boolean isAdded() {
            return isAdded;
        }

        public JsonNode getResultRoot() {
            return resultRoot;
        }
    }

    public static JsonNode findExistingNode(JsonNode root, ParamPath path) {
        if (root == null || path == null) return null;
        JsonNode current = root;

        for (ParamPath.Segment seg : path.getSegments()) {
            if (current == null) return null;
            if (seg.isArray()) {
                if (!current.isArray()) return null;
                ArrayNode arr = (ArrayNode) current;
                int idx = seg.getArrayIndex();
                if (idx < 0 || idx >= arr.size()) return null;
                current = arr.get(idx);
            } else {
                if (!current.isObject()) return null;
                current = current.get(seg.getPropertyName());
            }
        }
        return current;
    }

    public static ApplyResult applyOverride(JsonNode root, ParamPath path, JsonNode newValue) {
        if (root == null) {
            throw new OverrideException(ReasonCode.INTERNAL_ERROR, "Root node cannot be null.");
        }
        if (path == null || path.getSegments().isEmpty()) {
            throw new OverrideException(ReasonCode.PATH_INVALID, "ParamPath cannot be empty.");
        }

        List<ParamPath.Segment> segments = path.getSegments();
        JsonNode current = root;
        JsonNode oldValue = null;
        boolean added = false;

        for (int i = 0; i < segments.size(); i++) {
            ParamPath.Segment seg = segments.get(i);
            boolean isLast = (i == segments.size() - 1);

            if (seg.isArray()) {
                if (!current.isArray()) {
                    throw new OverrideException(ReasonCode.PATH_INVALID,
                            "Expected array at path segment " + seg + " but found: " + current.getNodeType());
                }
                ArrayNode arr = (ArrayNode) current;
                int idx = seg.getArrayIndex();

                if (isLast) {
                    if (idx == Integer.MAX_VALUE || idx == arr.size()) {
                        arr.add(newValue);
                        added = true;
                    } else if (idx < arr.size() && idx >= 0) {
                        oldValue = arr.get(idx);
                        arr.set(idx, newValue);
                    } else {
                        throw new OverrideException(ReasonCode.INDEX_OUT_OF_RANGE,
                                "Array index [" + idx + "] out of bounds for array of size " + arr.size() + "; sparse array fill is not allowed.");
                    }
                } else {
                    if (idx < arr.size()) {
                        current = arr.get(idx);
                    } else if (idx == arr.size()) {
                        // Create intermediate object or array for next segment
                        ParamPath.Segment nextSeg = segments.get(i + 1);
                        JsonNode nextNode = nextSeg.isArray() ? JsonNodeFactory.instance.arrayNode() : JsonNodeFactory.instance.objectNode();
                        arr.add(nextNode);
                        current = nextNode;
                    } else {
                        throw new OverrideException(ReasonCode.INDEX_OUT_OF_RANGE,
                                "Array index [" + idx + "] out of bounds for array of size " + arr.size() + "; sparse array fill is not allowed.");
                    }
                }
            } else {
                // Object property segment
                if (!current.isObject()) {
                    throw new OverrideException(ReasonCode.PATH_INVALID,
                            "Expected JSON object at path segment " + seg + " but found: " + current.getNodeType());
                }
                ObjectNode obj = (ObjectNode) current;
                String prop = seg.getPropertyName();

                if (isLast) {
                    oldValue = obj.get(prop);
                    added = (oldValue == null);
                    obj.set(prop, newValue);
                } else {
                    JsonNode child = obj.get(prop);
                    if (child == null || child.isNull()) {
                        ParamPath.Segment nextSeg = segments.get(i + 1);
                        JsonNode nextNode = nextSeg.isArray() ? JsonNodeFactory.instance.arrayNode() : JsonNodeFactory.instance.objectNode();
                        obj.set(prop, nextNode);
                        current = nextNode;
                    } else {
                        current = child;
                    }
                }
            }
        }

        return new ApplyResult(oldValue, added, root);
    }

    public static List<String> findCandidatePathsForLeaf(JsonNode root, String leafName) {
        List<String> candidates = new ArrayList<>();
        if (root == null || leafName == null || leafName.isBlank()) return candidates;
        collectLeafPaths(root, "$", leafName.trim(), candidates);
        return candidates;
    }

    private static void collectLeafPaths(JsonNode node, String currentPath, String leafName, List<String> results) {
        if (node == null) return;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String prop = field.getKey();
                String path = currentPath + "." + prop;
                if (prop.equalsIgnoreCase(leafName)) {
                    results.add(path);
                }
                collectLeafPaths(field.getValue(), path, leafName, results);
            }
        } else if (node.isArray()) {
            int idx = 0;
            for (JsonNode elem : node) {
                collectLeafPaths(elem, currentPath + "[" + idx + "]", leafName, results);
                idx++;
            }
        }
    }

    public static ApplyResult apply(JsonNode root, ParamPath path, Object value, OverrideAction action) {
        if (root == null || (!root.isObject() && !root.isArray())) {
            throw new OverrideException(ReasonCode.PATH_INVALID,
                    "Root node must be ObjectNode or ArrayNode, but was: " + (root != null ? root.getNodeType() : "null"));
        }
        JsonNode nodeVal;
        if (value instanceof JsonNode jn) {
            nodeVal = jn;
        } else if (value == null) {
            nodeVal = JsonNodeFactory.instance.nullNode();
        } else if (value instanceof String s) {
            nodeVal = JsonNodeFactory.instance.textNode(s);
        } else if (value instanceof Number n) {
            nodeVal = JsonNodeFactory.instance.numberNode(new java.math.BigDecimal(n.toString()));
        } else if (value instanceof Boolean b) {
            nodeVal = JsonNodeFactory.instance.booleanNode(b);
        } else {
            nodeVal = JsonNodeFactory.instance.textNode(value.toString());
        }

        // Apply on a row-scoped deep copy so failure leaves the original untouched
        JsonNode workingCopy = root.deepCopy();
        return applyOverride(workingCopy, path, nodeVal);
    }
}
