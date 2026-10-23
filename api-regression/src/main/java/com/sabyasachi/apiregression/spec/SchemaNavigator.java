package com.sabyasachi.apiregression.spec;

import com.sabyasachi.apiregression.payload.ParamPath;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.media.Schema;

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

    public Schema navigateExplicitPath(Schema rootSchema, ParamPath path) {
        if (rootSchema == null || path == null) return null;
        Schema current = resolveSchema(rootSchema);

        for (ParamPath.Segment seg : path.getSegments()) {
            if (current == null) return null;

            if (seg.isArray()) {
                if (current instanceof ArraySchema) {
                    current = resolveSchema(((ArraySchema) current).getItems());
                } else {
                    return null;
                }
            } else {
                Map<String, Schema> props = getAllProperties(current);
                if (props != null && props.containsKey(seg.getPropertyName())) {
                    current = resolveSchema(props.get(seg.getPropertyName()));
                } else {
                    return null;
                }
            }
        }

        return current;
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
