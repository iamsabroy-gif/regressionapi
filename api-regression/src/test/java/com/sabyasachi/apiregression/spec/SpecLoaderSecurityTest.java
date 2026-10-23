package com.sabyasachi.apiregression.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class SpecLoaderSecurityTest {

    @Test
    void s1_remoteRef_notFetched(@TempDir Path tempDir) throws IOException {
        Path specFile = tempDir.resolve("remote-ref.yaml");
        String yaml = """
                openapi: 3.0.3
                info:
                  title: Test Remote Ref
                  version: 1.0.0
                paths:
                  /test:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: 'http://127.0.0.1:9999/evil.yaml'
                """;
        Files.writeString(specFile, yaml);

        ProviderSpecLoader.LoadedSpec loaded = ProviderSpecLoader.load(specFile);
        assertNull(loaded.getOpenAPI(), "Spec load must fail closed when remote $ref is present");
        assertTrue(loaded.getMessages().stream().anyMatch(m -> m.contains("REMOTE_REF_BLOCKED")),
                "Expected REMOTE_REF_BLOCKED message in: " + loaded.getMessages());
    }

    @Test
    void s1_fileSchemeRef_blocked(@TempDir Path tempDir) throws IOException {
        Path specFile = tempDir.resolve("file-scheme.yaml");
        String yaml = """
                openapi: 3.0.3
                info:
                  title: Test File Scheme
                  version: 1.0.0
                paths:
                  /test:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: 'file:///etc/passwd'
                """;
        Files.writeString(specFile, yaml);

        ProviderSpecLoader.LoadedSpec loaded = ProviderSpecLoader.load(specFile);
        assertNull(loaded.getOpenAPI(), "Spec load must fail closed for file:// scheme");
        assertTrue(loaded.getMessages().stream().anyMatch(m -> m.contains("REMOTE_REF_BLOCKED")));
    }

    @Test
    void s1_relativeRefOutsideSpecDir_blocked(@TempDir Path tempDir) throws IOException {
        Path subDir = tempDir.resolve("specs");
        Files.createDirectories(subDir);

        Path secretFile = tempDir.resolve("secret.yaml");
        Files.writeString(secretFile, "secret: 12345");

        Path specFile = subDir.resolve("spec.yaml");
        String yaml = """
                openapi: 3.0.3
                info:
                  title: Escape Test
                  version: 1.0.0
                paths:
                  /test:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: '../secret.yaml'
                """;
        Files.writeString(specFile, yaml);

        ProviderSpecLoader.LoadedSpec loaded = ProviderSpecLoader.load(specFile);
        assertNull(loaded.getOpenAPI(), "Spec load must fail closed when $ref escapes directory");
        assertTrue(loaded.getMessages().stream().anyMatch(m -> m.contains("REF_OUTSIDE_DIR")));
    }

    @Test
    void s1_relativeRefInsideDir_works(@TempDir Path tempDir) throws IOException {
        Path schemasDir = tempDir.resolve("schemas");
        Files.createDirectories(schemasDir);

        Path userSchema = schemasDir.resolve("user.yaml");
        String userYaml = """
                type: object
                properties:
                  id:
                    type: integer
                  name:
                    type: string
                """;
        Files.writeString(userSchema, userYaml);

        Path specFile = tempDir.resolve("root-spec.yaml");
        String rootYaml = """
                openapi: 3.0.3
                info:
                  title: Split Spec
                  version: 1.0.0
                paths:
                  /users:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: 'schemas/user.yaml'
                """;
        Files.writeString(specFile, rootYaml);

        ProviderSpecLoader.LoadedSpec loaded = ProviderSpecLoader.load(specFile);
        assertNotNull(loaded.getOpenAPI(), "Legitimate internal relative ref must load successfully");
        assertEquals("3.0.3", loaded.getOpenAPI().getOpenapi());
    }

    @Test
    void s1_cycleAcrossFiles_terminates(@TempDir Path tempDir) throws IOException {
        Path fileA = tempDir.resolve("a.yaml");
        Path fileB = tempDir.resolve("b.yaml");

        Files.writeString(fileA, """
                openapi: 3.0.3
                info:
                  title: Cycle A
                  version: 1.0.0
                paths:
                  /a:
                    get:
                      responses:
                        '200':
                          description: ok
                          content:
                            application/json:
                              schema:
                                $ref: 'b.yaml'
                """);

        Files.writeString(fileB, """
                type: object
                properties:
                  next:
                    $ref: 'a.yaml'
                """);

        // RefGuard pre-scan must terminate and detect the cycle cleanly without infinite recursion
        RefGuard.GuardResult guardRes = RefGuard.scan(fileA);
        assertTrue(guardRes.isPassed());
    }
}
