package com.sabyasachi.apiregression.payload;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RequestPartOverrider {

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("(\\{\\{.*?\\}\\})");

    public static class OverridePartResult {
        private final String updatedUrl;
        private final Map<String, String> updatedHeaders;
        private final String oldValue;
        private final boolean added;

        public OverridePartResult(String updatedUrl, Map<String, String> updatedHeaders, String oldValue, boolean added) {
            this.updatedUrl = updatedUrl;
            this.updatedHeaders = updatedHeaders;
            this.oldValue = oldValue;
            this.added = added;
        }

        public String getUpdatedUrl() {
            return updatedUrl;
        }

        public Map<String, String> getUpdatedHeaders() {
            return updatedHeaders;
        }

        public String getOldValue() {
            return oldValue;
        }

        public boolean isAdded() {
            return added;
        }
    }

    public static OverridePartResult overrideQueryParam(String url, String paramName, String rawValue) {
        if (url == null) return new OverridePartResult(null, null, null, false);

        if ("<null>".equalsIgnoreCase(rawValue != null ? rawValue.trim() : "")) {
            throw new OverrideException(ReasonCode.NULL_NOT_ALLOWED_HERE,
                    "Null value is not allowed for query parameter '" + paramName + "'.");
        }

        String encodedValue = encodeQueryValue(rawValue);

        int qIdx = url.indexOf('?');
        String base = (qIdx != -1) ? url.substring(0, qIdx) : url;
        String queryStr = (qIdx != -1) ? url.substring(qIdx + 1) : "";

        List<String> pairs = new ArrayList<>();
        String oldValue = null;
        boolean found = false;

        if (!queryStr.isEmpty()) {
            String[] rawPairs = queryStr.split("&");
            for (String pair : rawPairs) {
                int eq = pair.indexOf('=');
                String k = (eq != -1) ? pair.substring(0, eq) : pair;
                String v = (eq != -1) ? pair.substring(eq + 1) : "";

                if (k.equalsIgnoreCase(paramName)) {
                    if (found) {
                        throw new OverrideException(ReasonCode.QUERY_REPEATED,
                                "Repeated query parameter '" + paramName + "' found in URL; ambiguous replacement.");
                    }
                    found = true;
                    oldValue = v;
                    pairs.add(k + "=" + encodedValue);
                } else {
                    pairs.add(pair);
                }
            }
        }

        boolean added = false;
        if (!found) {
            added = true;
            pairs.add(paramName + "=" + encodedValue);
        }

        String newQuery = String.join("&", pairs);
        String newUrl = base + (newQuery.isEmpty() ? "" : "?" + newQuery);
        return new OverridePartResult(newUrl, null, oldValue, added);
    }

    public static OverridePartResult overridePathParam(String url, String pathTemplate, String paramName, String rawValue) {
        if (url == null || pathTemplate == null) {
            throw new OverrideException(ReasonCode.INTERNAL_ERROR,
                    "URL and path template are required for path parameter substitution.");
        }

        if ("<null>".equalsIgnoreCase(rawValue != null ? rawValue.trim() : "")) {
            throw new OverrideException(ReasonCode.NULL_NOT_ALLOWED_HERE,
                    "Null value is not allowed for path parameter '" + paramName + "'.");
        }

        String encodedValue = encodePathSegment(rawValue);

        // Strip query string if any
        int qIdx = url.indexOf('?');
        String urlPath = (qIdx != -1) ? url.substring(0, qIdx) : url;
        String query = (qIdx != -1) ? url.substring(qIdx) : "";

        String[] templateSegments = pathTemplate.split("/");
        String[] urlSegments = urlPath.split("/");

        if (templateSegments.length != urlSegments.length) {
            int offset = urlSegments.length - templateSegments.length;
            if (offset < 0) {
                throw new OverrideException(ReasonCode.PATH_PARAM_NOT_FOUND,
                        "URL path segments do not match spec path template: " + pathTemplate);
            }
        }

        String targetPattern1 = "{" + paramName + "}";
        String targetPattern2 = ":" + paramName;
        int targetIdx = -1;

        for (int i = 0; i < templateSegments.length; i++) {
            String seg = templateSegments[i].trim();
            if (seg.equalsIgnoreCase(targetPattern1) || seg.equalsIgnoreCase(targetPattern2)) {
                targetIdx = i;
                break;
            }
        }

        if (targetIdx == -1) {
            throw new OverrideException(ReasonCode.PATH_PARAM_NOT_FOUND,
                    "Path parameter '{" + paramName + "}' not found in template: " + pathTemplate);
        }

        // Align with urlSegments from right if length difference exists
        int segInUrl = urlSegments.length - (templateSegments.length - targetIdx);
        if (segInUrl < 0 || segInUrl >= urlSegments.length) {
            throw new OverrideException(ReasonCode.PATH_PARAM_NOT_FOUND,
                    "Unable to map path parameter '" + paramName + "' to segment in URL: " + url);
        }

        String oldValue = urlSegments[segInUrl];
        urlSegments[segInUrl] = encodedValue;
        String newUrl = String.join("/", urlSegments) + query;

        return new OverridePartResult(newUrl, null, oldValue, false);
    }

    public static OverridePartResult overrideHeader(Map<String, String> headers, String headerName, String rawValue) {
        if ("<null>".equalsIgnoreCase(rawValue != null ? rawValue.trim() : "")) {
            throw new OverrideException(ReasonCode.NULL_NOT_ALLOWED_HERE,
                    "Null value is not allowed for header '" + headerName + "'.");
        }

        String effectiveValue = "<empty>".equalsIgnoreCase(rawValue != null ? rawValue.trim() : "") ? "" : (rawValue != null ? rawValue : "");

        if (effectiveValue.contains("\r") || effectiveValue.contains("\n")) {
            throw new OverrideException(ReasonCode.HEADER_INVALID_VALUE,
                    "Header value for '" + headerName + "' cannot contain CR or LF control characters.");
        }

        Map<String, String> updated = new LinkedHashMap<>();
        String oldValue = null;
        boolean found = false;

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(headerName)) {
                    found = true;
                    oldValue = entry.getValue();
                    updated.put(entry.getKey(), effectiveValue);
                } else {
                    updated.put(entry.getKey(), entry.getValue());
                }
            }
        }

        boolean added = false;
        if (!found) {
            added = true;
            updated.put(headerName, effectiveValue);
        }

        return new OverridePartResult(null, updated, oldValue, added);
    }

    public static String encodeQueryValue(String val) {
        if (val == null) return "";
        if ("<empty>".equalsIgnoreCase(val.trim())) return "";
        return encodePreservingPlaceholders(val);
    }

    public static String encodePathSegment(String val) {
        if (val == null) return "";
        if ("<empty>".equalsIgnoreCase(val.trim())) return "";
        return encodePreservingPlaceholders(val);
    }

    private static String encodePreservingPlaceholders(String val) {
        if (!val.contains("{{") || !val.contains("}}")) {
            return URLEncoder.encode(val, StandardCharsets.UTF_8).replace("+", "%20");
        }

        Matcher matcher = PLACEHOLDER_PATTERN.matcher(val);
        StringBuilder sb = new StringBuilder();
        int lastIdx = 0;
        while (matcher.find()) {
            if (matcher.start() > lastIdx) {
                String literal = val.substring(lastIdx, matcher.start());
                sb.append(URLEncoder.encode(literal, StandardCharsets.UTF_8).replace("+", "%20"));
            }
            sb.append(matcher.group(1)); // placeholder untouched
            lastIdx = matcher.end();
        }
        if (lastIdx < val.length()) {
            String literal = val.substring(lastIdx);
            sb.append(URLEncoder.encode(literal, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return sb.toString();
    }
}
