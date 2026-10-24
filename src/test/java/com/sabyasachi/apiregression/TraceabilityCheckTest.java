package com.sabyasachi.apiregression;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class TraceabilityCheckTest {

    private static final List<String> REQUIRED_FINDINGS = List.of(
            "H1", "H2", "H3", "H4", "H5", "H6",
            "M1", "M2", "M3", "M4", "M5", "M6", "M7", "M8", "M9", "M10", "M11",
            "LOW1", "LOW2",
            "S1", "S2", "S3", "S4", "S5", "S6"
    );

    @Test
    void allTwentyFiveFindingsHaveNamedTests() throws Exception {
        URI testClassesUri = TraceabilityCheckTest.class.getProtectionDomain().getCodeSource().getLocation().toURI();
        Path testClassesRoot = Path.of(testClassesUri);

        Set<String> testMethodNames = new HashSet<>();
        try (var stream = Files.walk(testClassesRoot)) {
            List<Path> classFiles = stream
                    .filter(p -> p.toString().endsWith(".class") && !p.toString().contains("$"))
                    .toList();

            for (Path classFile : classFiles) {
                Path relative = testClassesRoot.relativize(classFile);
                String className = relative.toString()
                        .replace(File.separatorChar, '.')
                        .replace(".class", "");
                if (!className.startsWith("com.sabyasachi.apiregression")) {
                    continue;
                }
                try {
                    Class<?> clazz = Class.forName(className);
                    for (Method m : clazz.getDeclaredMethods()) {
                        if (m.isAnnotationPresent(Test.class) || m.isAnnotationPresent(ParameterizedTest.class)) {
                            testMethodNames.add(m.getName().toLowerCase(Locale.ROOT));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        Map<String, List<String>> findingToTests = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();

        for (String finding : REQUIRED_FINDINGS) {
            String prefix = finding.toLowerCase(Locale.ROOT) + "_";
            List<String> matches = testMethodNames.stream()
                    .filter(name -> name.startsWith(prefix))
                    .sorted()
                    .toList();
            if (matches.isEmpty()) {
                missing.add(finding);
            } else {
                findingToTests.put(finding, matches);
            }
        }

        assertTrue(missing.isEmpty(),
                "Missing test coverage for findings: " + missing +
                ". All 25 findings (H1-H6, M1-M11, Low1-Low2, S1-S6) must have at least one test method prefixed with '<id>_'.");
    }
}
