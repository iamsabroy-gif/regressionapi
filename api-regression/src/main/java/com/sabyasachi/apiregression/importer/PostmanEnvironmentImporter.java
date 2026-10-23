package com.sabyasachi.apiregression.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class PostmanEnvironmentImporter {

    private final ObjectMapper objectMapper;

    public PostmanEnvironmentImporter() {
        this.objectMapper = new ObjectMapper();
    }

    public PostmanEnvironmentImporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Map<String, String> parse(Path environmentJsonPath) throws IOException {
        if (!Files.exists(environmentJsonPath)) {
            throw new IOException("Environment file not found: " + environmentJsonPath);
        }
        JsonNode root = objectMapper.readTree(environmentJsonPath.toFile());
        return parse(root);
    }

    public Map<String, String> parse(String jsonContent) throws IOException {
        JsonNode root = objectMapper.readTree(jsonContent);
        return parse(root);
    }

    public Map<String, String> parse(JsonNode root) {
        Map<String, String> envVars = new LinkedHashMap<>();
        if (root == null) {
            return envVars;
        }

        // Postman environment format has a "values" array:
        // [ { "key": "baseUrl", "value": "https://api.example.com", "enabled": true }, ... ]
        JsonNode valuesNode = root.get("values");
        if (valuesNode != null && valuesNode.isArray()) {
            for (JsonNode item : valuesNode) {
                boolean enabled = !item.has("enabled") || item.get("enabled").asBoolean(true);
                if (enabled && item.has("key")) {
                    String key = item.get("key").asText("");
                    String value = item.has("value") ? item.get("value").asText("") : "";
                    if (!key.isBlank()) {
                        envVars.put(key.trim(), value);
                    }
                }
            }
        } else if (root.isObject()) {
            // Flat key-value format fallback
            root.fields().forEachRemaining(entry -> {
                if (entry.getValue().isValueNode()) {
                    envVars.put(entry.getKey(), entry.getValue().asText());
                }
            });
        }

        return envVars;
    }
}
