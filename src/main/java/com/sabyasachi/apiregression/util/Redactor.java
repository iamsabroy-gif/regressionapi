package com.sabyasachi.apiregression.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Redactor {

    public static final String REDACTED_STRING = "***REDACTED***";

    private static final Set<String> DENY_HEADERS = Set.of(
            "authorization",
            "proxy-authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "api-key",
            "apikey",
            "x-auth-token",
            "x-access-token",
            "token"
    );

    private static final Set<String> DENY_QUERY_PARAMS = Set.of(
            "api_key",
            "apikey",
            "key",
            "token",
            "access_token",
            "secret",
            "password",
            "signature",
            "sig",
            "code"
    );

    private static final Set<String> SENSITIVE_FIELD_NAMES = Set.of(
            "access_token",
            "refresh_token",
            "id_token",
            "client_secret",
            "password",
            "secret",
            "token",
            "api_key",
            "apikey"
    );

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private Redactor() {
    }

    public static boolean isSensitiveHeader(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        if (DENY_HEADERS.contains(lower)) {
            return true;
        }
        return lower.contains("secret") || lower.contains("token") || lower.contains("password")
                || (lower.contains("key") && (lower.contains("api") || lower.contains("auth")));
    }

    public static boolean isSensitiveQueryParam(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.trim().toLowerCase(Locale.ROOT);
        if (DENY_QUERY_PARAMS.contains(lower)) {
            return true;
        }
        return lower.contains("secret") || lower.contains("token") || lower.contains("password")
                || lower.contains("signature") || (lower.contains("key") && lower.contains("api"));
    }

    public static boolean isSensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String lower = key.trim().toLowerCase(Locale.ROOT);
        // Strip trailing brackets or path indicators e.g. "auth.token" -> "token"
        int lastDot = lower.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < lower.length() - 1) {
            lower = lower.substring(lastDot + 1);
        }
        int lastSlash = lower.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash < lower.length() - 1) {
            lower = lower.substring(lastSlash + 1);
        }
        if (DENY_HEADERS.contains(lower) || DENY_QUERY_PARAMS.contains(lower) || SENSITIVE_FIELD_NAMES.contains(lower)) {
            return true;
        }
        return lower.contains("secret") || lower.contains("password") || lower.contains("token")
                || lower.contains("apikey") || lower.contains("api_key");
    }

    public static String redactUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }

        int qIdx = url.indexOf('?');
        if (qIdx < 0) {
            return url;
        }

        String base = url.substring(0, qIdx);
        String queryAndFragment = url.substring(qIdx + 1);

        String fragment = "";
        int hashIdx = queryAndFragment.indexOf('#');
        String query = queryAndFragment;
        if (hashIdx >= 0) {
            query = queryAndFragment.substring(0, hashIdx);
            fragment = queryAndFragment.substring(hashIdx);
        }

        String[] pairs = query.split("&", -1);
        StringBuilder sb = new StringBuilder(base).append('?');
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                sb.append('&');
            }
            String pair = pairs[i];
            int eqIdx = pair.indexOf('=');
            if (eqIdx >= 0) {
                String k = pair.substring(0, eqIdx);
                if (isSensitiveQueryParam(k)) {
                    sb.append(k).append('=').append(REDACTED_STRING);
                } else {
                    sb.append(pair);
                }
            } else {
                if (isSensitiveQueryParam(pair)) {
                    sb.append(pair).append('=').append(REDACTED_STRING);
                } else {
                    sb.append(pair);
                }
            }
        }
        sb.append(fragment);
        return sb.toString();
    }

    public static Map<String, List<String>> redactHeaders(Map<String, List<String>> headers) {
        if (headers == null) {
            return null;
        }
        Map<String, List<String>> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (isSensitiveHeader(key)) {
                redacted.put(key, List.of(REDACTED_STRING));
            } else {
                redacted.put(key, entry.getValue() != null ? new ArrayList<>(entry.getValue()) : Collections.emptyList());
            }
        }
        return redacted;
    }

    public static Map<String, String> redactHeadersMap(Map<String, String> headers) {
        if (headers == null) {
            return null;
        }
        Map<String, String> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            if (isSensitiveHeader(key)) {
                redacted.put(key, REDACTED_STRING);
            } else {
                redacted.put(key, entry.getValue());
            }
        }
        return redacted;
    }

    public static String redactBody(String body, Set<String> customJsonPaths) {
        if (body == null || body.isBlank()) {
            return body;
        }

        String trimmed = body.trim();
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(body);
                redactJsonNode(root, "", customJsonPaths != null ? customJsonPaths : Collections.emptySet());
                return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            } catch (Exception ignored) {
                // Not standard JSON or parse error, fallback to regex
            }
        }

        // Fallback: regex redact common sensitive JSON-like or form-like fields
        String result = body;
        for (String field : SENSITIVE_FIELD_NAMES) {
            Pattern pJson = Pattern.compile("(\"" + Pattern.quote(field) + "\"\\s*:\\s*\")[^\"]*(\")", Pattern.CASE_INSENSITIVE);
            result = pJson.matcher(result).replaceAll("$1" + REDACTED_STRING + "$2");

            Pattern pKv = Pattern.compile("(\\b" + Pattern.quote(field) + "\\s*[:=]\\s*)[^\\s,;}\"]+", Pattern.CASE_INSENSITIVE);
            result = pKv.matcher(result).replaceAll("$1" + REDACTED_STRING);
        }
        Pattern pBearer = Pattern.compile("(\\bBearer\\s+)[^\\s,;}\"]+", Pattern.CASE_INSENSITIVE);
        result = pBearer.matcher(result).replaceAll("$1" + REDACTED_STRING);
        return result;
    }

    private static void redactJsonNode(JsonNode node, String currentPath, Set<String> customPaths) {
        if (node == null) {
            return;
        }

        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            obj.fieldNames().forEachRemaining(fieldNames::add);

            for (String fieldName : fieldNames) {
                String path = currentPath.isEmpty() ? fieldName : currentPath + "." + fieldName;
                String jsonPath = "$." + path;

                boolean shouldRedact = isSensitiveKey(fieldName)
                        || customPaths.contains(fieldName)
                        || customPaths.contains(path)
                        || customPaths.contains(jsonPath);

                if (shouldRedact) {
                    obj.set(fieldName, TextNode.valueOf(REDACTED_STRING));
                } else {
                    redactJsonNode(obj.get(fieldName), path, customPaths);
                }
            }
        } else if (node.isArray()) {
            ArrayNode arr = (ArrayNode) node;
            for (int i = 0; i < arr.size(); i++) {
                redactJsonNode(arr.get(i), currentPath + "[" + i + "]", customPaths);
            }
        }
    }

    public static void applyFilePermissions0600(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(path, perms);
        } catch (UnsupportedOperationException | SecurityException e) {
            // Non-POSIX (e.g. Windows) - best effort owner permissions
            File f = path.toFile();
            f.setReadable(false, false);
            f.setReadable(true, true);
            f.setWritable(false, false);
            f.setWritable(true, true);
            f.setExecutable(false, false);
        } catch (IOException ignored) {
        }
    }

    public static void writeAtomicWith0600(Path targetPath, byte[] content) throws IOException {
        Path dir = targetPath.getParent() != null ? targetPath.getParent() : Path.of(".");
        Files.createDirectories(dir);
        Path tempFile = Files.createTempFile(dir, targetPath.getFileName().toString(), ".tmp");
        try {
            Files.write(tempFile, content);
            applyFilePermissions0600(tempFile);
            Files.move(tempFile, targetPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            applyFilePermissions0600(targetPath);
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
}
