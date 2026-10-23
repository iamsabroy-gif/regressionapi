package com.sabyasachi.apiregression.spec;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ProviderSpecLoader {

    public static class LoadedSpec {
        private final OpenAPI openAPI;
        private final String specPath;
        private final String sha256;
        private final String specVersion;
        private final List<String> messages;

        public LoadedSpec(OpenAPI openAPI, String specPath, String sha256, String specVersion, List<String> messages) {
            this.openAPI = openAPI;
            this.specPath = specPath;
            this.sha256 = sha256;
            this.specVersion = specVersion;
            this.messages = messages != null ? messages : Collections.emptyList();
        }

        public OpenAPI getOpenAPI() {
            return openAPI;
        }

        public String getSpecPath() {
            return specPath;
        }

        public String getSha256() {
            return sha256;
        }

        public String getSpecVersion() {
            return specVersion;
        }

        public List<String> getMessages() {
            return messages;
        }
    }

    private static class CacheEntry {
        final long lastModified;
        final LoadedSpec loadedSpec;

        CacheEntry(long lastModified, LoadedSpec loadedSpec) {
            this.lastModified = lastModified;
            this.loadedSpec = loadedSpec;
        }
    }

    private static final Map<String, CacheEntry> SPEC_CACHE = new ConcurrentHashMap<>();

    public static OpenAPI loadOpenAPI(Path path) throws IOException {
        LoadedSpec loaded = load(path);
        if (loaded.getOpenAPI() == null) {
            String err = loaded.getMessages().isEmpty() ? "Blocked by spec security rules" : String.join("; ", loaded.getMessages());
            throw new IOException("Failed to load OpenAPI spec: " + err);
        }
        return loaded.getOpenAPI();
    }

    public static LoadedSpec load(Path path) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("Spec path cannot be null");
        }
        if (!Files.exists(path)) {
            throw new IOException("Spec file not found: " + path);
        }

        long mtime = Files.getLastModifiedTime(path).toMillis();
        String absKey = path.toAbsolutePath().normalize().toString();

        CacheEntry cached = SPEC_CACHE.get(absKey);
        if (cached != null && cached.lastModified == mtime) {
            return cached.loadedSpec;
        }

        byte[] bytes = Files.readAllBytes(path);
        String sha256 = computeSha256(bytes);

        // Pre-scan with RefGuard to enforce $ref allowlist (D4 / S1)
        RefGuard.GuardResult guardResult = RefGuard.scan(path);
        if (!guardResult.isPassed()) {
            LoadedSpec loaded = new LoadedSpec(null, absKey, sha256, null, guardResult.getMessages());
            SPEC_CACHE.put(absKey, new CacheEntry(mtime, loaded));
            return loaded;
        }

        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        options.setResolveCombinators(false);

        OpenAPIParser parser = new OpenAPIParser();
        SwaggerParseResult parseResult = parser.readLocation(absKey, null, options);

        OpenAPI openAPI = parseResult.getOpenAPI();
        List<String> messages = parseResult.getMessages() != null ? new ArrayList<>(parseResult.getMessages()) : new ArrayList<>();
        messages.addAll(guardResult.getMessages());

        if (openAPI == null) {
            String err = messages.isEmpty() ? "Unknown parse error" : String.join(", ", messages);
            throw new IOException("Failed to parse OpenAPI/Swagger spec at " + path + ": " + err);
        }

        String version = openAPI.getOpenapi();
        if (version == null) {
            version = "2.0 (Swagger)";
        }

        LoadedSpec loaded = new LoadedSpec(openAPI, absKey, sha256, version, messages);
        SPEC_CACHE.put(absKey, new CacheEntry(mtime, loaded));
        return loaded;
    }

    public static String computeSha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
