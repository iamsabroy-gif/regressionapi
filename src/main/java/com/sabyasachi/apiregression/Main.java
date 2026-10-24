package com.sabyasachi.apiregression;

import com.sabyasachi.apiregression.model.TestResult;
import com.sabyasachi.apiregression.model.TestSuite;
import com.sabyasachi.apiregression.runner.ReportWriter;
import com.sabyasachi.apiregression.runner.SuiteRunner;
import com.sabyasachi.apiregression.store.SuiteRepository;
import com.sabyasachi.apiregression.ui.MainFrame;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;

public class Main {

    public static final String VERSION = "1.0.0";

    public static void main(String[] args) {
        if (args.length > 0) {
            String command = args[0];
            if ("--version".equalsIgnoreCase(command) || "-v".equalsIgnoreCase(command)) {
                System.out.println("API Regression Tool v" + VERSION + " (Phase 1)");
                return;
            } else if ("--help".equalsIgnoreCase(command) || "-h".equalsIgnoreCase(command)) {
                printHelp();
                return;
            } else if ("--run".equalsIgnoreCase(command)) {
                runHeadlessSuite(args);
                return;
            } else if ("--check-spec".equalsIgnoreCase(command)) {
                checkSpecDiagnostic(args);
                return;
            }
        }

        // Default: Launch Swing Desktop GUI
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }

    private static void printHelp() {
        System.out.println("API Regression Tool v" + VERSION);
        System.out.println("Usage:");
        System.out.println("  java -jar api-regression.jar               # Launch desktop Swing GUI");
        System.out.println("  java -jar api-regression.jar --version     # Display version");
        System.out.println("  java -jar api-regression.jar --check-spec <spec>          # Validate OpenAPI/Swagger spec");
        System.out.println("  java -jar api-regression.jar --run <suite.json> [--html report.html] [--json report.json]");
    }

    private static void checkSpecDiagnostic(String[] args) {
        if (args.length < 2) {
            System.err.println("Error: Please provide a specification file path. Example: --check-spec spec.json");
            System.exit(1);
        }
        File specFile = new File(args[1]);
        if (!specFile.exists()) {
            System.err.println("Error: Spec file not found: " + specFile.getAbsolutePath());
            System.exit(1);
        }
        try {
            System.out.println("Validating OpenAPI / Swagger specification: " + specFile.getName());
            io.swagger.v3.oas.models.OpenAPI spec = com.sabyasachi.apiregression.spec.ProviderSpecLoader.loadOpenAPI(specFile.toPath());
            System.out.println("  Spec version: " + (spec.getOpenapi() != null ? "OpenAPI " + spec.getOpenapi() : "Swagger 2.0 (converted to OAS3)"));
            System.out.println("  Title:        " + (spec.getInfo() != null ? spec.getInfo().getTitle() : "N/A"));
            System.out.println("  Version:      " + (spec.getInfo() != null ? spec.getInfo().getVersion() : "N/A"));
            int pathCount = spec.getPaths() != null ? spec.getPaths().size() : 0;
            int opCount = 0;
            if (spec.getPaths() != null) {
                for (var item : spec.getPaths().values()) {
                    opCount += item.readOperations().size();
                }
            }
            System.out.println("  Paths:        " + pathCount);
            System.out.println("  Operations:   " + opCount);
            System.out.println("Status: VALID specification.");
        } catch (Exception ex) {
            System.err.println("Status: INVALID specification.");
            System.err.println("Reason: " + ex.getMessage());
            System.exit(1);
        }
    }

    private static void runHeadlessSuite(String[] args) {
        if (args.length < 2) {
            System.err.println("Error: Please provide a suite JSON file path. Example: --run my_suite.json");
            System.exit(1);
        }

        File suiteFile = new File(args[1]);
        if (!suiteFile.exists()) {
            System.err.println("Error: Suite file not found: " + suiteFile.getAbsolutePath());
            System.exit(1);
        }

        String htmlOut = null;
        String jsonOut = null;
        Integer timeoutSec = null;
        Integer retries = null;
        Integer threads = null;

        for (int i = 2; i < args.length; i++) {
            if ("--html".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                htmlOut = args[++i];
            } else if ("--json".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                jsonOut = args[++i];
            } else if ("--timeout".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                timeoutSec = Integer.parseInt(args[++i]);
            } else if ("--retries".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                retries = Integer.parseInt(args[++i]);
            } else if ("--threads".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                threads = Integer.parseInt(args[++i]);
            }
        }

        try {
            SuiteRepository repository = new SuiteRepository();
            TestSuite suite = repository.load(suiteFile.toPath());
            suite.setFilePath(suiteFile.toPath().toAbsolutePath().toString());
            if (timeoutSec != null) suite.getRunOptions().setTimeoutSeconds(timeoutSec);
            if (retries != null) suite.getRunOptions().setRetryCount(retries);
            if (threads != null) suite.getRunOptions().setThreads(threads);
            System.out.println("Loaded suite: " + suite.getName() + " (" + suite.getTestCases().size() + " test cases)");

            SuiteRunner runner = new SuiteRunner();
            List<TestResult> results = runner.run(suite);

            int passed = 0;
            for (TestResult r : results) {
                if (r.isPassed()) passed++;
                String role = r.isTokenProvider() ? "[TOKEN] " : "";
                System.out.printf("  %s%-5s %-40s -> %s (HTTP %d, %d ms)%n",
                        role,
                        r.getMethod() != null ? r.getMethod() : "GET",
                        r.getTestCaseName(),
                        r.isPassed() ? "PASS" : "FAIL",
                        r.getActualStatus(),
                        r.getLatencyMs());
                if (!r.isPassed() && r.getDiffSummary() != null) {
                    System.out.println("      Diff: " + r.getDiffSummary().replace("\n", "\n      "));
                }
            }

            int failed = results.size() - passed;
            System.out.printf("%nExecution finished: %d total, %d passed, %d failed.%n", results.size(), passed, failed);

            ReportWriter writer = new ReportWriter();
            if (htmlOut != null) {
                writer.writeHtml(results, Path.of(htmlOut));
                System.out.println("Saved HTML report to: " + htmlOut);
            }
            if (jsonOut != null) {
                writer.writeJson(results, Path.of(jsonOut));
                System.out.println("Saved JSON report to: " + jsonOut);
            }

            if (failed > 0) {
                System.exit(2);
            }
        } catch (Exception e) {
            System.err.println("Execution failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
