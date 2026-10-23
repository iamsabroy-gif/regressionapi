package com.sabyasachi.apiregression.client;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class SubstitutorTest {

    @Test
    void testBasicSubstitution() {
        Map<String, String> vars = Map.of(
                "baseUrl", "https://api.example.com",
                "userId", "123",
                "env", "production"
        );

        String template = "{{baseUrl}}/v1/users/{{userId}}?env={{env}}";
        Substitutor.SubstitutionResult result = Substitutor.substitute(template, vars);

        assertEquals("https://api.example.com/v1/users/123?env=production", result.getResult());
        assertFalse(result.hasUnresolved());
        assertTrue(result.getUnresolved().isEmpty());
    }

    @Test
    void testUnresolvedVariablesCollected() {
        Map<String, String> vars = Map.of("baseUrl", "https://api.example.com");

        String template = "{{baseUrl}}/v1/users/{{userId}}?token={{token}}";
        Substitutor.SubstitutionResult result = Substitutor.substitute(template, vars);

        assertEquals("https://api.example.com/v1/users/{{userId}}?token={{token}}", result.getResult());
        assertTrue(result.hasUnresolved());
        assertEquals(List.of("userId", "token"), result.getUnresolved());
    }

    @Test
    void testSpecialRegexCharactersInValues() {
        Map<String, String> vars = Map.of(
                "token", "abc$123\\def",
                "regex", "foo(.*)bar"
        );

        String template = "{\"token\": \"{{token}}\", \"filter\": \"{{regex}}\"}";
        Substitutor.SubstitutionResult result = Substitutor.substitute(template, vars);

        assertEquals("{\"token\": \"abc$123\\def\", \"filter\": \"foo(.*)bar\"}", result.getResult());
    }

    @Test
    void testExtractPlaceholders() {
        String text = "Bearer {{authToken}} with {{apiKey}} and {{authToken}}";
        Set<String> placeholders = Substitutor.extractPlaceholders(text);

        assertEquals(Set.of("authToken", "apiKey"), placeholders);
    }
}
