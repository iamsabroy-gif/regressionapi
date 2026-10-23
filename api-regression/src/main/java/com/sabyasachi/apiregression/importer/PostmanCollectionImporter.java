package com.sabyasachi.apiregression.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sabyasachi.apiregression.client.Substitutor;
import com.sabyasachi.apiregression.model.Auth;
import com.sabyasachi.apiregression.model.TestCase;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class PostmanCollectionImporter {

    public static class ImportResult {
        private String collectionName = "Imported Collection";
        private final List<TestCase> testCases = new ArrayList<>();
        private final Set<String> unresolvedVariables = new LinkedHashSet<>();
        private final List<String> warnings = new ArrayList<>();

        public String getCollectionName() {
            return collectionName;
        }

        public void setCollectionName(String collectionName) {
            this.collectionName = collectionName;
        }

        public List<TestCase> getTestCases() {
            return testCases;
        }

        public Set<String> getUnresolvedVariables() {
            return unresolvedVariables;
        }

        public List<String> getWarnings() {
            return warnings;
        }
    }

    private final ObjectMapper objectMapper;

    public PostmanCollectionImporter() {
        this.objectMapper = new ObjectMapper();
    }

    public PostmanCollectionImporter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ImportResult parse(Path collectionPath) throws IOException {
        return parse(collectionPath, Collections.emptyMap());
    }

    public ImportResult parse(Path collectionPath, Map<String, String> companionEnv) throws IOException {
        if (!Files.exists(collectionPath)) {
            throw new IOException("Collection file not found: " + collectionPath);
        }
        JsonNode root = objectMapper.readTree(collectionPath.toFile());
        return parse(root, companionEnv);
    }

    public ImportResult parse(String jsonContent, Map<String, String> companionEnv) throws IOException {
        JsonNode root = objectMapper.readTree(jsonContent);
        return parse(root, companionEnv);
    }

    public ImportResult parse(JsonNode root, Map<String, String> companionEnv) {
        ImportResult result = new ImportResult();
        if (root == null) {
            return result;
        }

        Map<String, String> env = companionEnv != null ? companionEnv : Collections.emptyMap();

        // 1. Collection Name
        if (root.has("info") && root.get("info").has("name")) {
            result.setCollectionName(root.get("info").get("name").asText("Imported Collection"));
        } else if (root.has("name")) {
            result.setCollectionName(root.get("name").asText("Imported Collection"));
        }

        // 2. Collection-level Auth (if any)
        Auth collectionAuth = parseAuthNode(root.get("auth"));

        // 3. Walk items
        JsonNode itemNode = root.get("item");
        if (itemNode != null && itemNode.isArray()) {
            walkItems(itemNode, "", collectionAuth, env, result);
        }

        return result;
    }

    private void walkItems(JsonNode items, String currentCategory, Auth inheritedAuth,
                           Map<String, String> env, ImportResult result) {
        for (JsonNode item : items) {
            String itemName = item.has("name") ? item.get("name").asText("Untitled") : "Untitled";

            // If item has another "item" array, it's a folder
            if (item.has("item") && item.get("item").isArray()) {
                String subCategory = currentCategory.isEmpty() ? itemName : currentCategory + " / " + itemName;
                Auth folderAuth = item.has("auth") ? parseAuthNode(item.get("auth")) : inheritedAuth;
                walkItems(item.get("item"), subCategory, folderAuth, env, result);
            } else if (item.has("request")) {
                // It's a request
                TestCase tc = parseRequestItem(item, currentCategory, inheritedAuth, env, result);
                if (tc != null) {
                    result.getTestCases().add(tc);
                }
            }
        }
    }

    private TestCase parseRequestItem(JsonNode item, String category, Auth inheritedAuth,
                                      Map<String, String> env, ImportResult result) {
        String name = item.has("name") ? item.get("name").asText("Untitled Request") : "Untitled Request";
        JsonNode reqNode = item.get("request");
        if (reqNode == null) return null;

        TestCase tc = new TestCase();
        tc.setName(name);
        tc.setCategory(category);

        // 1. Method
        if (reqNode.has("method")) {
            tc.setMethod(reqNode.get("method").asText("GET").toUpperCase());
        }

        // 2. URL
        String rawUrl = "";
        JsonNode urlNode = reqNode.get("url");
        if (urlNode != null) {
            if (urlNode.isTextual()) {
                rawUrl = urlNode.asText();
            } else if (urlNode.isObject() && urlNode.has("raw")) {
                rawUrl = urlNode.get("raw").asText();
            }
        }
        tc.setUrl(rawUrl);
        collectPlaceholders(rawUrl, env, result.getUnresolvedVariables());

        // 3. Headers
        JsonNode headerNode = reqNode.get("header");
        Map<String, String> headers = new LinkedHashMap<>();
        if (headerNode != null && headerNode.isArray()) {
            for (JsonNode h : headerNode) {
                boolean disabled = h.has("disabled") && h.get("disabled").asBoolean();
                if (!disabled && h.has("key")) {
                    String hKey = h.get("key").asText("");
                    String hVal = h.has("value") ? h.get("value").asText("") : "";
                    if (!hKey.isBlank()) {
                        headers.put(hKey, hVal);
                        collectPlaceholders(hKey, env, result.getUnresolvedVariables());
                        collectPlaceholders(hVal, env, result.getUnresolvedVariables());
                    }
                }
            }
        }
        tc.setHeaders(headers);

        // 4. Auth
        Auth requestAuth = parseAuthNode(reqNode.get("auth"));
        if (requestAuth != null && requestAuth.getType() != Auth.AuthType.NONE) {
            tc.setAuth(requestAuth);
        } else if (inheritedAuth != null && inheritedAuth.getType() != Auth.AuthType.NONE) {
            tc.setAuth(inheritedAuth);
        }

        // 5. Body
        JsonNode bodyNode = reqNode.get("body");
        if (bodyNode != null && bodyNode.isObject()) {
            String mode = bodyNode.has("mode") ? bodyNode.get("mode").asText("") : "";
            if ("raw".equalsIgnoreCase(mode) && bodyNode.has("raw")) {
                String rawBody = bodyNode.get("raw").asText();
                tc.setBodyTemplate(rawBody);
                collectPlaceholders(rawBody, env, result.getUnresolvedVariables());
            }
        }

        // 6. Expected response (seeded from item.response[] if available)
        JsonNode respArray = item.get("response");
        if (respArray != null && respArray.isArray() && !respArray.isEmpty()) {
            JsonNode firstResp = respArray.get(0);
            if (firstResp.has("code")) {
                tc.setExpectedStatus(firstResp.get("code").asInt(200));
            }
            if (firstResp.has("body")) {
                tc.setExpectedBodyTemplate(firstResp.get("body").asText(""));
            }
        }

        return tc;
    }

    private Auth parseAuthNode(JsonNode authNode) {
        if (authNode == null || !authNode.isObject()) {
            return null;
        }

        String typeStr = authNode.has("type") ? authNode.get("type").asText("none").toLowerCase() : "none";
        Auth auth = new Auth();

        switch (typeStr) {
            case "bearer":
                auth.setType(Auth.AuthType.BEARER);
                JsonNode bearerArr = authNode.get("bearer");
                if (bearerArr != null && bearerArr.isArray()) {
                    for (JsonNode entry : bearerArr) {
                        if ("token".equalsIgnoreCase(entry.path("key").asText())) {
                            auth.setBearerToken(entry.path("value").asText(""));
                        }
                    }
                }
                break;
            case "basic":
                auth.setType(Auth.AuthType.BASIC);
                JsonNode basicArr = authNode.get("basic");
                if (basicArr != null && basicArr.isArray()) {
                    for (JsonNode entry : basicArr) {
                        String k = entry.path("key").asText();
                        if ("username".equalsIgnoreCase(k)) {
                            auth.setUsername(entry.path("value").asText(""));
                        } else if ("password".equalsIgnoreCase(k)) {
                            auth.setPassword(entry.path("value").asText(""));
                        }
                    }
                }
                break;
            case "apikey":
                auth.setType(Auth.AuthType.API_KEY);
                JsonNode apiArr = authNode.get("apikey");
                if (apiArr != null && apiArr.isArray()) {
                    for (JsonNode entry : apiArr) {
                        String k = entry.path("key").asText();
                        if ("key".equalsIgnoreCase(k)) {
                            auth.setApiKeyKey(entry.path("value").asText(""));
                        } else if ("value".equalsIgnoreCase(k)) {
                            auth.setApiKeyValue(entry.path("value").asText(""));
                        } else if ("in".equalsIgnoreCase(k)) {
                            auth.setApiKeyAddTo(entry.path("value").asText("HEADER").toUpperCase());
                        }
                    }
                }
                break;
            default:
                auth.setType(Auth.AuthType.NONE);
                break;
        }

        return auth;
    }

    private void collectPlaceholders(String text, Map<String, String> env, Set<String> unresolved) {
        if (text == null || text.isEmpty()) return;
        Set<String> found = Substitutor.extractPlaceholders(text);
        for (String varName : found) {
            if (!env.containsKey(varName)) {
                unresolved.add(varName);
            }
        }
    }
}
