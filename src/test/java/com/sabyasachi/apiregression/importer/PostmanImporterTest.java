package com.sabyasachi.apiregression.importer;

import com.sabyasachi.apiregression.model.Auth;
import com.sabyasachi.apiregression.model.TestCase;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PostmanImporterTest {

    @Test
    void testPostmanEnvironmentImporter() throws IOException {
        String envJson = """
                {
                    "name": "Dev Environment",
                    "values": [
                        { "key": "baseUrl", "value": "https://dev.api.example.com", "enabled": true },
                        { "key": "apiKey", "value": "secret_key_999", "enabled": true },
                        { "key": "disabledVar", "value": "ignoreMe", "enabled": false }
                    ]
                }
                """;

        PostmanEnvironmentImporter importer = new PostmanEnvironmentImporter();
        Map<String, String> env = importer.parse(envJson);

        assertEquals("https://dev.api.example.com", env.get("baseUrl"));
        assertEquals("secret_key_999", env.get("apiKey"));
        assertFalse(env.containsKey("disabledVar"));
    }

    @Test
    void testPostmanCollectionImporterWithNestedFoldersAndResponses() throws IOException {
        String collectionJson = """
                {
                    "info": {
                        "name": "E-Commerce API Collection",
                        "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"
                    },
                    "item": [
                        {
                            "name": "Users Folder",
                            "item": [
                                {
                                    "name": "Create User",
                                    "request": {
                                        "method": "POST",
                                        "url": {
                                            "raw": "{{baseUrl}}/api/users?ref={{referralCode}}"
                                        },
                                        "header": [
                                            { "key": "Content-Type", "value": "application/json" },
                                            { "key": "X-Disabled", "value": "none", "disabled": true }
                                        ],
                                        "auth": {
                                            "type": "bearer",
                                            "bearer": [
                                                { "key": "token", "value": "{{authToken}}" }
                                            ]
                                        },
                                        "body": {
                                            "mode": "raw",
                                            "raw": "{\\"username\\": \\"alice\\", \\"email\\": \\"{{email}}\\"}"
                                        }
                                    },
                                    "response": [
                                        {
                                            "name": "Success Response Example",
                                            "code": 201,
                                            "body": "{\\"id\\": 101, \\"status\\": \\"created\\"}"
                                        }
                                    ]
                                }
                            ]
                        }
                    ]
                }
                """;

        Map<String, String> companionEnv = Map.of(
                "baseUrl", "https://api.example.com",
                "authToken", "token_123"
        );

        PostmanCollectionImporter importer = new PostmanCollectionImporter();
        PostmanCollectionImporter.ImportResult result = importer.parse(collectionJson, companionEnv);

        assertEquals("E-Commerce API Collection", result.getCollectionName());
        assertEquals(1, result.getTestCases().size());

        TestCase tc = result.getTestCases().get(0);
        assertEquals("Create User", tc.getName());
        assertEquals("Users Folder", tc.getCategory());
        assertEquals("POST", tc.getMethod());
        assertEquals("{{baseUrl}}/api/users?ref={{referralCode}}", tc.getUrl());
        assertEquals("application/json", tc.getHeaders().get("Content-Type"));
        assertFalse(tc.getHeaders().containsKey("X-Disabled"), "Disabled header should be omitted");

        assertNotNull(tc.getAuth());
        assertEquals(Auth.AuthType.BEARER, tc.getAuth().getType());
        assertEquals("{{authToken}}", tc.getAuth().getBearerToken());

        assertEquals(201, tc.getExpectedStatus());
        assertEquals("{\"id\": 101, \"status\": \"created\"}", tc.getExpectedBodyTemplate());

        // Check unresolved variables: referralCode and email were in requests but NOT in companionEnv
        assertTrue(result.getUnresolvedVariables().contains("referralCode"));
        assertTrue(result.getUnresolvedVariables().contains("email"));
        assertFalse(result.getUnresolvedVariables().contains("baseUrl"));
        assertFalse(result.getUnresolvedVariables().contains("authToken"));
    }
}
