package com.sabyasachi.apiregression.client;

import com.sabyasachi.apiregression.model.Auth;
import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.util.Redactor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public class HttpRunner {

    public static final int MAX_REDIRECTS = 5;

    public static class ExecutionResponse {
        private final int statusCode;
        private final Map<String, List<String>> headers;
        private final String body;
        private final long latencyMs;
        private final String resolvedUrl;
        private final List<String> warnings;
        private final List<String> redirectChain;

        public ExecutionResponse(int statusCode, Map<String, List<String>> headers, String body,
                                 long latencyMs, String resolvedUrl, List<String> warnings) {
            this(statusCode, headers, body, latencyMs, resolvedUrl, warnings, Collections.emptyList());
        }

        public ExecutionResponse(int statusCode, Map<String, List<String>> headers, String body,
                                 long latencyMs, String resolvedUrl, List<String> warnings,
                                 List<String> redirectChain) {
            this.statusCode = statusCode;
            this.headers = headers != null ? headers : Collections.emptyMap();
            this.body = body != null ? body : "";
            this.latencyMs = latencyMs;
            this.resolvedUrl = resolvedUrl;
            this.warnings = warnings != null ? warnings : Collections.emptyList();
            this.redirectChain = redirectChain != null ? redirectChain : Collections.emptyList();
        }

        public int getStatusCode() {
            return statusCode;
        }

        public Map<String, List<String>> getHeaders() {
            return headers;
        }

        public String getFirstHeader(String name) {
            if (headers == null || name == null) return null;
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (name.equalsIgnoreCase(entry.getKey())) {
                    List<String> values = entry.getValue();
                    return (values != null && !values.isEmpty()) ? values.get(0) : null;
                }
            }
            return null;
        }

        public String getBody() {
            return body;
        }

        public long getLatencyMs() {
            return latencyMs;
        }

        public String getResolvedUrl() {
            return resolvedUrl;
        }

        public List<String> getWarnings() {
            return warnings;
        }

        public List<String> getRedirectChain() {
            return redirectChain;
        }
    }

    private final HttpClient httpClient;
    private final Duration timeout;
    private boolean followRedirects = true;
    private boolean redirectsCrossOriginAllowed = false;

    public HttpRunner() {
        this(Duration.ofSeconds(30));
    }

    public HttpRunner(Duration timeout) {
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
        // HttpClient.Redirect.NEVER: redirect following handled securely in manual loop
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public HttpRunner(HttpClient httpClient, Duration timeout) {
        this.httpClient = httpClient;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
    }

    public boolean isFollowRedirects() {
        return followRedirects;
    }

    public void setFollowRedirects(boolean followRedirects) {
        this.followRedirects = followRedirects;
    }

    public boolean isRedirectsCrossOriginAllowed() {
        return redirectsCrossOriginAllowed;
    }

    public void setRedirectsCrossOriginAllowed(boolean redirectsCrossOriginAllowed) {
        this.redirectsCrossOriginAllowed = redirectsCrossOriginAllowed;
    }

    private record Origin(String scheme, String host, int port) {
        public static Origin fromUri(URI uri) {
            String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
            String host = uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : "";
            int port = uri.getPort();
            if (port < 0) {
                port = "https".equalsIgnoreCase(scheme) ? 443 : 80;
            }
            return new Origin(scheme, host, port);
        }

        public boolean isHttps() {
            return "https".equalsIgnoreCase(scheme);
        }
    }

    public ExecutionResponse execute(TestCase testCase, Map<String, String> variables)
            throws IOException, InterruptedException {
        if (testCase == null) {
            throw new IllegalArgumentException("TestCase cannot be null");
        }
        Map<String, String> vars = variables != null ? variables : Collections.emptyMap();
        List<String> warnings = new ArrayList<>();

        // 1. Resolve URL
        String rawUrl = testCase.getUrl();
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("TestCase URL is missing or empty");
        }

        Substitutor.SubstitutionResult urlSub = Substitutor.substitute(rawUrl, vars);
        warnings.addAll(urlSub.getUnresolved());
        String targetUrl = urlSub.getResult();

        // If URL doesn't have scheme and baseUrl exists in vars
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            String baseUrl = vars.get("baseUrl");
            if (baseUrl != null && !baseUrl.isBlank()) {
                targetUrl = baseUrl.replaceAll("/+$", "") + "/" + targetUrl.replaceAll("^/+", "");
            }
        }

        // 2. Resolve Headers
        Map<String, String> resolvedHeaders = new LinkedHashMap<>();
        if (testCase.getHeaders() != null) {
            for (Map.Entry<String, String> entry : testCase.getHeaders().entrySet()) {
                String key = entry.getKey();
                String val = entry.getValue();
                Substitutor.SubstitutionResult keyRes = Substitutor.substitute(key, vars);
                Substitutor.SubstitutionResult valRes = Substitutor.substitute(val, vars);
                warnings.addAll(keyRes.getUnresolved());
                warnings.addAll(valRes.getUnresolved());
                resolvedHeaders.put(keyRes.getResult(), valRes.getResult());
            }
        }

        // 3. Resolve Auth
        Auth auth = testCase.getAuth();
        if (auth != null && auth.getType() != null) {
            applyAuth(auth, vars, resolvedHeaders, warnings);
        }

        // 4. Resolve Body
        String resolvedBody = null;
        if (testCase.getBodyTemplate() != null && !testCase.getBodyTemplate().isBlank()) {
            Substitutor.SubstitutionResult bodyRes = Substitutor.substitute(testCase.getBodyTemplate(), vars);
            warnings.addAll(bodyRes.getUnresolved());
            resolvedBody = bodyRes.getResult();
        }

        String initialMethod = (testCase.getMethod() != null ? testCase.getMethod() : "GET").toUpperCase();

        URI currentUri = URI.create(targetUrl);
        String currentMethod = initialMethod;
        String currentBody = resolvedBody;
        Map<String, String> currentHeaders = new LinkedHashMap<>(resolvedHeaders);
        Origin initialOrigin = Origin.fromUri(currentUri);
        Origin currentOrigin = initialOrigin;
        List<String> redirectChain = new ArrayList<>();

        long totalStart = System.currentTimeMillis();

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(currentUri)
                    .timeout(timeout);

            for (Map.Entry<String, String> entry : currentHeaders.entrySet()) {
                String hKey = entry.getKey();
                String hVal = entry.getValue();
                if (hKey != null && !hKey.isBlank() && hVal != null) {
                    if (!isRestrictedHeader(hKey)) {
                        reqBuilder.header(hKey, hVal);
                    }
                }
            }

            HttpRequest.BodyPublisher publisher;
            if (currentBody != null && !currentBody.isBlank()) {
                publisher = HttpRequest.BodyPublishers.ofString(currentBody, StandardCharsets.UTF_8);
            } else {
                publisher = HttpRequest.BodyPublishers.noBody();
            }

            switch (currentMethod) {
                case "GET" -> reqBuilder.GET();
                case "POST" -> reqBuilder.POST(publisher);
                case "PUT" -> reqBuilder.PUT(publisher);
                case "DELETE" -> reqBuilder.method("DELETE", publisher);
                case "PATCH" -> reqBuilder.method("PATCH", publisher);
                default -> reqBuilder.method(currentMethod, publisher);
            }

            HttpRequest request = reqBuilder.build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();

            boolean isRedirect = (status == 301 || status == 302 || status == 303 || status == 307 || status == 308);
            if (!isRedirect || !followRedirects) {
                long latency = System.currentTimeMillis() - totalStart;
                return new ExecutionResponse(
                        response.statusCode(),
                        response.headers().map(),
                        response.body(),
                        latency,
                        currentUri.toString(),
                        warnings,
                        redirectChain
                );
            }

            // Redirect handling
            Optional<String> locationOpt = response.headers().firstValue("Location");
            if (locationOpt.isEmpty() || locationOpt.get().isBlank()) {
                long latency = System.currentTimeMillis() - totalStart;
                return new ExecutionResponse(
                        response.statusCode(),
                        response.headers().map(),
                        response.body(),
                        latency,
                        currentUri.toString(),
                        warnings,
                        redirectChain
                );
            }

            if (hop == MAX_REDIRECTS) {
                warnings.add("Redirect limit of " + MAX_REDIRECTS + " hops reached.");
                long latency = System.currentTimeMillis() - totalStart;
                return new ExecutionResponse(
                        response.statusCode(),
                        response.headers().map(),
                        response.body(),
                        latency,
                        currentUri.toString(),
                        warnings,
                        redirectChain
                );
            }

            URI targetUri = currentUri.resolve(locationOpt.get().trim());
            redirectChain.add(Redactor.redactUrl(targetUri.toString()));

            Origin targetOrigin = Origin.fromUri(targetUri);

            // Refuse HTTPS -> HTTP downgrade
            if (currentOrigin.isHttps() && !targetOrigin.isHttps()) {
                warnings.add("HTTPS to HTTP downgrade refused for redirect target: " + Redactor.redactUrl(targetUri.toString()));
                long latency = System.currentTimeMillis() - totalStart;
                return new ExecutionResponse(
                        response.statusCode(),
                        response.headers().map(),
                        response.body(),
                        latency,
                        currentUri.toString(),
                        warnings,
                        redirectChain
                );
            }

            boolean sameOrigin = currentOrigin.equals(targetOrigin);
            if (!sameOrigin) {
                if (!redirectsCrossOriginAllowed) {
                    // Cross-origin redirect stopped by policy, surfaced as response
                    warnings.add("Cross-origin redirect to " + Redactor.redactUrl(targetUri.toString())
                            + " not followed (redirectsCrossOriginAllowed=false).");
                    long latency = System.currentTimeMillis() - totalStart;
                    return new ExecutionResponse(
                            response.statusCode(),
                            response.headers().map(),
                            response.body(),
                            latency,
                            currentUri.toString(),
                            warnings,
                            redirectChain
                    );
                } else {
                    // Cross-origin allowed: strip sensitive headers
                    currentHeaders.entrySet().removeIf(e -> Redactor.isSensitiveHeader(e.getKey()));
                    if (auth != null) {
                        if (auth.getApiKeyKey() != null) {
                            currentHeaders.remove(auth.getApiKeyKey());
                        }
                        currentHeaders.remove("Authorization");
                    }
                }
            }

            // RFC method & body transformation
            if (status == 301 || status == 302 || status == 303) {
                // Switch POST or body-carrying request to GET with no body
                currentMethod = "GET";
                currentBody = null;
                currentHeaders.remove("Content-Type");
                currentHeaders.remove("content-type");
                currentHeaders.remove("Content-Length");
                currentHeaders.remove("content-length");
            } else if (status == 307 || status == 308) {
                // 307/308 preserve method and body only for same origin
                if (!sameOrigin) {
                    currentBody = null;
                }
            }

            currentUri = targetUri;
            currentOrigin = targetOrigin;
        }

        long latency = System.currentTimeMillis() - totalStart;
        return new ExecutionResponse(500, Collections.emptyMap(), "", latency, currentUri.toString(), warnings, redirectChain);
    }

    private void applyAuth(Auth auth, Map<String, String> vars,
                           Map<String, String> headers, List<String> warnings) {
        if (auth == null || auth.getType() == null) {
            return;
        }

        switch (auth.getType()) {
            case BEARER -> {
                if (auth.getBearerToken() != null) {
                    Substitutor.SubstitutionResult tokenSub = Substitutor.substitute(auth.getBearerToken(), vars);
                    warnings.addAll(tokenSub.getUnresolved());
                    headers.put("Authorization", "Bearer " + tokenSub.getResult());
                }
            }
            case BASIC -> {
                String user = Substitutor.substitute(auth.getUsername(), vars).getResult();
                String pass = Substitutor.substitute(auth.getPassword(), vars).getResult();
                String creds = (user != null ? user : "") + ":" + (pass != null ? pass : "");
                String encoded = Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + encoded);
            }
            case API_KEY -> {
                if ("HEADER".equalsIgnoreCase(auth.getApiKeyAddTo()) || auth.getApiKeyAddTo() == null) {
                    String key = Substitutor.substitute(auth.getApiKeyKey(), vars).getResult();
                    String val = Substitutor.substitute(auth.getApiKeyValue(), vars).getResult();
                    if (key != null && !key.isBlank()) {
                        headers.put(key, val != null ? val : "");
                    }
                }
            }
        }
    }

    private boolean isRestrictedHeader(String name) {
        if (name == null) return true;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("host") || lower.equals("content-length");
    }
}
