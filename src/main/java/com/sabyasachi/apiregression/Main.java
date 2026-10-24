package com.sabyasachi.apiregression;

import com.sabyasachi.apiregression.model.TestResult;
import com.sabyasachi.apiregression.model.TestSuite;
import com.sabyasachi.apiregression.runner.ReportWriter;
import com.sabyasachi.apiregression.runner.SuiteRunner;
import com.sabyasachi.apiregression.store.SuiteRepository;
import com.sabyasachi.apiregression.ui.MainFrame;

import com.sabyasachi.apiregression.payload.OverrideOptions;
import com.sabyasachi.apiregression.payload.OverrideReport;
import com.sabyasachi.apiregression.payload.ParameterOverrideService;
import com.sabyasachi.apiregression.payload.SheetSummary;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
                int code = runHeadlessSuite(args);
                if (code != 0) System.exit(code);
                return;
            } else if ("--apply-params".equalsIgnoreCase(command)) {
                int code = applyParamsCommand(args);
                if (code != 0) System.exit(code);
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
        System.out.println("  java -jar api-regression.jar --run <suite.json> [--params <file.xlsx>] [--html report.html] [--json report.json] [--timeout <s>] [--retries <n>] [--threads <n>]");
        System.out.println("  java -jar api-regression.jar --apply-params <suite.json> <file.xlsx> [--spec <spec>] [--dry-run] [--out <out.json>]");
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
            System.out.println("Validating specification: " + specFile.getName());
            com.sabyasachi.apiregression.spec.ProviderSpecLoader.LoadedSpec loaded =
                    com.sabyasachi.apiregression.spec.ProviderSpecLoader.load(specFile.toPath());
            io.swagger.v3.oas.models.OpenAPI spec = loaded.getOpenAPI();
            if (spec == null) {
                String err = loaded.getMessages().isEmpty() ? "Blocked by security rules" : String.join("; ", loaded.getMessages());
                throw new IOException("Failed to load specification: " + err);
            }
            System.out.println("  Format/Version: " + (loaded.getSpecVersion() != null ? loaded.getSpecVersion() : "OpenAPI 3.0"));
            if (spec.getInfo() != null && spec.getInfo().getTitle() != null) {
                System.out.println("  Title:          " + spec.getInfo().getTitle());
            }
            int pathCount = spec.getPaths() != null ? spec.getPaths().size() : 0;
            int opCount = 0;
            if (spec.getPaths() != null) {
                for (Map.Entry<String, io.swagger.v3.oas.models.PathItem> entry : spec.getPaths().entrySet()) {
                    String p = entry.getKey();
                    for (Map.Entry<io.swagger.v3.oas.models.PathItem.HttpMethod, io.swagger.v3.oas.models.Operation> opEntry : entry.getValue().readOperationsMap().entrySet()) {
                        opCount++;
                        int paramCount = opEntry.getValue().getParameters() != null ? opEntry.getValue().getParameters().size() : 0;
                        int propCount = 0;
                        if (opEntry.getValue().getRequestBody() != null && opEntry.getValue().getRequestBody().getContent() != null) {
                            var mediaType = opEntry.getValue().getRequestBody().getContent().get("application/json");
                            if (mediaType != null && mediaType.getSchema() != null && mediaType.getSchema().getProperties() != null) {
                                propCount = mediaType.getSchema().getProperties().size();
                            }
                        }
                        System.out.println("  - " + opEntry.getKey().name() + " " + p + " (params: " + paramCount + ", body props: " + propCount + ")");
                    }
                }
            }
            System.out.println("  Paths:          " + pathCount);
            System.out.println("  Operations:     " + opCount);
            if (!loaded.getMessages().isEmpty()) {
                System.out.println("  Warnings (" + loaded.getMessages().size() + "):");
                for (String w : loaded.getMessages()) {
                    System.out.println("    * " + w);
                }
            }
            System.out.println("Status: VALID specification.");
        } catch (com.sabyasachi.apiregression.payload.OverrideException ex) {
            System.err.println("Status: INVALID specification.");
            System.err.println("Reason [" + ex.getReasonCode() + "]: " + ex.getMessage());
            System.exit(1);
        } catch (Exception ex) {
            System.err.println("Status: INVALID specification.");
            System.err.println("Reason: " + ex.getMessage());
            System.exit(1);
        }
    }

    public static int runHeadlessSuite(String[] args) {
        if (args.length < 2) {
            System.err.println("Error: Please provide a suite JSON file path. Example: --run my_suite.json");
            return 1;
        }

        File suiteFile = new File(args[1]);
        if (!suiteFile.exists()) {
            System.err.println("Error: Suite file not found: " + suiteFile.getAbsolutePath());
            return 1;
        }

        String htmlOut = null;
        String jsonOut = null;
        String paramsPath = null;
        Integer timeoutSec = null;
        Integer retries = null;
        Integer threads = null;

        for (int i = 2; i < args.length; i++) {
            if ("--params".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                paramsPath = args[++i];
            } else if ("--html".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
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

        File paramsFile = null;
        if (paramsPath != null) {
            paramsFile = new File(paramsPath);
            if (!paramsFile.exists()) {
                System.err.println("Error: Params workbook not found: " + paramsFile.getAbsolutePath());
                return 1;
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

            // If --params specified, perform dryRun and apply in memory
            if (paramsFile != null) {
                ParameterOverrideService overrideService = new ParameterOverrideService();
                OverrideOptions overrideOptions = suite.getOverrideOptions() != null ? suite.getOverrideOptions() : new OverrideOptions();
                OverrideReport dryReport = overrideService.dryRun(suite, paramsFile.toPath(), null, overrideOptions);
                printOverrideSummary(dryReport, paramsFile.getName());

                int totalErrors = dryReport.getSheets().stream().mapToInt(SheetSummary::getErrors).sum();
                if (totalErrors > 0 && !overrideOptions.isApplyWithErrors()) {
                    System.err.println("Parameter override failed with errors. Aborting run.");
                    return 2;
                }

                overrideService.apply(suite, paramsFile.toPath(), null, overrideOptions);
                System.out.println("Applied parameter overrides. Suite now has " + suite.getTestCases().size() + " test cases.");
            }

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

            return failed > 0 ? 2 : 0;
        } catch (Exception e) {
            System.err.println("Execution failed: " + e.getMessage());
            e.printStackTrace();
            return 1;
        }
    }

    public static int applyParamsCommand(String[] args) {
        if (args.length < 3) {
            System.err.println("Error: Please provide suite and workbook file paths. Example: --apply-params suite.json workbook.xlsx");
            return 1;
        }

        File suiteFile = new File(args[1]);
        if (!suiteFile.exists()) {
            System.err.println("Error: Suite file not found: " + suiteFile.getAbsolutePath());
            return 1;
        }

        File xlsxFile = new File(args[2]);
        if (!xlsxFile.exists()) {
            System.err.println("Error: Workbook not found: " + xlsxFile.getAbsolutePath());
            return 1;
        }

        String specPath = null;
        boolean isDryRun = false;
        String outPath = null;

        for (int i = 3; i < args.length; i++) {
            if ("--spec".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                specPath = args[++i];
            } else if ("--dry-run".equalsIgnoreCase(args[i])) {
                isDryRun = true;
            } else if ("--out".equalsIgnoreCase(args[i]) && i + 1 < args.length) {
                outPath = args[++i];
            }
        }

        if (specPath != null && !new File(specPath).exists()) {
            System.err.println("Error: Spec file not found: " + specPath);
            return 1;
        }

        try {
            SuiteRepository repository = new SuiteRepository();
            TestSuite suite = repository.load(suiteFile.toPath());
            suite.setFilePath(suiteFile.toPath().toAbsolutePath().toString());

            ParameterOverrideService overrideService = new ParameterOverrideService();
            OverrideOptions options = suite.getOverrideOptions() != null ? suite.getOverrideOptions() : new OverrideOptions();
            Path specP = specPath != null ? Path.of(specPath) : null;

            OverrideReport report;
            if (isDryRun) {
                report = overrideService.dryRun(suite, xlsxFile.toPath(), specP, options);
            } else {
                report = overrideService.apply(suite, xlsxFile.toPath(), specP, options);
            }
            printOverrideSummary(report, xlsxFile.getName());

            if (!isDryRun) {
                if (outPath != null && !outPath.isBlank()) {
                    repository.save(suite, Path.of(outPath));
                    System.out.println("Saved applied suite to: " + outPath);
                } else {
                    System.out.println("No --out specified; applied suite not written to disk. Use --out <file.json> to save.");
                }
            }

            int totalErrors = report.getSheets().stream().mapToInt(SheetSummary::getErrors).sum();
            return totalErrors > 0 ? 2 : 0;
        } catch (Exception e) {
            System.err.println("Apply params failed: " + e.getMessage());
            return 1;
        }
    }

    private static void printOverrideSummary(OverrideReport report, String workbookName) {
        System.out.println("--- Excel Overrides: " + workbookName + " ---");
        System.out.printf("%-28s %-24s %-8s %4s %4s %4s %5s%n",
                "Sheet / Run", "Target", "Status", "Mod", "Add", "Err", "Warn");
        System.out.println("-".repeat(81));
        int applied = 0, partial = 0, errors = 0, warnings = 0;
        for (SheetSummary s : report.getSheets()) {
            if ("APPLIED".equalsIgnoreCase(s.getStatus())) applied++;
            else if ("PARTIAL".equalsIgnoreCase(s.getStatus())) partial++;
            else errors++;
            warnings += s.getWarnings();
            System.out.printf("%-28s %-24s %-8s %4d %4d %4d %5d%n",
                    truncate(s.getSheet(), 28),
                    truncate(s.getTargetTestCase(), 24),
                    s.getStatus(),
                    s.getModified(),
                    s.getAdded(),
                    s.getErrors(),
                    s.getWarnings());
        }
        System.out.println("-".repeat(81));
        System.out.printf("Applied: %d  Partial: %d  Errors: %d  Warnings: %d%n%n", applied, partial, errors, warnings);
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 3) + "...";
    }
}
