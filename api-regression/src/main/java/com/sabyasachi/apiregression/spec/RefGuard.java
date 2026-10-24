package com.sabyasachi.apiregression.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class RefGuard {

    public static final long MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB
    public static final int MAX_FILE_COUNT = 200;
    public static final int MAX_DEPTH = 20;

    public static class GuardResult {
        private final boolean passed;
        private final List<String> messages;
        private final Set<Path> scannedFiles;
        private final Set<Path> missingFiles;

        public GuardResult(boolean passed, List<String> messages) {
            this(passed, messages, Collections.emptySet(), Collections.emptySet());
        }

        public GuardResult(boolean passed, List<String> messages, Set<Path> scannedFiles, Set<Path> missingFiles) {
            this.passed = passed;
            this.messages = messages != null ? messages : Collections.emptyList();
            this.scannedFiles = scannedFiles != null ? scannedFiles : Collections.emptySet();
            this.missingFiles = missingFiles != null ? missingFiles : Collections.emptySet();
        }

        public boolean isPassed() {
            return passed;
        }

        public List<String> getMessages() {
            return messages;
        }

        public Set<Path> getScannedFiles() {
            return scannedFiles;
        }

        public Set<Path> getMissingFiles() {
            return missingFiles;
        }
    }

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final YAMLMapper YAML_MAPPER = new YAMLMapper();

    public static GuardResult scan(Path rootSpecPath) {
        List<String> messages = new ArrayList<>();
        if (rootSpecPath == null || !Files.exists(rootSpecPath)) {
            messages.add("SPEC_NOT_FOUND: Spec file does not exist: " + rootSpecPath);
            return new GuardResult(false, messages, Collections.emptySet(), rootSpecPath != null ? Set.of(rootSpecPath) : Collections.emptySet());
        }

        Path realRoot;
        Path realSpecDir;
        try {
            realRoot = rootSpecPath.toRealPath();
            realSpecDir = realRoot.getParent();
            if (realSpecDir == null) {
                realSpecDir = realRoot;
            }
        } catch (IOException e) {
            messages.add("SPEC_IO_ERROR: Failed to resolve real path: " + e.getMessage());
            return new GuardResult(false, messages, Collections.emptySet(), Collections.emptySet());
        }

        Set<Path> visitedFiles = new LinkedHashSet<>();
        Set<Path> missingFiles = new LinkedHashSet<>();
        boolean passed = scanFile(realRoot, realSpecDir, 0, visitedFiles, missingFiles, messages);
        return new GuardResult(passed, messages, Collections.unmodifiableSet(visitedFiles), Collections.unmodifiableSet(missingFiles));
    }

    private static boolean scanFile(Path currentFile, Path realSpecDir, int depth, Set<Path> visitedFiles, Set<Path> missingFiles, List<String> messages) {
        if (!visitedFiles.add(currentFile)) {
            return true; // Already scanned in this cycle
        }

        if (visitedFiles.size() > MAX_FILE_COUNT) {
            messages.add("REF_FILE_CAP_EXCEEDED: Exceeded max spec file count of " + MAX_FILE_COUNT);
            return false;
        }

        if (depth > MAX_DEPTH) {
            messages.add("REF_DEPTH_EXCEEDED: Exceeded max reference depth of " + MAX_DEPTH);
            return false;
        }

        try {
            long size = Files.size(currentFile);
            if (size > MAX_FILE_SIZE_BYTES) {
                messages.add("SPEC_SIZE_EXCEEDED: Spec file exceeds 10 MB limit (" + size + " bytes): " + currentFile);
                return false;
            }

            byte[] bytes = Files.readAllBytes(currentFile);
            String fileName = currentFile.getFileName().toString().toLowerCase();
            JsonNode root;
            if (fileName.endsWith(".json")) {
                root = JSON_MAPPER.readTree(bytes);
            } else {
                root = YAML_MAPPER.readTree(bytes);
            }

            if (root == null) {
                return true;
            }

            List<String> refs = new ArrayList<>();
            collectRefs(root, refs);

            boolean allPassed = true;
            for (String ref : refs) {
                if (!checkRef(ref, currentFile.getParent(), realSpecDir, depth, visitedFiles, missingFiles, messages)) {
                    allPassed = false;
                }
            }
            return allPassed;

        } catch (Exception e) {
            messages.add("SPEC_PARSE_ERROR: Failed to parse spec file " + currentFile + ": " + e.getMessage());
            return false;
        }
    }

    private static void collectRefs(JsonNode node, List<String> refs) {
        if (node == null) return;

        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if ("$ref".equals(entry.getKey()) && entry.getValue().isTextual()) {
                    refs.add(entry.getValue().asText());
                } else {
                    collectRefs(entry.getValue(), refs);
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                collectRefs(item, refs);
            }
        }
    }

    private static boolean checkRef(String ref, Path currentDir, Path realSpecDir, int depth, Set<Path> visitedFiles, Set<Path> missingFiles, List<String> messages) {
        if (ref == null || ref.isBlank()) {
            return true;
        }

        String trimmed = ref.trim();

        // 1. Internal fragment reference
        if (trimmed.startsWith("#")) {
            return true;
        }

        // 2. URI Schemes check: block remote URLs and URI schemes
        int colonIdx = trimmed.indexOf(':');
        int slashIdx = trimmed.indexOf('/');
        if (colonIdx != -1 && (slashIdx == -1 || colonIdx < slashIdx)) {
            // Contains scheme e.g. "http:", "https:", "ftp:", "jar:", "file:", "c:"
            messages.add("REMOTE_REF_BLOCKED: Remote or absolute URI reference blocked: " + trimmed);
            return false;
        }

        // 3. Relative file reference
        String filePathPart = trimmed;
        int hashIdx = trimmed.indexOf('#');
        if (hashIdx != -1) {
            filePathPart = trimmed.substring(0, hashIdx);
        }

        if (filePathPart.isEmpty()) {
            return true; // fragment only
        }

        Path target = currentDir.resolve(filePathPart).normalize();
        if (!Files.exists(target)) {
            messages.add("REF_UNRESOLVED: Unresolved relative reference: " + trimmed);
            missingFiles.add(target.toAbsolutePath().normalize());
            return false;
        }

        Path realTarget;
        try {
            realTarget = target.toRealPath();
        } catch (IOException e) {
            messages.add("REF_UNRESOLVED: Cannot resolve real path for " + trimmed + ": " + e.getMessage());
            missingFiles.add(target.toAbsolutePath().normalize());
            return false;
        }

        // Verify directory containment (prevents directory traversal & symlink escapes)
        if (!realTarget.startsWith(realSpecDir)) {
            messages.add("REF_OUTSIDE_DIR: Reference escapes spec directory: " + trimmed);
            return false;
        }

        // Extension allowlist check (.json, .yaml, .yml)
        String lowerTarget = realTarget.getFileName().toString().toLowerCase();
        if (!lowerTarget.endsWith(".json") && !lowerTarget.endsWith(".yaml") && !lowerTarget.endsWith(".yml")) {
            messages.add("REF_INVALID_EXTENSION: Disallowed reference extension: " + trimmed);
            return false;
        }

        // Recurse into relative file
        return scanFile(realTarget, realSpecDir, depth + 1, visitedFiles, missingFiles, messages);
    }
}
