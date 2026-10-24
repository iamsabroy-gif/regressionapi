package com.sabyasachi.apiregression.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class RedactorTest {

    @Test
    void testRedactUrl() {
        String url = "https://api.example.com/oauth/token?grant_type=client_credentials&client_id=123&client_secret=SUPER_SECRET_123&api_key=KEY999#ref";
        String redacted = Redactor.redactUrl(url);

        assertTrue(redacted.contains("grant_type=client_credentials"));
        assertTrue(redacted.contains("client_id=123"));
        assertTrue(redacted.contains("client_secret=***REDACTED***"));
        assertTrue(redacted.contains("api_key=***REDACTED***"));
        assertFalse(redacted.contains("SUPER_SECRET_123"));
        assertFalse(redacted.contains("KEY999"));
        assertTrue(redacted.endsWith("#ref"));
    }

    @Test
    void testRedactUrlBareParam() {
        String url = "https://api.example.com/test?token&safe=1";
        String redacted = Redactor.redactUrl(url);
        assertTrue(redacted.contains("token=***REDACTED***"));
        assertTrue(redacted.contains("safe=1"));
    }

    @Test
    void testRedactHeaders() {
        Map<String, List<String>> headers = Map.of(
                "Authorization", List.of("Bearer top_secret_jwt_token"),
                "X-Api-Key", List.of("secret_key_abc"),
                "Content-Type", List.of("application/json")
        );

        Map<String, List<String>> redacted = Redactor.redactHeaders(headers);
        assertEquals(List.of("***REDACTED***"), redacted.get("Authorization"));
        assertEquals(List.of("***REDACTED***"), redacted.get("X-Api-Key"));
        assertEquals(List.of("application/json"), redacted.get("Content-Type"));
    }

    @Test
    void testRedactBodyJson() {
        String json = """
                {
                    "username": "alice",
                    "password": "Password123!",
                    "tokens": {
                        "access_token": "jwt_token_xyz",
                        "refresh_token": "refresh_abc"
                    },
                    "client_secret": "sec_456"
                }
                """;

        String redacted = Redactor.redactBody(json, null);
        assertFalse(redacted.contains("Password123!"));
        assertFalse(redacted.contains("jwt_token_xyz"));
        assertFalse(redacted.contains("refresh_abc"));
        assertFalse(redacted.contains("sec_456"));
        assertTrue(redacted.contains("alice"));
        assertTrue(redacted.contains("***REDACTED***"));
    }

    @Test
    void testAtomicWriteAndPermissions0600(@TempDir Path tempDir) throws IOException {
        Path target = tempDir.resolve("secret_file.json");
        byte[] content = "{\"secret\": true}".getBytes();
        Redactor.writeAtomicWith0600(target, content);

        assertTrue(Files.exists(target));
        assertEquals(new String(content), Files.readString(target));

        // If on POSIX, assert rw-------
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(target);
            String permStr = PosixFilePermissions.toString(perms);
            assertEquals("rw-------", permStr);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX system (e.g. Windows)
        }
    }
}
