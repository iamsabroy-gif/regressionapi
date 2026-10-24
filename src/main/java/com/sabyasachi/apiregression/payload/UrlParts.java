package com.sabyasachi.apiregression.payload;

public class UrlParts {

    private final String rawUrl;
    private final String scheme;
    private final String host;
    private final String path;
    private final String query;

    public UrlParts(String rawUrl, String scheme, String host, String path, String query) {
        this.rawUrl = rawUrl;
        this.scheme = scheme;
        this.host = host;
        this.path = path != null && !path.startsWith("/") ? "/" + path : path;
        this.query = query;
    }

    public static UrlParts parse(String inputUrl) {
        if (inputUrl == null || inputUrl.isBlank()) {
            return new UrlParts("", null, null, "/", "");
        }

        String raw = inputUrl.trim();
        int qIdx = raw.indexOf('?');
        String pathPart = (qIdx != -1) ? raw.substring(0, qIdx) : raw;
        String queryPart = (qIdx != -1) ? raw.substring(qIdx + 1) : "";

        String scheme = null;
        String host = null;
        String path = pathPart;

        // Check scheme
        int schemeIdx = pathPart.indexOf("://");
        if (schemeIdx != -1) {
            scheme = pathPart.substring(0, schemeIdx);
            String afterScheme = pathPart.substring(schemeIdx + 3);
            int slashIdx = afterScheme.indexOf('/');
            if (slashIdx != -1) {
                host = afterScheme.substring(0, slashIdx);
                path = afterScheme.substring(slashIdx);
            } else {
                host = afterScheme;
                path = "/";
            }
        } else if (pathPart.startsWith("{{") && pathPart.contains("}}")) {
            // Placeholder baseUrl: e.g. {{baseUrl}}/customers/123
            int endVar = pathPart.indexOf("}}");
            host = pathPart.substring(0, endVar + 2);
            String rest = pathPart.substring(endVar + 2);
            path = rest.isEmpty() ? "/" : rest;
        }

        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        return new UrlParts(raw, scheme, host, path, queryPart);
    }

    public String getRawUrl() {
        return rawUrl;
    }

    public String getScheme() {
        return scheme;
    }

    public String getHost() {
        return host;
    }

    public String getPath() {
        return path;
    }

    public String getQuery() {
        return query;
    }

    public String[] getPathSegments() {
        if (path == null || path.equals("/") || path.isEmpty()) {
            return new String[0];
        }
        String clean = path.startsWith("/") ? path.substring(1) : path;
        if (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        return clean.split("/");
    }
}
