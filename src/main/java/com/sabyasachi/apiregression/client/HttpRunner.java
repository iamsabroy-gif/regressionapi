package com.sabyasachi.apiregression.client;

import com.sabyasachi.apiregression.model.Auth;
import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.util.Redactor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

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

    public static final int DEFAULT_MAX_BODY_BYTES = 5 * 1024 * 1024; // 5 MB

    public record CappedBody(String body, boolean truncated, long totalBytesReceived) {
        public String getBody() {
            return body;
        }

        public boolean isTruncated() {
            return truncated;
        }

        public long getTotalBytesReceived() {
            return totalBytesReceived;
        }
    }

    public static class CappedBodySubscriber implements HttpResponse.BodySubscriber<CappedBody> {
        private final CompletableFuture<CappedBody> future = new CompletableFuture<>();
        private final ByteArrayOutputStream baos;
        private final int maxBytes;
        private final Charset charset;
        private Flow.Subscription subscription;
        private long totalBytes = 0;
        private boolean truncated = false;

        public CappedBodySubscriber(int maxBytes, Charset charset) {
            this.maxBytes = Math.max(0, maxBytes);
            this.charset = charset != null ? charset : StandardCharsets.UTF_8;
            this.baos = new ByteArrayOutputStream(Math.min(this.maxBytes, 8192));
        }

        @Override
        public CompletionStage<CappedBody> getBody() {
            return future;
        }

        @Override
        public synchronized void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (maxBytes <= 0) {
                truncated = true;
                subscription.cancel();
                future.complete(new CappedBody("", true, 0));
                return;
            }
            subscription.request(1);
        }

        @Override
        public synchronized void onNext(List<ByteBuffer> buffers) {
            if (future.isDone()) {
                return;
            }
            for (ByteBuffer buf : buffers) {
                int remaining = buf.remaining();
                totalBytes += remaining;
                if (!truncated) {
                    int canWrite = Math.min(remaining, maxBytes - baos.size());
                    if (canWrite > 0) {
                        byte[] bytes = new byte[canWrite];
                        buf.get(bytes);
                        baos.write(bytes, 0, canWrite);
                    }
                    if (baos.size() >= maxBytes) {
                        truncated = true;
                        if (subscription != null) {
                            subscription.cancel();
                        }
                        future.complete(new CappedBody(baos.toString(charset), true, totalBytes));
                        return;
                    }
                }
            }
            if (!truncated && subscription != null) {
                subscription.request(1);
            }
        }

        @Override
        public synchronized void onError(Throwable throwable) {
            if (!future.isDone()) {
                future.completeExceptionally(throwable);
            }
        }

        @Override
        public synchronized void onComplete() {
            if (!future.isDone()) {
                future.complete(new CappedBody(baos.toString(charset), truncated, totalBytes));
            }
        }
    }

    public static HttpResponse.BodyHandler<CappedBody> cappedBodyHandler(int maxBytes) {
        return responseInfo -> {
            Charset charset = determineCharset(responseInfo);
            return new CappedBodySubscriber(maxBytes, charset);
        };
    }

    private static Charset determineCharset(HttpResponse.ResponseInfo responseInfo) {
        if (responseInfo != null) {
            Optional<String> ct = responseInfo.headers().firstValue("Content-Type");
            if (ct.isPresent()) {
                String val = ct.get();
                int idx = val.toLowerCase(Locale.ROOT).indexOf("charset=");
                if (idx >= 0) {
                    String csName = val.substring(idx + 8).trim();
                    int end = csName.indexOf(';');
                    if (end >= 0) csName = csName.substring(0, end).trim();
                    csName = csName.replace("\"", "").replace("'", "");
                    try {
                        return Charset.forName(csName);
                    } catch (Exception ignored) {}
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String formatByteLimit(int bytes) {
        if (bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0) {
            return (bytes / (1024 * 1024)) + " MB";
        } else if (bytes >= 1024 && bytes % 1024 == 0) {
            return (bytes / 1024) + " KB";
        } else {
            return bytes + " bytes";
        }
    }

    private final HttpClient httpClient;
    private final Duration timeout;
    private int retryCount = 0;
    private int maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
    private boolean followRedirects = true;
    private boolean redirectsCrossOriginAllowed = false;

    public HttpRunner() {
        this(Duration.ofSeconds(30), 0);
    }

    public HttpRunner(Duration timeout) {
        this(timeout, 0);
    }

    public HttpRunner(Duration timeout, int retryCount) {
        this(timeout, retryCount, DEFAULT_MAX_BODY_BYTES);
    }

    public HttpRunner(Duration timeout, int retryCount, int maxBodyBytes) {
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
        this.retryCount = Math.max(0, retryCount);
        this.maxBodyBytes = Math.max(0, maxBodyBytes);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public HttpRunner(HttpClient httpClient, Duration timeout) {
        this(httpClient, timeout, 0);
    }

    public HttpRunner(HttpClient httpClient, Duration timeout, int retryCount) {
        this(httpClient, timeout, retryCount, DEFAULT_MAX_BODY_BYTES);
    }

    public HttpRunner(HttpClient httpClient, Duration timeout, int retryCount, int maxBodyBytes) {
        this.httpClient = httpClient;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(30);
        this.retryCount = Math.max(0, retryCount);
        this.maxBodyBytes = Math.max(0, maxBodyBytes);
    }

    public int getMaxBodyBytes() {
        return maxBodyBytes;
    }

    public void setMaxBodyBytes(int maxBodyBytes) {
        this.maxBodyBytes = Math.max(0, maxBodyBytes);
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = Math.max(0, retryCount);
    }

    public Duration getTimeout() {
        return timeout;
    }

    public static boolean isIdempotentMethod(String method) {
        if (method == null) return false;
        String m = method.toUpperCase(Locale.ROOT);
        return m.equals("GET") || m.equals("HEAD") || m.equals("PUT") || m.equals("DELETE") || m.equals("OPTIONS");
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

    public HttpClient getHttpClient() {
        return httpClient;
    }

    public ExecutionResponse execute(TestCase testCase, Map<String, String> variables)
            throws IOException, InterruptedException {
        return execute(testCase, variables, this.retryCount);
    }

    public ExecutionResponse execute(TestCase testCase, Map<String, String> variables, int effectiveRetryCount)
            throws IOException, InterruptedException {
        if (this.getClass() != HttpRunner.class) {
            try {
                var method = this.getClass().getMethod("execute", TestCase.class, Map.class);
                if (method.getDeclaringClass() != HttpRunner.class) {
                    var method3 = this.getClass().getMethod("execute", TestCase.class, Map.class, int.class);
                    if (method3.getDeclaringClass() == HttpRunner.class) {
                        return execute(testCase, variables);
                    }
                }
            } catch (NoSuchMethodException ignored) {}
        }
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
            HttpResponse<CappedBody> response = null;
            boolean isIdempotent = isIdempotentMethod(currentMethod);
            int retries = Math.max(0, effectiveRetryCount);
            int attemptsLeft = isIdempotent ? retries : 0;

            while (true) {
                try {
                    response = httpClient.send(request, cappedBodyHandler(maxBodyBytes));
                    int s = response.statusCode();
                    if (isIdempotent && attemptsLeft > 0 && (s == 502 || s == 503 || s == 504)) {
                        attemptsLeft--;
                        long backoff = Math.min(1000L, 50L * (1L << (retries - attemptsLeft)));
                        Thread.sleep(backoff);
                        continue;
                    }
                    break;
                } catch (IOException e) {
                    if (isIdempotent && attemptsLeft > 0) {
                        attemptsLeft--;
                        long backoff = Math.min(1000L, 50L * (1L << (retries - attemptsLeft)));
                        Thread.sleep(backoff);
                    } else {
                        throw e;
                    }
                }
            }
            if (response.body() != null && response.body().isTruncated()) {
                warnings.add("Response body truncated: exceeded limit of " + formatByteLimit(maxBodyBytes));
            }
            int status = response.statusCode();

            boolean isRedirect = (status == 301 || status == 302 || status == 303 || status == 307 || status == 308);
            if (!isRedirect || !followRedirects) {
                long latency = System.currentTimeMillis() - totalStart;
                return new ExecutionResponse(
                        response.statusCode(),
                        response.headers().map(),
                        response.body() != null ? response.body().getBody() : "",
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
                        response.body() != null ? response.body().getBody() : "",
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
                        response.body() != null ? response.body().getBody() : "",
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
                        response.body() != null ? response.body().getBody() : "",
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
                            response.body() != null ? response.body().getBody() : "",
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
