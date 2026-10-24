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

    @Test
    void refFileModified_invalidatesCacheAndReloads(@TempDir Path tempDir) throws Exception {
        Path schemasDir = tempDir.resolve("schemas");
        Files.createDirectories(schemasDir);

        Path customerSchema = schemasDir.resolve("customer.yaml");
        Files.writeString(customerSchema, """
                type: object
                properties:
                  name:
                    type: string
                """);

        Path rootSpec = tempDir.resolve("spec.yaml");
        Files.writeString(rootSpec, """
                openapi: 3.0.3
                info:
                  title: Customer Spec
                  version: 1.0.0
                paths:
                  /customers:
                    post:
                      requestBody:
                        content:
                          application/json:
                            schema:
                              $ref: 'schemas/customer.yaml'
                      responses:
                        '200':
                          description: ok
                """);

        // 1. First load: schema contains only "name"
        ProviderSpecLoader.LoadedSpec loaded1 = ProviderSpecLoader.load(rootSpec);
        assertNotNull(loaded1.getOpenAPI());
        var schema1 = loaded1.getOpenAPI().getPaths().get("/customers").getPost().getRequestBody().getContent().get("application/json").getSchema();
        assertNotNull(schema1.getProperties().get("name"));
        assertNull(schema1.getProperties().get("email"));

        // Wait a moment so lastModified timestamp changes
        Thread.sleep(50);

        // 2. Modify ONLY schemas/customer.yaml (rootSpec is UNTOUCHED)
        Files.writeString(customerSchema, """
                type: object
                properties:
                  name:
                    type: string
                  email:
                    type: string
                """);
        Files.setLastModifiedTime(customerSchema, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1000));

        // 3. Second load: rootSpec must be re-parsed because scanned ref file changed
        ProviderSpecLoader.LoadedSpec loaded2 = ProviderSpecLoader.load(rootSpec);
        assertNotNull(loaded2.getOpenAPI());
        var schema2 = loaded2.getOpenAPI().getPaths().get("/customers").getPost().getRequestBody().getContent().get("application/json").getSchema();
        assertNotNull(schema2.getProperties().get("name"));
        assertNotNull(schema2.getProperties().get("email"), "Modified $ref file must be noticed by cache and reloaded");
    }

    @Test
    void blockedRefFixed_invalidatesCacheAndLoadsSuccessfully(@TempDir Path tempDir) throws Exception {
        Path schemasDir = tempDir.resolve("schemas");
        Files.createDirectories(schemasDir);

        Path childSchema = schemasDir.resolve("child.yaml");
        // Initially blocked due to remote ref
        Files.writeString(childSchema, """
                type: object
                properties:
                  bad:
                    $ref: 'http://127.0.0.1:9999/remote.yaml'
                """);

        Path rootSpec = tempDir.resolve("spec.yaml");
        Files.writeString(rootSpec, """
                openapi: 3.0.3
                info:
                  title: Blocked Ref Test
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
                                $ref: 'schemas/child.yaml'
                """);

        // 1. Initial load fails closed
        ProviderSpecLoader.LoadedSpec loaded1 = ProviderSpecLoader.load(rootSpec);
        assertNull(loaded1.getOpenAPI(), "Spec should be blocked due to child.yaml remote ref");

        Thread.sleep(50);

        // 2. Fix child.yaml so it is valid
        Files.writeString(childSchema, """
                type: object
                properties:
                  fixed:
                    type: string
                """);
        Files.setLastModifiedTime(childSchema, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 1000));

        // 3. Re-load: fixing the bad $ref file must be noticed and load successfully
        ProviderSpecLoader.LoadedSpec loaded2 = ProviderSpecLoader.load(rootSpec);
        assertNotNull(loaded2.getOpenAPI(), "Spec should load successfully after fixing bad $ref file");
    }

    @Test
    void missingRefFileCreated_invalidatesCacheAndLoadsSuccessfully(@TempDir Path tempDir) throws Exception {
        Path schemasDir = tempDir.resolve("schemas");
        Files.createDirectories(schemasDir);

        Path missingChild = schemasDir.resolve("missing.yaml");

        Path rootSpec = tempDir.resolve("spec.yaml");
        Files.writeString(rootSpec, """
                openapi: 3.0.3
                info:
                  title: Missing Ref Test
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
                                $ref: 'schemas/missing.yaml'
                """);

        // 1. Initial load fails due to missing file
        ProviderSpecLoader.LoadedSpec loaded1 = ProviderSpecLoader.load(rootSpec);
        assertNull(loaded1.getOpenAPI(), "Spec should fail when ref file is missing");
        assertTrue(loaded1.getMessages().stream().anyMatch(m -> m.contains("REF_UNRESOLVED")));

        Thread.sleep(50);

        // 2. Create the missing file
        Files.writeString(missingChild, """
                type: object
                properties:
                  created:
                    type: string
                """);

        // 3. Re-load: creation of missing $ref file must be noticed and load successfully
        ProviderSpecLoader.LoadedSpec loaded2 = ProviderSpecLoader.load(rootSpec);
        assertNotNull(loaded2.getOpenAPI(), "Spec should load successfully after creating missing $ref file");
    }
}
