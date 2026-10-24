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
        final Map<Path, Long> fileModifiedTimes;
        final Set<Path> missingFiles;
        final LoadedSpec loadedSpec;

        CacheEntry(Map<Path, Long> fileModifiedTimes, Set<Path> missingFiles, LoadedSpec loadedSpec) {
            this.fileModifiedTimes = fileModifiedTimes != null ? fileModifiedTimes : Collections.emptyMap();
            this.missingFiles = missingFiles != null ? missingFiles : Collections.emptySet();
            this.loadedSpec = loadedSpec;
        }

        boolean isStale() {
            for (Path missing : missingFiles) {
                if (Files.exists(missing)) {
                    return true;
                }
            }
            for (Map.Entry<Path, Long> entry : fileModifiedTimes.entrySet()) {
                Path file = entry.getKey();
                if (!Files.exists(file)) {
                    return true;
                }
                try {
                    long currentMtime = Files.getLastModifiedTime(file).toMillis();
                    if (currentMtime != entry.getValue()) {
                        return true;
                    }
                } catch (IOException e) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final Map<String, CacheEntry> SPEC_CACHE = new ConcurrentHashMap<>();

    public static void clearCache() {
        SPEC_CACHE.clear();
    }

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

        String absKey = path.toAbsolutePath().normalize().toString();

        CacheEntry cached = SPEC_CACHE.get(absKey);
        if (cached != null && !cached.isStale()) {
            return cached.loadedSpec;
        }

        byte[] bytes = Files.readAllBytes(path);
        String sha256 = computeSha256(bytes);

        // Pre-scan with RefGuard to enforce $ref allowlist (D4 / S1)
        RefGuard.GuardResult guardResult = RefGuard.scan(path);

        // Record modified times of root file and all files scanned by RefGuard
        Map<Path, Long> fileModifiedTimes = new LinkedHashMap<>();
        try {
            fileModifiedTimes.put(path.toRealPath(), Files.getLastModifiedTime(path).toMillis());
        } catch (IOException e) {
            fileModifiedTimes.put(path.toAbsolutePath().normalize(), Files.getLastModifiedTime(path).toMillis());
        }
        for (Path f : guardResult.getScannedFiles()) {
            try {
                if (Files.exists(f)) {
                    fileModifiedTimes.put(f, Files.getLastModifiedTime(f).toMillis());
                }
            } catch (IOException ignored) {}
        }

        if (!guardResult.isPassed()) {
            LoadedSpec loaded = new LoadedSpec(null, absKey, sha256, null, guardResult.getMessages());
            SPEC_CACHE.put(absKey, new CacheEntry(fileModifiedTimes, guardResult.getMissingFiles(), loaded));
            return loaded;
        }

        /*
         * Architecture Note & Design Decision:
         * We employ a two-pass approach:
         * 1. RefGuard.scan() performs an AST pre-scan over raw file bytes to enforce security boundaries
         *    (rejecting remote URL schemes, cloud-metadata IP addresses, path traversal escapes outside the
         *    spec root directory, and circular reference depth > 20).
         * 2. OpenAPIParser.readLocation() parses and resolves the full OpenAPI/Swagger object graph.
         *    readLocation is required so that the third-party parser maintains proper base-URI resolution
         *    for multi-file specs with nested relative references.
         *
         * TOCTOU Consideration:
         * A theoretical Time-Of-Check to Time-Of-Use race could occur if a local file were swapped between
         * the RefGuard scan and parser.readLocation. In a desktop application where the user explicitly selects
         * local workspace specifications, this is considered low risk and accepted by design.
         * As defense-in-depth, we verify that none of the scanned files have shifted their modified timestamps
         * between RefGuard's scan and the completion of parser.readLocation.
         */
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        options.setResolveCombinators(false);

        OpenAPIParser parser = new OpenAPIParser();
        SwaggerParseResult parseResult = parser.readLocation(absKey, null, options);

        // Defense-in-depth: Verify no scanned file was concurrently modified during parse
        for (Map.Entry<Path, Long> entry : fileModifiedTimes.entrySet()) {
            try {
                if (!Files.exists(entry.getKey()) || Files.getLastModifiedTime(entry.getKey()).toMillis() != entry.getValue()) {
                    throw new IOException("Spec file modified concurrently during load: " + entry.getKey());
                }
            } catch (IOException e) {
                throw new IOException("Spec file modified concurrently during load: " + entry.getKey(), e);
            }
        }

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
        SPEC_CACHE.put(absKey, new CacheEntry(fileModifiedTimes, guardResult.getMissingFiles(), loaded));
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
