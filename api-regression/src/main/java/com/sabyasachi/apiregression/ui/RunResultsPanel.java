package com.sabyasachi.apiregression.ui;

import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestResult;
import com.sabyasachi.apiregression.model.TestSuite;
import com.sabyasachi.apiregression.runner.ReportWriter;
import com.sabyasachi.apiregression.runner.SuiteRunner;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class RunResultsPanel extends JPanel {

    private final Frame owner;
    private final Supplier<TestSuite> suiteSupplier;

    private JButton runSuiteBtn;
    private JButton exportHtmlBtn;
    private JButton exportJsonBtn;
    private JProgressBar progressBar;
    private JLabel summaryLabel;

    private DefaultTableModel resultsTableModel;
    private JTable resultsTable;

    private JTextArea diffSummaryArea;
    private JTextArea actualBodyArea;
    private JTextArea headersArea;
    private JTextArea requestInfoArea;

    private List<TestResult> currentResults = new ArrayList<>();
    private SwingWorker<List<TestResult>, Object> runningWorker;

    public RunResultsPanel(Frame owner, Supplier<TestSuite> suiteSupplier) {
        this.owner = owner;
        this.suiteSupplier = suiteSupplier;

        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Top Toolbar
        JPanel topToolbar = new JPanel(new BorderLayout(8, 8));
        topToolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Suite Execution"),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)
        ));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        runSuiteBtn = new JButton("▶ Run Test Suite");
        runSuiteBtn.setFont(runSuiteBtn.getFont().deriveFont(Font.BOLD, 13f));
        runSuiteBtn.addActionListener(e -> executeSuite());

        exportHtmlBtn = new JButton("Export HTML Report...");
        exportHtmlBtn.setEnabled(false);
        exportHtmlBtn.addActionListener(e -> exportHtmlReport());

        exportJsonBtn = new JButton("Export JSON Report...");
        exportJsonBtn.setEnabled(false);
        exportJsonBtn.addActionListener(e -> exportJsonReport());

        btnRow.add(runSuiteBtn);
        btnRow.add(exportHtmlBtn);
        btnRow.add(exportJsonBtn);
        topToolbar.add(btnRow, BorderLayout.WEST);

        JPanel progressPanel = new JPanel(new BorderLayout(6, 6));
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setVisible(false);
        summaryLabel = new JLabel("Suite ready to run.");
        summaryLabel.setFont(summaryLabel.getFont().deriveFont(Font.BOLD));
        progressPanel.add(progressBar, BorderLayout.NORTH);
        progressPanel.add(summaryLabel, BorderLayout.CENTER);
        topToolbar.add(progressPanel, BorderLayout.EAST);

        add(topToolbar, BorderLayout.NORTH);

        // Center: Split Pane (Results Table on Top, Details Tabs on Bottom)
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setResizeWeight(0.5);

        // Results Table
        String[] columns = {"#", "Status", "Role", "Override", "Method", "Name", "Category", "Status Code", "Latency (ms)"};
        resultsTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        resultsTable = new JTable(resultsTableModel);
        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsTable.setRowHeight(24);

        // Custom Cell Renderer for Status, Role, and Override
        resultsTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    if (column == 1) { // Status column
                        String status = String.valueOf(value);
                        if ("PASS".equals(status)) {
                            c.setForeground(new Color(16, 140, 70));
                            setFont(getFont().deriveFont(Font.BOLD));
                        } else if ("FAIL".equals(status)) {
                            c.setForeground(new Color(220, 38, 38));
                            setFont(getFont().deriveFont(Font.BOLD));
                        }
                    } else if (column == 2) { // Role column
                        String role = String.valueOf(value);
                        if ("TOKEN".equals(role)) {
                            c.setForeground(new Color(202, 100, 0));
                            setFont(getFont().deriveFont(Font.BOLD));
                        } else {
                            c.setForeground(table.getForeground());
                        }
                    } else if (column == 3) { // Override column
                        String ov = String.valueOf(value);
                        if (ov.startsWith("PARTIAL")) {
                            c.setForeground(new Color(217, 119, 6));
                            setFont(getFont().deriveFont(Font.BOLD));
                        } else if ("APPLIED".equals(ov)) {
                            c.setForeground(new Color(37, 99, 235));
                            setFont(getFont().deriveFont(Font.BOLD));
                        } else {
                            c.setForeground(table.getForeground());
                        }
                    } else {
                        c.setForeground(table.getForeground());
                    }
                }
                return c;
            }
        });

        resultsTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int selected = resultsTable.getSelectedRow();
                displayResultDetails(selected);
            }
        });

        JPanel tablePanel = new JPanel(new BorderLayout());
        tablePanel.setBorder(BorderFactory.createTitledBorder("Execution Results"));
        tablePanel.add(new JScrollPane(resultsTable), BorderLayout.CENTER);
        splitPane.setTopComponent(tablePanel);

        // Details Tabs
        JTabbedPane detailsTabs = new JTabbedPane();
        detailsTabs.setBorder(BorderFactory.createTitledBorder("Test Execution Details"));

        diffSummaryArea = createMonospacedArea();
        detailsTabs.addTab("Diff & Summary", new JScrollPane(diffSummaryArea));

        actualBodyArea = createMonospacedArea();
        detailsTabs.addTab("Actual Response Body", new JScrollPane(actualBodyArea));

        headersArea = createMonospacedArea();
        detailsTabs.addTab("Response Headers", new JScrollPane(headersArea));

        requestInfoArea = createMonospacedArea();
        detailsTabs.addTab("Request & URL Info", new JScrollPane(requestInfoArea));

        splitPane.setBottomComponent(detailsTabs);
        add(splitPane, BorderLayout.CENTER);
    }

    private JTextArea createMonospacedArea() {
        JTextArea area = new JTextArea();
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBackground(new Color(250, 250, 252));
        return area;
    }

    public void executeSuite() {
        TestSuite suite = suiteSupplier.get();
        if (suite == null || suite.getTestCases() == null || suite.getTestCases().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Suite has no test cases to run. Please add or import test cases first.",
                    "Empty Suite", JOptionPane.WARNING_MESSAGE);
            return;
        }

        runSuiteBtn.setEnabled(false);
        exportHtmlBtn.setEnabled(false);
        exportJsonBtn.setEnabled(false);
        resultsTableModel.setRowCount(0);
        currentResults.clear();
        clearDetailAreas();

        int totalCount = suite.getTestCases().size();
        progressBar.setMaximum(totalCount);
        progressBar.setValue(0);
        progressBar.setString("0 / " + totalCount);
        progressBar.setVisible(true);
        summaryLabel.setText("Running suite: " + suite.getName() + "...");

        SuiteRunner runner = new SuiteRunner();

        runningWorker = new SwingWorker<>() {
            @Override
            protected List<TestResult> doInBackground() {
                return runner.run(suite, new SuiteRunner.SuiteRunListener() {
                    @Override
                    public void onTestCompleted(int currentIndex, int total, TestResult result) {
                        SwingUtilities.invokeLater(() -> {
                            currentResults.add(result);
                            addResultRow(currentResults.size(), result);
                            progressBar.setValue(currentIndex);
                            progressBar.setString(currentIndex + " / " + total);
                            summaryLabel.setText("Executed: " + result.getTestCaseName());
                        });
                    }
                });
            }

            @Override
            protected void done() {
                try {
                    List<TestResult> finalResults = get();
                    currentResults = finalResults;

                    int passed = 0;
                    for (TestResult r : finalResults) {
                        if (r.isPassed()) passed++;
                    }
                    int failed = finalResults.size() - passed;
                    double rate = finalResults.size() > 0 ? (passed * 100.0 / finalResults.size()) : 0;

                    summaryLabel.setText(String.format("Completed: %d tests | Passed: %d | Failed: %d (%.1f%%)",
                            finalResults.size(), passed, failed, rate));
                    if (failed > 0) {
                        summaryLabel.setForeground(new Color(220, 38, 38));
                    } else {
                        summaryLabel.setForeground(new Color(16, 140, 70));
                    }

                    exportHtmlBtn.setEnabled(!finalResults.isEmpty());
                    exportJsonBtn.setEnabled(!finalResults.isEmpty());

                    if (!finalResults.isEmpty()) {
                        resultsTable.setRowSelectionInterval(0, 0);
                    }
                } catch (Exception e) {
                    summaryLabel.setText("Suite execution failed: " + e.getMessage());
                    summaryLabel.setForeground(Color.RED);
                } finally {
                    runSuiteBtn.setEnabled(true);
                }
            }
        };

        runningWorker.execute();
    }

    private void addResultRow(int index, TestResult r) {
        String status = r.isPassed() ? "PASS" : "FAIL";
        String role = r.isTokenProvider() ? "TOKEN" : "STANDARD";
        String httpStatus = r.getActualStatus() > 0 ? String.valueOf(r.getActualStatus()) : "ERR";
        if (r.getExpectedStatus() > 0 && r.getExpectedStatus() != r.getActualStatus() && !r.isTokenProvider()) {
            httpStatus += " (exp: " + r.getExpectedStatus() + ")";
        }

        String overrideBadge = "-";
        TestSuite suite = suiteSupplier != null ? suiteSupplier.get() : null;
        if (suite != null && suite.getTestCases() != null) {
            for (TestCase tc : suite.getTestCases()) {
                if (r.getTestCaseName() != null && r.getTestCaseName().equals(tc.getName())) {
                    if (tc.getLastOverride() != null) {
                        if (tc.getLastOverride().getErrors() > 0) {
                            overrideBadge = "PARTIAL (" + tc.getLastOverride().getErrors() + " err)";
                        } else {
                            overrideBadge = "APPLIED";
                        }
                    } else if (tc.getBaseline() != null) {
                        overrideBadge = "BASELINE";
                    }
                    break;
                }
            }
        }

        resultsTableModel.addRow(new Object[]{
                index,
                status,
                role,
                overrideBadge,
                r.getMethod() != null ? r.getMethod() : "GET",
                r.getTestCaseName(),
                r.getCategory() != null ? r.getCategory() : "",
                httpStatus,
                r.getLatencyMs()
        });
    }

    private void displayResultDetails(int selectedRow) {
        if (selectedRow < 0 || selectedRow >= currentResults.size()) {
            clearDetailAreas();
            return;
        }

        TestResult r = currentResults.get(selectedRow);

        // Diff & Summary
        StringBuilder sbDiff = new StringBuilder();
        if (r.isTokenProvider()) {
            sbDiff.append("=== TOKEN PROVIDER API ===\n");
            if (r.getTokenExtracted() != null) {
                sbDiff.append("Status: Token successfully resolved.\n");
                sbDiff.append("Extracted Value: ").append(r.getTokenExtracted()).append("\n\n");
            }
        }

        if (r.getErrorMessage() != null) {
            sbDiff.append("ERROR:\n").append(r.getErrorMessage()).append("\n\n");
        }

        sbDiff.append("DIFF / ASSERTION SUMMARY:\n");
        sbDiff.append(r.getDiffSummary() != null ? r.getDiffSummary() : "No details.");
        diffSummaryArea.setText(sbDiff.toString());

        // Actual Body
        actualBodyArea.setText(r.getActualBody() != null ? r.getActualBody() : "(Empty response body)");

        // Headers
        StringBuilder sbHeaders = new StringBuilder();
        if (r.getActualHeaders() != null) {
            for (var entry : r.getActualHeaders().entrySet()) {
                sbHeaders.append(entry.getKey()).append(": ").append(String.join(", ", entry.getValue())).append("\n");
            }
        } else {
            sbHeaders.append("(No response headers recorded)");
        }
        headersArea.setText(sbHeaders.toString());

        // Request info
        StringBuilder sbReq = new StringBuilder();
        sbReq.append("Test Case: ").append(r.getTestCaseName()).append("\n");
        sbReq.append("Method: ").append(r.getMethod() != null ? r.getMethod() : "GET").append("\n");
        sbReq.append("Resolved Target URL: ").append(r.getResolvedUrl() != null ? r.getResolvedUrl() : "N/A").append("\n");
        sbReq.append("Expected Status: ").append(r.getExpectedStatus()).append("\n");
        sbReq.append("Actual Status: ").append(r.getActualStatus()).append("\n");
        sbReq.append("Latency: ").append(r.getLatencyMs()).append(" ms\n");
        sbReq.append("Executed At: ").append(r.getExecutedAt()).append("\n");

        TestSuite suite = suiteSupplier != null ? suiteSupplier.get() : null;
        if (suite != null && suite.getTestCases() != null) {
            for (TestCase tc : suite.getTestCases()) {
                if (r.getTestCaseName() != null && r.getTestCaseName().equals(tc.getName())) {
                    if (tc.getOperationId() != null) {
                        sbReq.append("Operation ID: ").append(tc.getOperationId()).append("\n");
                    }
                    if (tc.getLastOverride() != null) {
                        sbReq.append("Last Override: Sheet '").append(tc.getLastOverride().getSourceSheet())
                                .append("' from ").append(tc.getLastOverride().getSourceWorkbook()).append("\n");
                        sbReq.append("Override Stats: ").append(tc.getLastOverride().getModifications()).append(" modified, ")
                                .append(tc.getLastOverride().getAdditions()).append(" added, ")
                                .append(tc.getLastOverride().getErrors()).append(" errors\n");
                    }
                    if (tc.getBaseline() != null) {
                        sbReq.append("Baseline Snapshot: ").append(tc.getBaseline().getCapturedAt()).append("\n");
                    }
                    break;
                }
            }
        }
        requestInfoArea.setText(sbReq.toString());
    }

    private void clearDetailAreas() {
        diffSummaryArea.setText("");
        actualBodyArea.setText("");
        headersArea.setText("");
        requestInfoArea.setText("");
    }

    private void exportHtmlReport() {
        if (currentResults.isEmpty()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export HTML Test Report");
        chooser.setSelectedFile(new File("api-test-report.html"));
        chooser.setFileFilter(new FileNameExtensionFilter("HTML Report (*.html)", "html"));

        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File target = chooser.getSelectedFile();
            if (!target.getName().toLowerCase().endsWith(".html")) {
                target = new File(target.getParentFile(), target.getName() + ".html");
            }
            try {
                ReportWriter writer = new ReportWriter();
                writer.writeHtml(currentResults, target.toPath());
                JOptionPane.showMessageDialog(this, "HTML report exported successfully to:\n" + target.getAbsolutePath(),
                        "Export Successful", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to export HTML report: " + ex.getMessage(),
                        "Export Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void exportJsonReport() {
        if (currentResults.isEmpty()) return;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export JSON Test Report");
        chooser.setSelectedFile(new File("api-test-report.json"));
        chooser.setFileFilter(new FileNameExtensionFilter("JSON Report (*.json)", "json"));

        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File target = chooser.getSelectedFile();
            if (!target.getName().toLowerCase().endsWith(".json")) {
                target = new File(target.getParentFile(), target.getName() + ".json");
            }
            try {
                ReportWriter writer = new ReportWriter();
                writer.writeJson(currentResults, target.toPath());
                JOptionPane.showMessageDialog(this, "JSON report exported successfully to:\n" + target.getAbsolutePath(),
                        "Export Successful", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to export JSON report: " + ex.getMessage(),
                        "Export Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}
