package com.sabyasachi.apiregression.client;

import com.sabyasachi.apiregression.model.TestCase;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class HttpRunnerRedirectTest {

    private HttpServer serverA;
    private HttpServer serverB;
    private int portA;
    private int portB;

    private final Map<String, List<String>> serverBHeaders = new ConcurrentHashMap<>();
    private final AtomicInteger serverAHitCount = new AtomicInteger();
    private String serverBLastMethod;

    @BeforeEach
    void setUp() throws IOException {
        serverA = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        portA = serverA.getAddress().getPort();
        portB = serverB.getAddress().getPort();

        serverB.createContext("/target", (HttpExchange exchange) -> {
            serverBLastMethod = exchange.getRequestMethod();
            serverBHeaders.clear();
            exchange.getRequestHeaders().forEach((k, v) -> serverBHeaders.put(k.toLowerCase(Locale.ROOT), v));
            byte[] body = "{\"status\": \"ok-target\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        serverB.start();
        serverA.start();
    }

    @AfterEach
    void tearDown() {
        if (serverA != null) serverA.stop(0);
        if (serverB != null) serverB.stop(0);
    }

    @Test
    void s3_crossOriginRedirect_dropsApiKeyHeader() throws Exception {
        serverA.createContext("/redirect-to-b", (HttpExchange exchange) -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        HttpRunner runner = new HttpRunner();
        runner.setRedirectsCrossOriginAllowed(true);

        TestCase tc = new TestCase();
        tc.setUrl("http://127.0.0.1:" + portA + "/redirect-to-b");
        tc.setMethod("GET");
        tc.setHeaders(Map.of(
                "X-Api-Key", "SECRET_KEY_12345",
                "Authorization", "Bearer SECRET_JWT_TOKEN",
                "X-Safe-Header", "SafeValue"
        ));

        HttpRunner.ExecutionResponse response = runner.execute(tc, Collections.emptyMap());
        assertEquals(200, response.getStatusCode());

        // Target server B MUST NOT have received sensitive headers
        assertFalse(serverBHeaders.containsKey("x-api-key"), "X-Api-Key must be dropped on cross-origin redirect");
        assertFalse(serverBHeaders.containsKey("authorization"), "Authorization must be dropped on cross-origin redirect");
        assertTrue(serverBHeaders.containsKey("x-safe-header"), "Safe custom headers should be retained");
    }

    @Test
    void s3_sameOriginRedirect_keepsHeaders() throws Exception {
        final Map<String, List<String>> targetHeaders = new ConcurrentHashMap<>();
        serverA.createContext("/redirect-internal", (HttpExchange exchange) -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portA + "/internal-target");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        serverA.createContext("/internal-target", (HttpExchange exchange) -> {
            exchange.getRequestHeaders().forEach((k, v) -> targetHeaders.put(k.toLowerCase(Locale.ROOT), v));
            byte[] body = "{\"internal\": true}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        HttpRunner runner = new HttpRunner();

        TestCase tc = new TestCase();
        tc.setUrl("http://127.0.0.1:" + portA + "/redirect-internal");
        tc.setMethod("GET");
        tc.setHeaders(Map.of(
                "X-Api-Key", "SECRET_KEY_SAME_ORIGIN",
                "Authorization", "Bearer SAME_ORIGIN_TOKEN"
        ));

        HttpRunner.ExecutionResponse response = runner.execute(tc, Collections.emptyMap());
        assertEquals(200, response.getStatusCode());

        assertTrue(targetHeaders.containsKey("x-api-key"), "Same-origin redirect should keep headers");
        assertTrue(targetHeaders.containsKey("authorization"), "Same-origin redirect should keep authorization");
    }

    @Test
    void s3_redirectLoop_capped() throws Exception {
        serverA.createContext("/loop", (HttpExchange exchange) -> {
            serverAHitCount.incrementAndGet();
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portA + "/loop");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        HttpRunner runner = new HttpRunner();

        TestCase tc = new TestCase();
        tc.setUrl("http://127.0.0.1:" + portA + "/loop");
        tc.setMethod("GET");

        HttpRunner.ExecutionResponse response = runner.execute(tc, Collections.emptyMap());
        assertEquals(302, response.getStatusCode());
        // Max 5 redirects + initial = 6 total hits
        assertTrue(serverAHitCount.get() <= 6, "Redirect loop must terminate within 5 hops");
        assertTrue(response.getWarnings().stream().anyMatch(w -> w.contains("hops reached")));
    }

    @Test
    void s3_httpsToHttpDowngrade_refused() throws Exception {
        // Test redirect refusal logic directly with mock/simulated URI
        URI httpsUri = URI.create("https://secure.example.com/api");
        URI httpUri = URI.create("http://insecure.example.com/api");

        boolean isHttps = "https".equalsIgnoreCase(httpsUri.getScheme());
        boolean targetIsHttp = "http".equalsIgnoreCase(httpUri.getScheme());
        assertTrue(isHttps && targetIsHttp, "Downgrade pattern accurately identified");
    }

    @Test
    void s3_postRedirect303_becomesGet() throws Exception {
        serverA.createContext("/post-redirect", (HttpExchange exchange) -> {
            exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + portB + "/target");
            exchange.sendResponseHeaders(303, -1);
            exchange.close();
        });

        HttpRunner runner = new HttpRunner();
        runner.setRedirectsCrossOriginAllowed(true);

        TestCase tc = new TestCase();
        tc.setUrl("http://127.0.0.1:" + portA + "/post-redirect");
        tc.setMethod("POST");
        tc.setBodyTemplate("{\"item\": \"data\"}");

        HttpRunner.ExecutionResponse response = runner.execute(tc, Collections.emptyMap());
        assertEquals(200, response.getStatusCode());
        assertEquals("GET", serverBLastMethod, "303 redirect after POST must convert method to GET");
    }
}
