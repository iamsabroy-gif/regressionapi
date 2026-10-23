package com.sabyasachi.apiregression.store;

import com.sabyasachi.apiregression.model.Auth;
import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestSuite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SuiteRepositoryTest {

    @Test
    void testRoundTripSerialization(@TempDir Path tempDir) throws IOException {
        SuiteRepository repo = new SuiteRepository();

        TestSuite suite = new TestSuite("User Onboarding Suite");
        suite.setBaseUrl("https://api.example.com");
        suite.setVariables(Map.of("tenantId", "tenant_123", "version", "v1"));

        TestCase authCase = new TestCase();
        authCase.setName("Generate Token (Authman)");
        authCase.setMethod("POST");
        authCase.setUrl("{{baseUrl}}/oauth/token");
        authCase.setCategory("Authentication");
        authCase.setHeaders(Map.of("Content-Type", "application/json"));
        authCase.setBodyTemplate("{\"client_id\": \"id_123\", \"client_secret\": \"secret_456\"}");
        authCase.setExpectedStatus(200);
        authCase.setTokenProvider(true);
        authCase.setTokenJsonPath("$.access_token");
        authCase.setTokenVariableName("authToken");

        TestCase getUserCase = new TestCase();
        getUserCase.setName("Get User Profile");
        getUserCase.setMethod("GET");
        getUserCase.setUrl("{{baseUrl}}/api/{{version}}/users/42");
        getUserCase.setCategory("Users");
        getUserCase.setHeaders(Map.of("Accept", "application/json", "Authorization", "Bearer {{authToken}}"));
        getUserCase.setAuth(Auth.bearer("{{authToken}}"));
        getUserCase.setExpectedStatus(200);
        getUserCase.setExpectedBodyTemplate("{\"id\": 42, \"name\": \"Alice\", \"createdAt\": \"2026-01-01T00:00:00Z\"}");
        getUserCase.setIgnorePaths(List.of("$.createdAt"));
        getUserCase.setUsesTokenFrom(List.of("authToken"));

        suite.setTestCases(List.of(authCase, getUserCase));

        Path suiteFile = tempDir.resolve("test_suite.json");
        repo.save(suite, suiteFile);
        assertTrue(suiteFile.toFile().exists());

        TestSuite loaded = repo.load(suiteFile);
        assertNotNull(loaded);
        assertEquals(suite.getName(), loaded.getName());
        assertEquals(suite.getBaseUrl(), loaded.getBaseUrl());
        assertEquals("tenant_123", loaded.getVariables().get("tenantId"));
        assertEquals(2, loaded.getTestCases().size());

        TestCase loadedAuth = loaded.getTestCases().get(0);
        assertEquals("Generate Token (Authman)", loadedAuth.getName());
        assertTrue(loadedAuth.isTokenProvider());
        assertEquals("$.access_token", loadedAuth.getTokenJsonPath());
        assertEquals("authToken", loadedAuth.getTokenVariableName());

        TestCase loadedGet = loaded.getTestCases().get(1);
        assertEquals("Get User Profile", loadedGet.getName());
        assertFalse(loadedGet.isTokenProvider());
        assertEquals(List.of("authToken"), loadedGet.getUsesTokenFrom());
        assertEquals(List.of("$.createdAt"), loadedGet.getIgnorePaths());
        assertEquals(Auth.AuthType.BEARER, loadedGet.getAuth().getType());
    }
}
