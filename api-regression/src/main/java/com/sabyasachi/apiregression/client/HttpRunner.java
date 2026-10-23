package com.sabyasachi.apiregression.client;

import com.sabyasachi.apiregression.model.Auth;
import com.sabyasachi.apiregression.model.TestCase;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

public class HttpRunner {

    public static class ExecutionResponse {
        private final int statusCode;
        private final Map<String, List<String>> headers;
        private final String body;
        private final long latencyMs;
        private final String resolvedUrl;
        private final List<String> warnings;

        public ExecutionResponse(int statusCode, Map<String, List<String>> headers, String body,
                                 long latencyMs, String resolvedUrl, List<String> warnings) {
            this.statusCode = statusCode;
            this.headers = headers != null ? headers : Collections.emptyMap();
            this.body = body != null ? body : "";
            this.latencyMs = latencyMs;
            this.resolvedUrl = resolvedUrl;
            this.warnings = warnings != null ? warnings : Collections.emptyList();
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
    }

    private final HttpClient httpClient;
    private final Duration timeout;

    public HttpRunner() {
        this(Duration.ofSeconds(30));
    }

    public HttpRunner(Duration timeout) {
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public HttpRunner(HttpClient httpClient, Duration timeout) {
        this.httpClient = httpClient;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
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

        // 5. Build HttpRequest
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .timeout(timeout);

        // Add headers (filter restricted ones if any)
        for (Map.Entry<String, String> entry : resolvedHeaders.entrySet()) {
            String hKey = entry.getKey();
            String hVal = entry.getValue();
            if (hKey != null && !hKey.isBlank() && hVal != null) {
                // Some headers like Host / Content-Length are managed by HttpClient, ignore if restricted
                if (!isRestrictedHeader(hKey)) {
                    reqBuilder.header(hKey, hVal);
                }
            }
        }

        String method = (testCase.getMethod() != null ? testCase.getMethod() : "GET").toUpperCase();
        HttpRequest.BodyPublisher publisher;
        if (resolvedBody != null && !resolvedBody.isBlank()) {
            publisher = HttpRequest.BodyPublishers.ofString(resolvedBody, StandardCharsets.UTF_8);
        } else {
            publisher = HttpRequest.BodyPublishers.noBody();
        }

        switch (method) {
            case "GET":
                reqBuilder.GET();
                break;
            case "POST":
                reqBuilder.POST(publisher);
                break;
            case "PUT":
                reqBuilder.PUT(publisher);
                break;
            case "DELETE":
                reqBuilder.method("DELETE", publisher);
                break;
            case "PATCH":
                reqBuilder.method("PATCH", publisher);
                break;
            default:
                reqBuilder.method(method, publisher);
                break;
        }

        HttpRequest request = reqBuilder.build();

        long start = System.currentTimeMillis();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long latency = System.currentTimeMillis() - start;

        return new ExecutionResponse(
                response.statusCode(),
                response.headers().map(),
                response.body(),
                latency,
                targetUrl,
                warnings
        );
    }

    private void applyAuth(Auth auth, Map<String, String> vars, Map<String, String> headers, List<String> warnings) {
        switch (auth.getType()) {
            case BEARER:
                if (auth.getBearerToken() != null && !auth.getBearerToken().isBlank()) {
                    Substitutor.SubstitutionResult tokenSub = Substitutor.substitute(auth.getBearerToken(), vars);
                    warnings.addAll(tokenSub.getUnresolved());
                    if (!containsHeaderIgnoreCase(headers, "Authorization")) {
                        headers.put("Authorization", "Bearer " + tokenSub.getResult());
                    }
                }
                break;
            case BASIC:
                String user = Substitutor.substitute(auth.getUsername(), vars).getResult();
                String pass = Substitutor.substitute(auth.getPassword(), vars).getResult();
                if (user != null || pass != null) {
                    String credentials = (user != null ? user : "") + ":" + (pass != null ? pass : "");
                    String base64 = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
                    if (!containsHeaderIgnoreCase(headers, "Authorization")) {
                        headers.put("Authorization", "Basic " + base64);
                    }
                }
                break;
            case API_KEY:
                String key = Substitutor.substitute(auth.getApiKeyKey(), vars).getResult();
                String val = Substitutor.substitute(auth.getApiKeyValue(), vars).getResult();
                if (key != null && !key.isBlank()) {
                    if ("HEADER".equalsIgnoreCase(auth.getApiKeyAddTo()) || auth.getApiKeyAddTo() == null) {
                        headers.put(key, val != null ? val : "");
                    }
                }
                break;
            case NONE:
            default:
                break;
        }
    }

    private boolean containsHeaderIgnoreCase(Map<String, String> headers, String name) {
        for (String k : headers.keySet()) {
            if (name.equalsIgnoreCase(k)) return true;
        }
        return false;
    }

    private boolean isRestrictedHeader(String name) {
        String lower = name.toLowerCase();
        return lower.equals("host") || lower.equals("content-length");
    }
}
