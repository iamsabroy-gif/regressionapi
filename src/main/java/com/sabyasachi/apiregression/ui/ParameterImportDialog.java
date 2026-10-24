package com.sabyasachi.apiregression.ui;

import com.sabyasachi.apiregression.model.TestSuite;
import com.sabyasachi.apiregression.payload.ConstraintPolicy;
import com.sabyasachi.apiregression.payload.OverrideOptions;
import com.sabyasachi.apiregression.payload.OverrideReport;
import com.sabyasachi.apiregression.payload.ParameterOverrideService;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.nio.file.Path;

public class ParameterImportDialog extends JDialog {

    private final TestSuite suite;
    private final Runnable onAppliedCallback;

    private JTextField workbookField;
    private JTextField specField;
    private JComboBox<ConstraintPolicy> policyCombo;
    private JCheckBox skipHiddenCheck;
    private JCheckBox applyWithErrorsCheck;

    private JPanel centerCardPanel;
    private CardLayout centerCardLayout;
    private OverridePreviewPanel previewPanel;
    private boolean applied = false;

    public ParameterImportDialog(Frame owner, TestSuite suite, Runnable onAppliedCallback) {
        super(owner, "Import Parameter Override Sheet (.xlsx)", true);
        this.suite = suite;
        this.onAppliedCallback = onAppliedCallback;

        initUI();
        setSize(880, 680);
        setLocationRelativeTo(owner);
    }

    private void initUI() {
        setLayout(new BorderLayout(8, 8));

        centerCardLayout = new CardLayout();
        centerCardPanel = new JPanel(centerCardLayout);

        // Card 1: Configuration Form
        JPanel formCard = new JPanel();
        formCard.setLayout(new BoxLayout(formCard, BoxLayout.Y_AXIS));
        formCard.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        // 1. Workbook Picker
        JPanel wbPanel = new JPanel(new BorderLayout(8, 8));
        wbPanel.setBorder(BorderFactory.createTitledBorder("Excel Parameter Workbook (.xlsx) *"));
        workbookField = new JTextField();
        workbookField.setEditable(false);
        JButton browseWbBtn = new JButton("Browse...");
        browseWbBtn.addActionListener(e -> chooseWorkbook());
        wbPanel.add(workbookField, BorderLayout.CENTER);
        wbPanel.add(browseWbBtn, BorderLayout.EAST);
        formCard.add(wbPanel);
        formCard.add(Box.createVerticalStrut(10));

        // 2. Spec Picker
        JPanel spPanel = new JPanel(new BorderLayout(8, 8));
        spPanel.setBorder(BorderFactory.createTitledBorder("Provider OpenAPI / Swagger Spec (Optional for Gap-Fill)"));
        specField = new JTextField(suite.getSpecPath() != null ? suite.getSpecPath() : "");
        JButton browseSpBtn = new JButton("Browse...");
        browseSpBtn.addActionListener(e -> chooseSpec());
        spPanel.add(specField, BorderLayout.CENTER);
        spPanel.add(browseSpBtn, BorderLayout.EAST);
        formCard.add(spPanel);
        formCard.add(Box.createVerticalStrut(10));

        // 3. Options
        JPanel optPanel = new JPanel(new GridBagLayout());
        optPanel.setBorder(BorderFactory.createTitledBorder("Override & Validation Options"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        optPanel.add(new JLabel("Constraint Validation Policy:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0;
        policyCombo = new JComboBox<>(ConstraintPolicy.values());
        policyCombo.setSelectedItem(suite.getOverrideOptions().getConstraintPolicy());
        optPanel.add(policyCombo, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 2;
        skipHiddenCheck = new JCheckBox("Skip hidden sheets (recommended)", suite.getOverrideOptions().isSkipHiddenSheets());
        optPanel.add(skipHiddenCheck, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 2;
        applyWithErrorsCheck = new JCheckBox("Allow applying despite validation errors (unresolved rows will be skipped)", suite.getOverrideOptions().isApplyWithErrors());
        optPanel.add(applyWithErrorsCheck, gbc);

        formCard.add(optPanel);
        formCard.add(Box.createVerticalStrut(10));

        // Note about dry run
        JLabel noteLbl = new JLabel("<html><em>Note: A dry run is always performed first so you can review all MODIFIED, ADDED, SKIPPED, and ERROR rows before applying to your suite.</em></html>");
        noteLbl.setForeground(Color.DARK_GRAY);
        formCard.add(noteLbl);

        centerCardPanel.add(formCard, "FORM");
        add(centerCardPanel, BorderLayout.CENTER);

        // Bottom Action Buttons
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());

        JButton dryRunBtn = new JButton("Run Dry Run →");
        dryRunBtn.setFont(dryRunBtn.getFont().deriveFont(Font.BOLD));
        dryRunBtn.addActionListener(e -> executeDryRun());

        bottomBar.add(cancelBtn);
        bottomBar.add(dryRunBtn);
        add(bottomBar, BorderLayout.SOUTH);
    }

    private void chooseWorkbook() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Parameter Workbook (.xlsx)");
        chooser.setFileFilter(new FileNameExtensionFilter("Excel Workbook (*.xlsx)", "xlsx"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            workbookField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void chooseSpec() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Provider Specification (JSON, YAML, or Excel .xlsx)");
        chooser.setFileFilter(new FileNameExtensionFilter("Specification Files (*.json, *.yaml, *.yml, *.xlsx)", "json", "yaml", "yml", "xlsx"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            specField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void executeDryRun() {
        String wbPathStr = workbookField.getText().trim();
        if (wbPathStr.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select an .xlsx workbook file.", "Input Required", JOptionPane.WARNING_MESSAGE);
            return;
        }

        File wbFile = new File(wbPathStr);
        if (!wbFile.exists()) {
            JOptionPane.showMessageDialog(this, "Workbook file not found:\n" + wbPathStr, "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String specPathStr = specField.getText().trim();
        Path specPath = !specPathStr.isEmpty() ? Path.of(specPathStr) : null;
        if (!specPathStr.isEmpty()) {
            suite.setSpecPath(specPathStr);
        }

        OverrideOptions options = new OverrideOptions();
        options.setConstraintPolicy((ConstraintPolicy) policyCombo.getSelectedItem());
        options.setSkipHiddenSheets(skipHiddenCheck.isSelected());
        options.setApplyWithErrors(applyWithErrorsCheck.isSelected());

        try {
            ParameterOverrideService service = new ParameterOverrideService();
            OverrideReport report = service.dryRun(suite, wbFile.toPath(), specPath, options);

            previewPanel = new OverridePreviewPanel(report, () -> {
                try {
                    // Apply overrides
                    OverrideReport applyReport = service.apply(suite, wbFile.toPath(), specPath, options, report);
                    applied = true;
                    if (specPathStr != null && !specPathStr.isBlank()) {
                        suite.setSpecPath(specPathStr);
                    }
                    suite.setOverrideOptions(options);
                    if (onAppliedCallback != null) {
                        onAppliedCallback.run();
                    }
                    JOptionPane.showMessageDialog(this,
                            ApplyResultFormatter.format(applyReport),
                            "Overrides Applied", JOptionPane.INFORMATION_MESSAGE);
                    dispose();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Failed to apply overrides: " + ex.getMessage(), "Apply Error", JOptionPane.ERROR_MESSAGE);
                }
            }, options.isApplyWithErrors());

            centerCardPanel.add(previewPanel, "PREVIEW");
            centerCardLayout.show(centerCardPanel, "PREVIEW");
            setTitle("Parameter Overrides — Dry Run Preview");

        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Dry run failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public boolean isApplied() {
        return applied;
    }
}
