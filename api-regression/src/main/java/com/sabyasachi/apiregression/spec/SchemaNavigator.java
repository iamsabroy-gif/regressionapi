package com.sabyasachi.apiregression.spec;

import com.sabyasachi.apiregression.payload.ParamPath;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;

import java.util.*;

@SuppressWarnings("rawtypes")
public class SchemaNavigator {

    public static class ResolvedField {
        private final String jsonPointer;
        private final Schema schema;
        private final String specPointer;
        private final boolean passesThroughArray;

        public ResolvedField(String jsonPointer, Schema schema, String specPointer, boolean passesThroughArray) {
            this.jsonPointer = jsonPointer;
            this.schema = schema;
            this.specPointer = specPointer;
            this.passesThroughArray = passesThroughArray;
        }

        public String getJsonPointer() {
            return jsonPointer;
        }

        public Schema getSchema() {
            return schema;
        }

        public String getSpecPointer() {
            return specPointer;
        }

        public boolean isPassesThroughArray() {
            return passesThroughArray;
        }
    }

    public static class Resolution {
        public enum Status {
            FOUND_IN_PAYLOAD,
            DECLARED_IN_SPEC,
            UNDECLARED,
            NO_SCHEMA
        }

        private final Status status;
        private final Schema schema;
        private final String specPointer;
        private final List<String> candidates;
        private final boolean additionalPropertiesAllowed;

        public Resolution(Status status, Schema schema, String specPointer, List<String> candidates, boolean additionalPropertiesAllowed) {
            this.status = status;
            this.schema = schema;
            this.specPointer = specPointer;
            this.candidates = candidates != null ? candidates : Collections.emptyList();
            this.additionalPropertiesAllowed = additionalPropertiesAllowed;
        }

        public static Resolution noSchema() {
            return new Resolution(Status.NO_SCHEMA, null, null, Collections.emptyList(), false);
        }

        public static Resolution declaredInSpec(Schema schema, String specPointer) {
            return new Resolution(Status.DECLARED_IN_SPEC, schema, specPointer, Collections.emptyList(), false);
        }

        public static Resolution undeclared(List<String> candidates, boolean additionalPropertiesAllowed) {
            return new Resolution(Status.UNDECLARED, null, null, candidates, additionalPropertiesAllowed);
        }

        public Status getStatus() {
            return status;
        }

        public Schema getSchema() {
            return schema;
        }

        public String getSpecPointer() {
            return specPointer;
        }

        public List<String> getCandidates() {
            return candidates;
        }

        public boolean isAdditionalPropertiesAllowed() {
            return additionalPropertiesAllowed;
        }
    }

    private final OpenAPI openAPI;

    public SchemaNavigator(OpenAPI openAPI) {
        this.openAPI = openAPI;
    }

    public Schema extractJsonRequestBodySchema(RequestBody requestBody) {
        if (requestBody == null || requestBody.getContent() == null) {
            return null;
        }

        for (Map.Entry<String, MediaType> entry : requestBody.getContent().entrySet()) {
            String mediaType = entry.getKey().toLowerCase();
            if (mediaType.contains("application/json") || mediaType.endsWith("+json") || mediaType.equals("*/*")) {
                Schema s = entry.getValue().getSchema();
                return resolveSchema(s);
            }
        }
        return null;
    }

    public Schema resolveSchema(Schema schema) {
        if (schema == null) return null;
        if (schema.get$ref() != null && !schema.get$ref().isBlank()) {
            return resolveRef(schema.get$ref());
        }
        return schema;
    }

    public Schema resolveRef(String ref) {
        if (ref == null || openAPI == null || openAPI.getComponents() == null) {
            return null;
        }
        String prefix = "#/components/schemas/";
        if (ref.startsWith(prefix)) {
            String name = ref.substring(prefix.length());
            Map<String, Schema> schemas = openAPI.getComponents().getSchemas();
            if (schemas != null && schemas.containsKey(name)) {
                return schemas.get(name);
            }
        }
        // Swagger 2.0 ref style: #/definitions/
        String defPrefix = "#/definitions/";
        if (ref.startsWith(defPrefix)) {
            String name = ref.substring(defPrefix.length());
            Map<String, Schema> schemas = openAPI.getComponents().getSchemas();
            if (schemas != null && schemas.containsKey(name)) {
                return schemas.get(name);
            }
        }
        return null;
    }

    public List<ResolvedField> findSchemaPathsForLeaf(Schema rootSchema, String leafName) {
        List<ResolvedField> results = new ArrayList<>();
        if (rootSchema == null || leafName == null || leafName.isBlank()) {
            return results;
        }

        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        walkSchema(rootSchema, "$", "#/requestBody", leafName.trim(), 0, false, visited, results);
        return results;
    }

    private void walkSchema(Schema schema, String currentPath, String currentSpecPointer,
                            String targetLeaf, int depth, boolean inArray, Set<Object> visited,
                            List<ResolvedField> results) {
        if (schema == null || depth > 10) return;

        Schema resolved = resolveSchema(schema);
        if (resolved == null) return;

        // Cycle guard
        if (!visited.add(resolved)) {
            return;
        }

        try {
            // Check allOf merge
            if (resolved.getAllOf() != null && !resolved.getAllOf().isEmpty()) {
                for (Object sub : resolved.getAllOf()) {
                    if (sub instanceof Schema) {
                        walkSchema((Schema) sub, currentPath, currentSpecPointer + "/allOf", targetLeaf, depth + 1, inArray, visited, results);
                    }
                }
            }

            // Check properties
            Map<String, Schema> props = resolved.getProperties();
            if (props != null) {
                for (Map.Entry<String, Schema> entry : props.entrySet()) {
                    String propName = entry.getKey();
                    Schema propSchema = entry.getValue();
                    String nextPath = currentPath + "." + propName;
                    String nextSpecPointer = currentSpecPointer + "/properties/" + propName;

                    if (propName.equalsIgnoreCase(targetLeaf)) {
                        results.add(new ResolvedField(nextPath, resolveSchema(propSchema), nextSpecPointer, inArray));
                    }

                    walkSchema(propSchema, nextPath, nextSpecPointer, targetLeaf, depth + 1, inArray, visited, results);
                }
            }

            // Check array items
            if (resolved instanceof ArraySchema) {
                ArraySchema arrSchema = (ArraySchema) resolved;
                Schema items = arrSchema.getItems();
                if (items != null) {
                    walkSchema(items, currentPath + "[*]", currentSpecPointer + "/items", targetLeaf, depth + 1, true, visited, results);
                }
            }
        } finally {
            visited.remove(resolved);
        }
    }

    public Resolution resolveExplicitPath(Schema rootSchema, ParamPath path) {
        if (rootSchema == null) {
            return Resolution.noSchema();
        }
        if (path == null || path.getSegments().isEmpty()) {
            return Resolution.declaredInSpec(resolveSchema(rootSchema), "#/requestBody");
        }

        Schema current = resolveSchema(rootSchema);
        StringBuilder specPointer = new StringBuilder("#/requestBody");

        List<ParamPath.Segment> segments = path.getSegments();
        for (int i = 0; i < segments.size(); i++) {
            ParamPath.Segment seg = segments.get(i);
            if (current == null) {
                return Resolution.undeclared(Collections.emptyList(), false);
            }

            if (seg.isArray()) {
                if (current instanceof ArraySchema) {
                    current = resolveSchema(((ArraySchema) current).getItems());
                    specPointer.append("/items");
                } else {
                    return Resolution.undeclared(Collections.emptyList(), false);
                }
            } else {
                Map<String, Schema> props = getAllProperties(current);
                String propName = seg.getPropertyName();
                if (props.containsKey(propName)) {
                    current = resolveSchema(props.get(propName));
                    specPointer.append("/properties/").append(propName);
                } else {
                    // Check did-you-mean suggestions from sibling properties
                    List<String> didYouMean = findDidYouMean(propName, props.keySet());
                    boolean additionalProps = hasAdditionalProperties(current);
                    return Resolution.undeclared(didYouMean, additionalProps);
                }
            }
        }

        return Resolution.declaredInSpec(current, specPointer.toString());
    }

    public Schema navigateExplicitPath(Schema rootSchema, ParamPath path) {
        Resolution res = resolveExplicitPath(rootSchema, path);
        return (res.getStatus() == Resolution.Status.DECLARED_IN_SPEC) ? res.getSchema() : null;
    }

    private boolean hasAdditionalProperties(Schema schema) {
        if (schema == null) return false;
        Object addProps = schema.getAdditionalProperties();
        if (addProps instanceof Boolean) {
            return (Boolean) addProps;
        }
        return (addProps instanceof Schema);
    }

    private List<String> findDidYouMean(String target, Set<String> candidates) {
        List<String> matches = new ArrayList<>();
        if (target == null || candidates == null) return matches;

        for (String cand : candidates) {
            if (cand.equalsIgnoreCase(target)) {
                matches.add(cand);
            } else if (levenshteinDistance(target.toLowerCase(), cand.toLowerCase()) <= 2) {
                matches.add(cand);
            }
            if (matches.size() >= 5) break;
        }
        return matches;
    }

    private static int levenshteinDistance(String s1, String s2) {
        int[] prev = new int[s2.length() + 1];
        for (int j = 0; j <= s2.length(); j++) prev[j] = j;
        for (int i = 1; i <= s1.length(); i++) {
            int[] curr = new int[s2.length() + 1];
            curr[0] = i;
            for (int j = 1; j <= s2.length(); j++) {
                int cost = s1.charAt(i - 1) == s2.charAt(j - 1) ? 0 : 1;
                curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            prev = curr;
        }
        return prev[s2.length()];
    }

    public Map<String, Schema> getAllProperties(Schema schema) {
        if (schema == null) return Collections.emptyMap();
        Map<String, Schema> all = new LinkedHashMap<>();

        Schema resolved = resolveSchema(schema);
        if (resolved == null) return all;

        if (resolved.getAllOf() != null) {
            for (Object sub : resolved.getAllOf()) {
                if (sub instanceof Schema) {
                    all.putAll(getAllProperties((Schema) sub));
                }
            }
        }

        if (resolved.getProperties() != null) {
            all.putAll(resolved.getProperties());
        }

        return all;
    }
}
