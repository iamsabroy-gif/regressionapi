package com.sabyasachi.apiregression.payload;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class RequestPartOverrider {

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
                        throw new IllegalArgumentException("Repeated query parameter '" + paramName + "' found in URL; ambiguous replacement.");
                    }
                    found = true;
                    oldValue = v;
                    pairs.add(k + "=" + encodeQueryVal(rawValue));
                } else {
                    pairs.add(pair);
                }
            }
        }

        boolean added = false;
        if (!found) {
            added = true;
            pairs.add(paramName + "=" + encodeQueryVal(rawValue));
        }

        String newQuery = String.join("&", pairs);
        String newUrl = base + (newQuery.isEmpty() ? "" : "?" + newQuery);
        return new OverridePartResult(newUrl, null, oldValue, added);
    }

    public static OverridePartResult overridePathParam(String url, String pathTemplate, String paramName, String rawValue) {
        if (url == null || pathTemplate == null) {
            throw new IllegalArgumentException("URL and path template are required for path parameter substitution.");
        }

        // Strip query string if any
        int qIdx = url.indexOf('?');
        String urlPath = (qIdx != -1) ? url.substring(0, qIdx) : url;
        String query = (qIdx != -1) ? url.substring(qIdx) : "";

        String[] templateSegments = pathTemplate.split("/");
        String[] urlSegments = urlPath.split("/");

        if (templateSegments.length != urlSegments.length) {
            // If template has leading basePath or scheme/host, try suffix alignment
            int offset = urlSegments.length - templateSegments.length;
            if (offset < 0) {
                throw new IllegalArgumentException("URL path segments do not match spec path template: " + pathTemplate);
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
            throw new IllegalArgumentException("Path parameter '{" + paramName + "}' not found in template: " + pathTemplate);
        }

        // Align with urlSegments from right if length difference exists
        int segInUrl = urlSegments.length - (templateSegments.length - targetIdx);
        if (segInUrl < 0 || segInUrl >= urlSegments.length) {
            throw new IllegalArgumentException("Unable to map path parameter '" + paramName + "' to segment in URL: " + url);
        }

        String oldValue = urlSegments[segInUrl];
        urlSegments[segInUrl] = (rawValue != null ? rawValue : "");
        String newUrl = String.join("/", urlSegments) + query;

        return new OverridePartResult(newUrl, null, oldValue, false);
    }

    public static OverridePartResult overrideHeader(Map<String, String> headers, String headerName, String rawValue) {
        Map<String, String> updated = new LinkedHashMap<>();
        String oldValue = null;
        boolean found = false;

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(headerName)) {
                    found = true;
                    oldValue = entry.getValue();
                    updated.put(entry.getKey(), rawValue != null ? rawValue : "");
                } else {
                    updated.put(entry.getKey(), entry.getValue());
                }
            }
        }

        boolean added = false;
        if (!found) {
            added = true;
            updated.put(headerName, rawValue != null ? rawValue : "");
        }

        return new OverridePartResult(null, updated, oldValue, added);
    }

    private static String encodeQueryVal(String val) {
        if (val == null) return "";
        if (val.contains("{{") && val.contains("}}")) {
            return val; // Leave placeholder raw
        }
        return URLEncoder.encode(val, StandardCharsets.UTF_8);
    }
}
