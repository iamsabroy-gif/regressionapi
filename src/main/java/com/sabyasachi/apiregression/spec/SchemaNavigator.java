package com.sabyasachi.apiregression.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.sabyasachi.apiregression.payload.OverrideException;
import com.sabyasachi.apiregression.payload.ParamPath;
import com.sabyasachi.apiregression.payload.ReasonCode;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.RequestBody;

import java.util.*;

@SuppressWarnings("rawtypes")
public class SchemaNavigator {

    public static class SchemaView {
        private final Schema schema;

        public SchemaView(Schema schema) {
            this.schema = schema;
        }

        public Schema getSchema() {
            return schema;
        }

        public Set<String> getTypes() {
            if (schema == null) return Collections.emptySet();
            if (schema.getTypes() != null && !schema.getTypes().isEmpty()) {
                return schema.getTypes();
            }
            if (schema.getType() != null && !schema.getType().isBlank()) {
                return Set.of(schema.getType());
            }
            return Collections.emptySet();
        }

        public boolean isNullable() {
            if (schema == null) return false;
            if (Boolean.TRUE.equals(schema.getNullable())) return true;
            return getTypes().contains("null");
        }

        public boolean isArray() {
            if (schema instanceof ArraySchema) return true;
            return getTypes().contains("array");
        }

        public boolean isObject() {
            if (schema != null && (schema.getProperties() != null || schema.getAdditionalProperties() != null)) return true;
            return getTypes().contains("object");
        }

        public Schema getItems() {
            if (schema == null) return null;
            if (schema instanceof ArraySchema) {
                return ((ArraySchema) schema).getItems();
            }
            if (schema.getItems() != null) {
                return schema.getItems();
            }
            if (schema.getPrefixItems() != null && !schema.getPrefixItems().isEmpty()) {
                Object pi = schema.getPrefixItems().get(0);
                if (pi instanceof Schema) return (Schema) pi;
            }
            return null;
        }

        public Object getAdditionalProperties() {
            if (schema == null) return null;
            return schema.getAdditionalProperties();
        }
    }

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
        private final Schema additionalPropertiesSchema;

        public Resolution(Status status, Schema schema, String specPointer, List<String> candidates, boolean additionalPropertiesAllowed, Schema additionalPropertiesSchema) {
            this.status = status;
            this.schema = schema;
            this.specPointer = specPointer;
            this.candidates = candidates != null ? candidates : Collections.emptyList();
            this.additionalPropertiesAllowed = additionalPropertiesAllowed;
            this.additionalPropertiesSchema = additionalPropertiesSchema;
        }

        public static Resolution noSchema() {
            return new Resolution(Status.NO_SCHEMA, null, null, Collections.emptyList(), false, null);
        }

        public static Resolution declaredInSpec(Schema schema, String specPointer) {
            return new Resolution(Status.DECLARED_IN_SPEC, schema, specPointer, Collections.emptyList(), false, null);
        }

        public static Resolution undeclared(List<String> candidates, boolean additionalPropertiesAllowed, Schema additionalPropertiesSchema) {
            return new Resolution(Status.UNDECLARED, null, null, candidates, additionalPropertiesAllowed, additionalPropertiesSchema);
        }

        public static Resolution undeclared(List<String> candidates, boolean additionalPropertiesAllowed) {
            return new Resolution(Status.UNDECLARED, null, null, candidates, additionalPropertiesAllowed, null);
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

        public Schema getAdditionalPropertiesSchema() {
            return additionalPropertiesSchema;
        }
    }

    private final OpenAPI openAPI;

    public SchemaNavigator(OpenAPI openAPI) {
        this.openAPI = openAPI;
        if (openAPI != null && openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
            for (Map.Entry<String, Schema> entry : openAPI.getComponents().getSchemas().entrySet()) {
                if (entry.getValue() != null && entry.getValue().getName() == null) {
                    entry.getValue().setName(entry.getKey());
                }
            }
        }
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
        if (ref == null || openAPI == null) {
            return null;
        }
        String prefix = "#/components/schemas/";
        if (ref.startsWith(prefix)) {
            String name = ref.substring(prefix.length());
            if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
                Schema s = openAPI.getComponents().getSchemas().get(name);
                if (s != null && s.getName() == null) {
                    s.setName(name);
                }
                return s;
            }
        }
        String defPrefix = "#/definitions/";
        if (ref.startsWith(defPrefix)) {
            String name = ref.substring(defPrefix.length());
            if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
                return openAPI.getComponents().getSchemas().get(name);
            }
        }
        if (ref.startsWith("#/$defs/")) {
            String name = ref.substring("#/$defs/".length());
            if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
                return openAPI.getComponents().getSchemas().get(name);
            }
        }
        if (ref.startsWith("#/components/$defs/")) {
            String name = ref.substring("#/components/$defs/".length());
            if (openAPI.getComponents() != null && openAPI.getComponents().getSchemas() != null) {
                return openAPI.getComponents().getSchemas().get(name);
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

            // Check oneOf
            if (resolved.getOneOf() != null && !resolved.getOneOf().isEmpty()) {
                for (Object sub : resolved.getOneOf()) {
                    if (sub instanceof Schema) {
                        walkSchema((Schema) sub, currentPath, currentSpecPointer + "/oneOf", targetLeaf, depth + 1, inArray, visited, results);
                    }
                }
            }

            // Check anyOf
            if (resolved.getAnyOf() != null && !resolved.getAnyOf().isEmpty()) {
                for (Object sub : resolved.getAnyOf()) {
                    if (sub instanceof Schema) {
                        walkSchema((Schema) sub, currentPath, currentSpecPointer + "/anyOf", targetLeaf, depth + 1, inArray, visited, results);
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
            SchemaView sView = new SchemaView(resolved);
            if (sView.isArray()) {
                Schema items = sView.getItems();
                if (items != null) {
                    walkSchema(items, currentPath + "[*]", currentSpecPointer + "/items", targetLeaf, depth + 1, true, visited, results);
                }
            }
        } finally {
            visited.remove(resolved);
        }
    }

    public Resolution resolveExplicitPath(Schema rootSchema, ParamPath path) {
        return resolveExplicitPath(rootSchema, path, null);
    }

    public Resolution resolveExplicitPath(Schema rootSchema, ParamPath path, JsonNode currentPayload) {
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
                SchemaView currentView = new SchemaView(current);
                if (currentView.isArray()) {
                    current = resolveSchema(currentView.getItems());
                    specPointer.append("/items");
                } else {
                    return Resolution.undeclared(Collections.emptyList(), false);
                }
            } else {
                String propName = seg.getPropertyName();
                // Check if current has polymorphic branches (oneOf or anyOf)
                if (current.getOneOf() != null || current.getAnyOf() != null) {
                    Schema polyBranch = resolvePolymorphicProperty(current, propName, currentPayload);
                    if (polyBranch != null) {
                        Map<String, Schema> bProps = getAllProperties(polyBranch);
                        if (bProps.containsKey(propName)) {
                            current = resolveSchema(bProps.get(propName));
                            specPointer.append("/").append(polyBranch.getName() != null ? polyBranch.getName() : "branch").append("/properties/").append(propName);
                            continue;
                        }
                    }
                }

                Map<String, Schema> props = getAllProperties(current);
                if (props.containsKey(propName)) {
                    current = resolveSchema(props.get(propName));
                    specPointer.append("/properties/").append(propName);
                } else {
                    // Check did-you-mean suggestions from sibling properties
                    List<String> didYouMean = findDidYouMean(propName, props.keySet());
                    Object addProps = current.getAdditionalProperties();
                    boolean additionalProps = false;
                    Schema addPropsSchema = null;
                    if (addProps instanceof Boolean) {
                        additionalProps = (Boolean) addProps;
                    } else if (addProps instanceof Schema) {
                        additionalProps = true;
                        addPropsSchema = (Schema) addProps;
                    }
                    return Resolution.undeclared(didYouMean, additionalProps, addPropsSchema);
                }
            }
        }

        return Resolution.declaredInSpec(current, specPointer.toString());
    }

    public Schema resolvePolymorphicProperty(Schema schema, String propertyName, JsonNode currentPayload) {
        if (schema == null) return null;
        Schema resolved = resolveSchema(schema);
        if (resolved == null) return null;

        List subSchemas = null;
        if (resolved.getOneOf() != null && !resolved.getOneOf().isEmpty()) {
            subSchemas = resolved.getOneOf();
        } else if (resolved.getAnyOf() != null && !resolved.getAnyOf().isEmpty()) {
            subSchemas = resolved.getAnyOf();
        }

        if (subSchemas == null || subSchemas.isEmpty()) {
            return null;
        }

        // 1. Check discriminator
        if (resolved.getDiscriminator() != null) {
            String prop = resolved.getDiscriminator().getPropertyName();
            if (prop != null && currentPayload != null && currentPayload.has(prop)) {
                String discValue = currentPayload.get(prop).asText();
                Map<String, String> mapping = resolved.getDiscriminator().getMapping();
                String targetRef = mapping != null ? mapping.get(discValue) : null;
                if (targetRef != null) {
                    Schema branch = resolveRef(targetRef);
                    if (branch != null) return branch;
                }
                for (Object o : subSchemas) {
                    if (o instanceof Schema s) {
                        Schema resolvedS = resolveSchema(s);
                        if (discValue.equalsIgnoreCase(s.getName()) || (resolvedS != null && discValue.equalsIgnoreCase(resolvedS.getName()))) {
                            return resolvedS;
                        }
                    }
                }
            }
        }

        // 2. Check property membership across branches
        List<Schema> branchesWithProp = new ArrayList<>();
        for (Object o : subSchemas) {
            if (o instanceof Schema s) {
                Schema branch = resolveSchema(s);
                if (branch != null && getAllProperties(branch).containsKey(propertyName)) {
                    branchesWithProp.add(branch);
                }
            }
        }

        if (branchesWithProp.size() == 1) {
            return branchesWithProp.get(0);
        }
        if (branchesWithProp.size() > 1) {
            // Check if same type across all matching branches
            String firstType = new SchemaView(getAllProperties(branchesWithProp.get(0)).get(propertyName)).getTypes().toString();
            boolean sameType = true;
            for (Schema b : branchesWithProp) {
                String bType = new SchemaView(getAllProperties(b).get(propertyName)).getTypes().toString();
                if (!Objects.equals(firstType, bType)) {
                    sameType = false;
                    break;
                }
            }
            if (sameType) {
                return branchesWithProp.get(0);
            }
            List<String> branchNames = new ArrayList<>();
            for (Schema b : branchesWithProp) {
                branchNames.add(b.getName() != null ? b.getName() : b.getTitle() != null ? b.getTitle() : "branch");
            }
            throw new OverrideException(ReasonCode.AMBIGUOUS_POLYMORPHIC,
                    "Property '" + propertyName + "' exists in multiple polymorphic branches with differing types", branchNames);
        }

        return null;
    }

    public Schema navigateExplicitPath(Schema rootSchema, ParamPath path) {
        return navigateExplicitPath(rootSchema, path, null);
    }

    public Schema navigateExplicitPath(Schema rootSchema, ParamPath path, JsonNode currentPayload) {
        Resolution res = resolveExplicitPath(rootSchema, path, currentPayload);
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

        if (resolved.getOneOf() != null) {
            for (Object sub : resolved.getOneOf()) {
                if (sub instanceof Schema) {
                    all.putAll(getAllProperties((Schema) sub));
                }
            }
        }

        if (resolved.getAnyOf() != null) {
            for (Object sub : resolved.getAnyOf()) {
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
