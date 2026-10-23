package com.sabyasachi.apiregression.ui;

import com.sabyasachi.apiregression.client.Substitutor;
import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestSuite;
import com.sabyasachi.apiregression.store.SuiteRepository;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;
import java.util.*;

public class MainFrame extends JFrame {

    private final SuiteRepository suiteRepository = new SuiteRepository();
    private TestSuite currentSuite = new TestSuite("Default Suite");
    private Path currentFilePath = null;

    private JTabbedPane mainTabs;
    private SuiteEditorPanel editorPanel;
    private RunResultsPanel resultsPanel;
    private JLabel statusLabel;

    public MainFrame() {
        super("API Regression Tool — Phase 1");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1180, 820);
        setMinimumSize(new Dimension(800, 600));
        setLocationRelativeTo(null);

        initUI();
        updateTitle();
    }

    private void initUI() {
        setJMenuBar(createMenuBar());

        setLayout(new BorderLayout());

        // Main Tabbed Pane
        mainTabs = new JTabbedPane();
        editorPanel = new SuiteEditorPanel(this, currentSuite);
        resultsPanel = new RunResultsPanel(this, () -> editorPanel.getSuite());

        mainTabs.addTab("  Suite Editor  ", editorPanel);
        mainTabs.addTab("  Run & Results  ", resultsPanel);

        add(mainTabs, BorderLayout.CENTER);

        // Status bar
        JPanel statusBar = new JPanel(new BorderLayout(8, 4));
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(210, 210, 215)),
                BorderFactory.createEmptyBorder(4, 10, 4, 10)
        ));
        statusLabel = new JLabel("Ready. No file loaded.");
        statusLabel.setFont(statusLabel.getFont().deriveFont(12f));
        statusBar.add(statusLabel, BorderLayout.WEST);

        JLabel versionLabel = new JLabel("Phase 1 • Java 17+ / Swing");
        versionLabel.setFont(versionLabel.getFont().deriveFont(11f));
        versionLabel.setForeground(Color.GRAY);
        statusBar.add(versionLabel, BorderLayout.EAST);

        add(statusBar, BorderLayout.SOUTH);
    }

    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();

        // File Menu
        JMenu fileMenu = new JMenu("File");
        JMenuItem newSuiteItem = new JMenuItem("New Suite");
        newSuiteItem.setAccelerator(KeyStroke.getKeyStroke("ctrl N"));
        newSuiteItem.addActionListener(e -> newSuite());

        JMenuItem openSuiteItem = new JMenuItem("Open Suite...");
        openSuiteItem.setAccelerator(KeyStroke.getKeyStroke("ctrl O"));
        openSuiteItem.addActionListener(e -> openSuite());

        JMenuItem saveSuiteItem = new JMenuItem("Save Suite");
        saveSuiteItem.setAccelerator(KeyStroke.getKeyStroke("ctrl S"));
        saveSuiteItem.addActionListener(e -> saveSuite(false));

        JMenuItem saveAsItem = new JMenuItem("Save Suite As...");
        saveAsItem.addActionListener(e -> saveSuite(true));

        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> System.exit(0));

        fileMenu.add(newSuiteItem);
        fileMenu.add(openSuiteItem);
        fileMenu.addSeparator();
        fileMenu.add(saveSuiteItem);
        fileMenu.add(saveAsItem);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);
        bar.add(fileMenu);

        // Import Menu
        JMenu importMenu = new JMenu("Import");
        JMenuItem importPostmanItem = new JMenuItem("Import Postman Collection...");
        importPostmanItem.setAccelerator(KeyStroke.getKeyStroke("ctrl I"));
        importPostmanItem.addActionListener(e -> openPostmanImportDialog());
        importMenu.add(importPostmanItem);

        JMenuItem attachSpecItem = new JMenuItem("Attach Provider Spec (OpenAPI / Swagger)...");
        attachSpecItem.addActionListener(e -> attachProviderSpec());
        importMenu.add(attachSpecItem);

        JMenuItem importExcelItem = new JMenuItem("Import Parameter Sheet (.xlsx)...");
        importExcelItem.setAccelerator(KeyStroke.getKeyStroke("ctrl P"));
        importExcelItem.addActionListener(e -> openParameterImportDialog());
        importMenu.add(importExcelItem);
        bar.add(importMenu);

        // Run Menu
        JMenu runMenu = new JMenu("Run");
        JMenuItem runSuiteItem = new JMenuItem("Run Test Suite");
        runSuiteItem.setAccelerator(KeyStroke.getKeyStroke("ctrl R"));
        runSuiteItem.addActionListener(e -> {
            mainTabs.setSelectedComponent(resultsPanel);
            resultsPanel.executeSuite();
        });
        runMenu.add(runSuiteItem);
        bar.add(runMenu);

        // Tools Menu
        JMenu toolsMenu = new JMenu("Tools");
        JMenuItem validateItem = new JMenuItem("Validate {{variable}} Placeholders...");
        validateItem.addActionListener(e -> validatePlaceholders());
        toolsMenu.add(validateItem);

        toolsMenu.addSeparator();
        JMenuItem revertAllItem = new JMenuItem("Revert All TestCases to Baseline...");
        revertAllItem.addActionListener(e -> revertAllToBaseline());
        toolsMenu.add(revertAllItem);

        JMenuItem promoteAllItem = new JMenuItem("Promote All TestCases to Baseline...");
        promoteAllItem.addActionListener(e -> promoteAllToBaseline());
        toolsMenu.add(promoteAllItem);
        bar.add(toolsMenu);

        // Help Menu
        JMenu helpMenu = new JMenu("Help");
        JMenuItem aboutItem = new JMenuItem("About API Regression Tool");
        aboutItem.addActionListener(e -> showAboutDialog());
        helpMenu.add(aboutItem);
        bar.add(helpMenu);

        return bar;
    }

    private void updateTitle() {
        String fileName = (currentFilePath != null) ? currentFilePath.getFileName().toString() : "Untitled Suite";
        String specInfo = (currentSuite.getSpecPath() != null) ? " | Spec: " + new File(currentSuite.getSpecPath()).getName() : "";
        setTitle("API Regression Tool — [" + fileName + "] (" + currentSuite.getName() + ")" + specInfo);
        statusLabel.setText("File: " + (currentFilePath != null ? currentFilePath.toString() : "Unsaved") +
                " | Test Cases: " + currentSuite.getTestCases().size() + specInfo);
    }

    private void newSuite() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Create a new test suite? Any unsaved changes in the current suite will be lost.",
                "New Suite",
                JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            currentSuite = new TestSuite("New Test Suite");
            currentFilePath = null;
            editorPanel.setSuite(currentSuite);
            updateTitle();
        }
    }

    private void openSuite() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Open Test Suite JSON");
        chooser.setFileFilter(new FileNameExtensionFilter("Test Suite JSON (*.json)", "json"));

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            try {
                TestSuite loaded = suiteRepository.load(selected.toPath());
                currentSuite = loaded;
                currentFilePath = selected.toPath();
                editorPanel.setSuite(currentSuite);
                updateTitle();
                JOptionPane.showMessageDialog(this, "Suite loaded successfully with "
                                + currentSuite.getTestCases().size() + " test cases.",
                        "Suite Loaded", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to load suite file: " + ex.getMessage(),
                        "Open Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void saveSuite(boolean saveAs) {
        editorPanel.syncSuiteData();

        if (currentFilePath == null || saveAs) {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("Save Test Suite JSON");
            String defaultName = currentSuite.getName().replaceAll("[^a-zA-Z0-9_-]", "_") + ".json";
            chooser.setSelectedFile(new File(defaultName));
            chooser.setFileFilter(new FileNameExtensionFilter("Test Suite JSON (*.json)", "json"));

            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                File target = chooser.getSelectedFile();
                if (!target.getName().toLowerCase().endsWith(".json")) {
                    target = new File(target.getParentFile(), target.getName() + ".json");
                }
                currentFilePath = target.toPath();
            } else {
                return; // User cancelled
            }
        }

        try {
            suiteRepository.save(currentSuite, currentFilePath);
            updateTitle();
            JOptionPane.showMessageDialog(this, "Suite saved successfully to:\n" + currentFilePath,
                    "Saved", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Failed to save suite: " + ex.getMessage(),
                    "Save Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openPostmanImportDialog() {
        editorPanel.syncSuiteData();
        ImportDialog dialog = new ImportDialog(this, currentSuite);
        dialog.setVisible(true);

        if (dialog.isImported()) {
            editorPanel.loadSuiteData();
            updateTitle();
        }
    }

    private void openParameterImportDialog() {
        editorPanel.syncSuiteData();
        ParameterImportDialog dialog = new ParameterImportDialog(this, currentSuite, () -> {
            editorPanel.refreshTestCasesTable();
            updateTitle();
        });
        dialog.setVisible(true);
    }

    private void attachProviderSpec() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select OpenAPI 3.x / Swagger 2.0 Spec");
        chooser.setFileFilter(new FileNameExtensionFilter("OpenAPI / Swagger Spec (*.json, *.yaml, *.yml)", "json", "yaml", "yml"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                io.swagger.v3.oas.models.OpenAPI spec = com.sabyasachi.apiregression.spec.ProviderSpecLoader.loadOpenAPI(file.toPath());
                currentSuite.setSpecPath(file.getAbsolutePath());
                int opCount = 0;
                if (spec.getPaths() != null) {
                    for (var item : spec.getPaths().values()) {
                        opCount += item.readOperations().size();
                    }
                }
                JOptionPane.showMessageDialog(this,
                        "Successfully attached specification!\n" +
                        "File: " + file.getName() + "\n" +
                        "Version: " + (spec.getOpenapi() != null ? spec.getOpenapi() : "2.0") + "\n" +
                        "Operations: " + opCount,
                        "Spec Attached", JOptionPane.INFORMATION_MESSAGE);
                updateTitle();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this,
                        "Failed to parse provider spec:\n" + ex.getMessage(),
                        "Spec Parse Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void revertAllToBaseline() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Revert all test cases with a baseline snapshot to their baseline state?",
                "Revert All to Baseline",
                JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            int count = 0;
            for (TestCase tc : currentSuite.getTestCases()) {
                if (tc.getBaseline() != null) {
                    tc.revertToBaseline();
                    count++;
                }
            }
            editorPanel.refreshTestCasesTable();
            JOptionPane.showMessageDialog(this, "Reverted " + count + " test case(s) to baseline.", "Revert Complete", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void promoteAllToBaseline() {
        int confirm = JOptionPane.showConfirmDialog(this,
                "Snapshot the current state of all test cases as their new baseline?",
                "Promote All to Baseline",
                JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            for (TestCase tc : currentSuite.getTestCases()) {
                tc.promoteToBaseline();
            }
            editorPanel.refreshTestCasesTable();
            JOptionPane.showMessageDialog(this, "Promoted all " + currentSuite.getTestCases().size() + " test case(s) to baseline.", "Promote Complete", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void validatePlaceholders() {
        editorPanel.syncSuiteData();
        Map<String, String> vars = new HashMap<>(currentSuite.getVariables());
        if (currentSuite.getBaseUrl() != null && !currentSuite.getBaseUrl().isBlank()) {
            vars.put("baseUrl", currentSuite.getBaseUrl());
        }

        // Also add any token variables that will be provided during run
        for (TestCase tc : currentSuite.getTestCases()) {
            if (tc.isTokenProvider() && tc.getTokenVariableName() != null && !tc.getTokenVariableName().isBlank()) {
                vars.put(tc.getTokenVariableName(), "(provided by " + tc.getName() + ")");
            }
        }

        Map<String, Set<String>> missingPerCase = new LinkedHashMap<>();
        for (TestCase tc : currentSuite.getTestCases()) {
            Set<String> missing = new LinkedHashSet<>();
            checkMissing(tc.getUrl(), vars, missing);
            if (tc.getHeaders() != null) {
                for (var e : tc.getHeaders().entrySet()) {
                    checkMissing(e.getKey(), vars, missing);
                    checkMissing(e.getValue(), vars, missing);
                }
            }
            checkMissing(tc.getBodyTemplate(), vars, missing);
            if (!missing.isEmpty()) {
                missingPerCase.put(tc.getName(), missing);
            }
        }

        if (missingPerCase.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "All {{variable}} placeholders in all test cases are properly defined or covered by token providers!",
                    "Validation Passed", JOptionPane.INFORMATION_MESSAGE);
        } else {
            StringBuilder sb = new StringBuilder("The following test cases have undefined placeholders:\n\n");
            for (var entry : missingPerCase.entrySet()) {
                sb.append("• ").append(entry.getKey()).append(":\n");
                for (String m : entry.getValue()) {
                    sb.append("    - {{").append(m).append("}}\n");
                }
            }
            sb.append("\nPlease define them in the Suite Variables table.");

            JTextArea area = new JTextArea(sb.toString(), 15, 45);
            area.setEditable(false);
            JOptionPane.showMessageDialog(this, new JScrollPane(area), "Unresolved Variables Found", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void checkMissing(String input, Map<String, String> vars, Set<String> missing) {
        if (input == null || input.isEmpty()) return;
        Set<String> extracted = Substitutor.extractPlaceholders(input);
        for (String p : extracted) {
            if (!vars.containsKey(p)) {
                missing.add(p);
            }
        }
    }

    private void showAboutDialog() {
        String msg = "API Regression Tool — Phase 1\n" +
                "Version 1.0.0\n\n" +
                "Features:\n" +
                "• Standalone fat JAR, runs on JDK without external SDKs\n" +
                "• Postman Collection (v2.1) & Environment Importer\n" +
                "• Token Provider & Authman support with automatic injection\n" +
                "• Structural JSON diff & volatile field ignore paths\n" +
                "• HTML and JSON reporting\n\n" +
                "Created for fast, local office desktop API testing.";
        JOptionPane.showMessageDialog(this, msg, "About API Regression Tool", JOptionPane.INFORMATION_MESSAGE);
    }
}
