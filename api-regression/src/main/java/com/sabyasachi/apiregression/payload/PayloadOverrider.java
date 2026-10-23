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

        public ApplyResult(JsonNode oldValue, boolean isAdded) {
            this.oldValue = oldValue;
            this.isAdded = isAdded;
        }

        public JsonNode getOldValue() {
            return oldValue;
        }

        public boolean isAdded() {
            return isAdded;
        }
    }

    public static JsonNode findExistingNode(ObjectNode root, ParamPath path) {
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

    public static ApplyResult applyOverride(ObjectNode root, ParamPath path, JsonNode newValue) {
        if (root == null) {
            throw new IllegalArgumentException("Root ObjectNode cannot be null.");
        }
        if (path == null || path.getSegments().isEmpty()) {
            throw new IllegalArgumentException("ParamPath cannot be empty.");
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
                    throw new IllegalArgumentException("Expected array at path segment " + seg + " but found: " + current.getNodeType());
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
                        throw new IllegalArgumentException("Array index [" + idx + "] out of bounds for array of size " + arr.size() + "; sparse array fill is not allowed.");
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
                        throw new IllegalArgumentException("Array index [" + idx + "] out of bounds for array of size " + arr.size() + "; sparse array fill is not allowed.");
                    }
                }
            } else {
                // Object property segment
                if (!current.isObject()) {
                    throw new IllegalArgumentException("Expected JSON object at path segment " + seg + " but found: " + current.getNodeType());
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

        return new ApplyResult(oldValue, added);
    }

    public static List<String> findCandidatePathsForLeaf(ObjectNode root, String leafName) {
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

    public static ApplyResult removeOverride(ObjectNode root, ParamPath path) {
        if (root == null || path == null || path.getSegments().isEmpty()) {
            return new ApplyResult(null, false);
        }
        List<ParamPath.Segment> segments = path.getSegments();
        JsonNode current = root;
        for (int i = 0; i < segments.size(); i++) {
            ParamPath.Segment seg = segments.get(i);
            boolean isLast = (i == segments.size() - 1);
            if (isLast) {
                if (seg.isArray()) {
                    if (current.isArray()) {
                        ArrayNode arr = (ArrayNode) current;
                        int idx = seg.getArrayIndex();
                        if (idx >= 0 && idx < arr.size()) {
                            JsonNode old = arr.remove(idx);
                            return new ApplyResult(old, false);
                        }
                    }
                } else {
                    if (current.isObject()) {
                        ObjectNode obj = (ObjectNode) current;
                        JsonNode old = obj.remove(seg.getPropertyName());
                        return new ApplyResult(old, false);
                    }
                }
            } else {
                if (seg.isArray()) {
                    if (!current.isArray()) return new ApplyResult(null, false);
                    ArrayNode arr = (ArrayNode) current;
                    int idx = seg.getArrayIndex();
                    if (idx < 0 || idx >= arr.size()) return new ApplyResult(null, false);
                    current = arr.get(idx);
                } else {
                    if (!current.isObject()) return new ApplyResult(null, false);
                    current = current.get(seg.getPropertyName());
                    if (current == null) return new ApplyResult(null, false);
                }
            }
        }
        return new ApplyResult(null, false);
    }

    public static ApplyResult apply(JsonNode root, ParamPath path, Object value, OverrideAction action) {
        if (!(root instanceof ObjectNode objRoot)) {
            throw new IllegalArgumentException("Root node must be ObjectNode, but was: " + (root != null ? root.getNodeType() : "null"));
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
        return applyOverride(objRoot, path, nodeVal);
    }
}
