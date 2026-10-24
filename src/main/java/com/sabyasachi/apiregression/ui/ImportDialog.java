package com.sabyasachi.apiregression.ui;

import com.sabyasachi.apiregression.importer.PostmanCollectionImporter;
import com.sabyasachi.apiregression.importer.PostmanEnvironmentImporter;
import com.sabyasachi.apiregression.model.TestSuite;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.io.File;
import java.util.Map;

public class ImportDialog extends JDialog {

    private final Frame owner;
    private final TestSuite currentSuite;
    private boolean imported = false;

    private JTextField collectionPathField;
    private JTextField envPathField;
    private JLabel previewLabel;
    private JTextArea warningsArea;
    private JButton importBtn;

    private PostmanCollectionImporter.ImportResult parsedResult;
    private Map<String, String> parsedEnv;

    public ImportDialog(Frame owner, TestSuite currentSuite) {
        super(owner, "Import Postman Collection", true);
        this.owner = owner;
        this.currentSuite = currentSuite;

        initUI();
        setSize(650, 480);
        setLocationRelativeTo(owner);
    }

    private void initUI() {
        setLayout(new BorderLayout(12, 12));
        JPanel contentPanel = new JPanel();
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

        // Collection picker
        JPanel colPanel = new JPanel(new BorderLayout(8, 8));
        colPanel.setBorder(BorderFactory.createTitledBorder("Postman Collection File (v2.1 JSON) *"));
        collectionPathField = new JTextField();
        collectionPathField.setEditable(false);
        JButton browseColBtn = new JButton("Browse...");
        browseColBtn.addActionListener(e -> chooseCollectionFile());
        colPanel.add(collectionPathField, BorderLayout.CENTER);
        colPanel.add(browseColBtn, BorderLayout.EAST);
        contentPanel.add(colPanel);
        contentPanel.add(Box.createVerticalStrut(10));

        // Environment picker
        JPanel envPanel = new JPanel(new BorderLayout(8, 8));
        envPanel.setBorder(BorderFactory.createTitledBorder("Companion Environment File (Optional JSON)"));
        envPathField = new JTextField();
        envPathField.setEditable(false);
        JButton browseEnvBtn = new JButton("Browse...");
        browseEnvBtn.addActionListener(e -> chooseEnvironmentFile());
        envPanel.add(envPathField, BorderLayout.CENTER);
        envPanel.add(browseEnvBtn, BorderLayout.EAST);
        contentPanel.add(envPanel);
        contentPanel.add(Box.createVerticalStrut(10));

        // Preview & Warnings
        JPanel previewPanel = new JPanel(new BorderLayout(8, 8));
        previewPanel.setBorder(BorderFactory.createTitledBorder("Import Preview & Unresolved Variables"));
        previewLabel = new JLabel("Please select a Postman collection JSON file to preview.");
        previewLabel.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 4));
        previewPanel.add(previewLabel, BorderLayout.NORTH);

        warningsArea = new JTextArea(8, 40);
        warningsArea.setEditable(false);
        warningsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        warningsArea.setBackground(new Color(248, 249, 250));
        JScrollPane scrollWarnings = new JScrollPane(warningsArea);
        previewPanel.add(scrollWarnings, BorderLayout.CENTER);

        contentPanel.add(previewPanel);
        add(contentPanel, BorderLayout.CENTER);

        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 12));
        importBtn = new JButton("Import & Append");
        importBtn.setEnabled(false);
        importBtn.addActionListener(e -> doImport());

        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());

        buttonPanel.add(cancelBtn);
        buttonPanel.add(importBtn);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private void chooseCollectionFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Postman Collection JSON");
        chooser.setFileFilter(new FileNameExtensionFilter("Postman Collection JSON (*.json)", "json"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            collectionPathField.setText(file.getAbsolutePath());
            analyzeFiles();
        }
    }

    private void chooseEnvironmentFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Postman Environment JSON");
        chooser.setFileFilter(new FileNameExtensionFilter("Postman Environment JSON (*.json)", "json"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            envPathField.setText(file.getAbsolutePath());
            analyzeFiles();
        }
    }

    private void analyzeFiles() {
        String colPath = collectionPathField.getText().trim();
        if (colPath.isEmpty()) return;

        try {
            Map<String, String> envVars = null;
            String envPath = envPathField.getText().trim();
            if (!envPath.isEmpty()) {
                PostmanEnvironmentImporter envImporter = new PostmanEnvironmentImporter();
                envVars = envImporter.parse(new File(envPath).toPath());
                parsedEnv = envVars;
            }

            PostmanCollectionImporter colImporter = new PostmanCollectionImporter();
            parsedResult = colImporter.parse(new File(colPath).toPath(), envVars);

            int count = parsedResult.getTestCases().size();
            int envCount = (parsedEnv != null) ? parsedEnv.size() : 0;
            previewLabel.setText("<html><strong>Collection:</strong> " + parsedResult.getCollectionName()
                    + " &nbsp;|&nbsp; <strong>Requests found:</strong> " + count
                    + " &nbsp;|&nbsp; <strong>Env variables loaded:</strong> " + envCount + "</html>");

            StringBuilder sb = new StringBuilder();
            if (!parsedResult.getUnresolvedVariables().isEmpty()) {
                sb.append("WARNING: The following {{variables}} were found in requests but not defined in the environment:\n");
                for (String v : parsedResult.getUnresolvedVariables()) {
                    sb.append("  • {{").append(v).append("}}\n");
                }
                sb.append("\nYou can add values for these variables in the Suite Variables table after importing.\n");
            } else {
                sb.append("All variables resolved successfully or no variables required.\n");
            }

            warningsArea.setText(sb.toString());
            importBtn.setEnabled(count > 0);
        } catch (Exception ex) {
            previewLabel.setText("<html><font color='red'>Error parsing file: " + ex.getMessage() + "</font></html>");
            warningsArea.setText("Failed to parse collection file:\n" + ex.getMessage());
            importBtn.setEnabled(false);
        }
    }

    private void doImport() {
        if (parsedResult == null || parsedResult.getTestCases().isEmpty()) {
            return;
        }

        // Merge environment variables into suite
        if (parsedEnv != null) {
            for (Map.Entry<String, String> entry : parsedEnv.entrySet()) {
                currentSuite.getVariables().putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        // Append test cases
        currentSuite.getTestCases().addAll(parsedResult.getTestCases());
        imported = true;

        JOptionPane.showMessageDialog(this,
                "Successfully imported " + parsedResult.getTestCases().size() + " test cases from '"
                        + parsedResult.getCollectionName() + "'!",
                "Import Successful",
                JOptionPane.INFORMATION_MESSAGE);

        dispose();
    }

    public boolean isImported() {
        return imported;
    }
}
