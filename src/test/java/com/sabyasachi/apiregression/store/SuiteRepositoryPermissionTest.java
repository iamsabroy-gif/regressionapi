package com.sabyasachi.apiregression.store;

import com.sabyasachi.apiregression.model.TestSuite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class SuiteRepositoryPermissionTest {

    @Test
    void s2_suiteFileMode0600(@TempDir Path tempDir) throws IOException {
        SuiteRepository repo = new SuiteRepository();
        TestSuite suite = new TestSuite("Secret Suite");

        Path suitePath = tempDir.resolve("suite.json");
        repo.save(suite, suitePath);

        assertTrue(Files.exists(suitePath));

        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(suitePath);
            String permString = PosixFilePermissions.toString(perms);
            assertEquals("rw-------", permString, "POSIX permissions must be 0600 (rw-------)");
        } catch (UnsupportedOperationException ignored) {
            // Running on non-POSIX (Windows), skipped
        }
    }
}
