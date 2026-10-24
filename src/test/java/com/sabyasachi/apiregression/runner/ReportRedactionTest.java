package com.sabyasachi.apiregression.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sabyasachi.apiregression.client.HttpRunner;
import com.sabyasachi.apiregression.client.Substitutor;
import com.sabyasachi.apiregression.importer.PostmanEnvironmentImporter;
import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestResult;
import com.sabyasachi.apiregression.model.TestSuite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ReportRedactionTest {

    private static final String CANARY_TOKEN = "CANARY_TOKEN_998877_XYZ";
    private static final String CANARY_SECRET = "CANARY_SECRET_112233_ABC";

    @Test
    void s2_tokenResultNotSerialised() throws Exception {
        TestCase provider = new TestCase();
        provider.setName("Authman Provider");
        provider.setTokenProvider(true);
        provider.setTokenVariableName("token");
        provider.setTokenJsonPath("$.access_token");

        HttpRunner mockRunner = new HttpRunner() {
            @Override
            public ExecutionResponse execute(TestCase testCase, Map<String, String> variables) {
                return new ExecutionResponse(200, Map.of("Content-Type", List.of("application/json")),
                        "{\"access_token\": \"" + CANARY_TOKEN + "\"}",
                        15, "https://api.example.com/oauth/token", List.of());
            }
        };

        TokenProviderResolver resolver = new TokenProviderResolver(mockRunner);
        TestSuite suite = new TestSuite();
        suite.setTestCases(List.of(provider));

        TokenProviderResolver.TokenResolutionOutcome outcome = resolver.resolve(suite, new HashMap<>());
        assertEquals(1, outcome.getProviderResults().size());
        TestResult tokenResult = outcome.getProviderResults().get(0);

        // Serialize via Jackson
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        String json = mapper.writeValueAsString(tokenResult);

        // Assert no CANARY_TOKEN substring in serialized JSON
        assertFalse(json.contains(CANARY_TOKEN), "Serialized TestResult must not contain CANARY_TOKEN");
        // Also actualBody and actualHeaders must not be serialized with secrets
        assertFalse(json.contains("actualBody"), "Token provider result must not retain actualBody");
    }

    @Test
    void s2_reportDoesNotContainToken(@TempDir Path tempDir) throws Exception {
        ReportWriter writer = new ReportWriter();

        TestResult result = new TestResult("Secure Call");
        result.setResolvedUrl("https://api.example.com/data?api_key=" + CANARY_SECRET + "&limit=10");
        result.setActualHeaders(Map.of(
                "Authorization", List.of("Bearer " + CANARY_TOKEN),
                "X-Api-Key", List.of(CANARY_SECRET)
        ));
        result.setActualBody("{\"access_token\": \"" + CANARY_TOKEN + "\", \"client_secret\": \"" + CANARY_SECRET + "\", \"user\": \"bob\"}");
        result.setDiffSummary("Body diff with token: " + CANARY_TOKEN);
        result.setActualStatus(200);

        Path jsonReport = tempDir.resolve("report.json");
        Path htmlReport = tempDir.resolve("report.html");

        writer.writeJson(List.of(result), jsonReport);
        writer.writeHtml(List.of(result), htmlReport);

        String jsonContent = Files.readString(jsonReport);
        String htmlContent = Files.readString(htmlReport);

        // CANARY_TOKEN and CANARY_SECRET must NOT appear in either report
        assertFalse(jsonContent.contains(CANARY_TOKEN), "JSON report must not contain CANARY_TOKEN");
        assertFalse(jsonContent.contains(CANARY_SECRET), "JSON report must not contain CANARY_SECRET");
        assertFalse(htmlContent.contains(CANARY_TOKEN), "HTML report must not contain CANARY_TOKEN");
        assertFalse(htmlContent.contains(CANARY_SECRET), "HTML report must not contain CANARY_SECRET");

        assertTrue(jsonContent.contains("***REDACTED***"));
        assertTrue(htmlContent.contains("***REDACTED***"));
    }

    @Test
    void s2_urlApiKeyRedacted() {
        String url = "https://api.example.com/v1/users?api_key=" + CANARY_SECRET + "&x=1";
        String redacted = com.sabyasachi.apiregression.util.Redactor.redactUrl(url);
        assertEquals("https://api.example.com/v1/users?api_key=***REDACTED***&x=1", redacted);
    }

    @Test
    void s2_authorizationHeaderRedacted() {
        Map<String, List<String>> headers = Map.of("Authorization", List.of("Bearer " + CANARY_TOKEN));
        Map<String, List<String>> redacted = com.sabyasachi.apiregression.util.Redactor.redactHeaders(headers);
        assertEquals(List.of("***REDACTED***"), redacted.get("Authorization"));
    }

    @Test
    void s2_postmanSecretBecomesReference() throws Exception {
        String envJson = """
                {
                    "name": "Production Env",
                    "values": [
                        { "key": "baseUrl", "value": "https://api.example.com", "enabled": true },
                        { "key": "apiKey", "value": "CANARY_SECRET_XYZ", "type": "secret", "enabled": true }
                    ]
                }
                """;

        PostmanEnvironmentImporter importer = new PostmanEnvironmentImporter();
        Map<String, String> parsed = importer.parse(envJson);

        assertEquals("https://api.example.com", parsed.get("baseUrl"));
        assertEquals("{{env:apiKey}}", parsed.get("apiKey"));
        assertFalse(parsed.containsValue("CANARY_SECRET_XYZ"));
    }

    @Test
    void s2_substitutorResolvesEnvReference() {
        System.setProperty("APIREG_SECRET_mySecretKey", "RESOLVED_VALUE_777");
        try {
            Substitutor.SubstitutionResult res = Substitutor.substitute("Bearer {{env:mySecretKey}}", Map.of());
            assertEquals("Bearer RESOLVED_VALUE_777", res.getResult());
            assertFalse(res.hasUnresolved());
        } finally {
            System.clearProperty("APIREG_SECRET_mySecretKey");
        }
    }
}
