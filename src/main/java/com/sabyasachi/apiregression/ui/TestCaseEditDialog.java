package com.sabyasachi.apiregression.ui;

import com.sabyasachi.apiregression.compare.IgnorePathHeuristic;
import com.sabyasachi.apiregression.model.Auth;
import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestSuite;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.*;
import java.util.List;

public class TestCaseEditDialog extends JDialog {

    private final TestCase testCase;
    private final TestSuite suite;
    private boolean saved = false;

    // Form fields
    private JTextField nameField;
    private JTextField categoryField;
    private JComboBox<String> methodCombo;
    private JTextField urlField;
    private JTextField operationIdField;
    private JLabel baselineStatusLabel;
    private JButton revertBaselineBtn;
    private JButton promoteBaselineBtn;

    // Headers
    private DefaultTableModel headersTableModel;
    private JTable headersTable;

    // Auth
    private JComboBox<Auth.AuthType> authTypeCombo;
    private JPanel authCardsPanel;
    private CardLayout authCardLayout;
    private JTextField bearerTokenField;
    private JTextField basicUserField;
    private JPasswordField basicPassField;
    private JTextField apiKeyKeyField;
    private JTextField apiKeyValueField;
    private JComboBox<String> apiKeyAddToCombo;

    // Body
    private JTextArea bodyTextArea;

    // Assertions
    private JSpinner expectedStatusSpinner;
    private JTextArea expectedBodyArea;
    private JTextArea ignorePathsArea;

    // Token provider fields
    private JCheckBox tokenProviderCheck;
    private JPanel tokenProviderPanel;
    private JTextField tokenJsonPathField;
    private JTextField tokenHeaderSourceField;
    private JTextField tokenVarNameField;

    // Token consumer fields
    private JPanel tokenConsumerPanel;
    private JComboBox<String> availableTokensCombo;
    private JButton injectTokenBtn;

    public TestCaseEditDialog(Dialog owner, TestCase testCase, TestSuite suite) {
        super(owner, testCase.getName() == null ? "Add TestCase" : "Edit TestCase: " + testCase.getName(), true);
        this.testCase = testCase;
        this.suite = suite;

        initUI();
        populateFields();
        setSize(820, 720);
        setLocationRelativeTo(owner);
    }

    public TestCaseEditDialog(Frame owner, TestCase testCase, TestSuite suite) {
        super(owner, testCase.getName() == null ? "Add TestCase" : "Edit TestCase: " + testCase.getName(), true);
        this.testCase = testCase;
        this.suite = suite;

        initUI();
        populateFields();
        setSize(820, 720);
        setLocationRelativeTo(owner);
    }

    private void initUI() {
        setLayout(new BorderLayout(8, 8));

        // Top: General Info
        JPanel generalPanel = new JPanel(new GridBagLayout());
        generalPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createEmptyBorder(12, 12, 4, 12),
                BorderFactory.createTitledBorder("General Request Configuration")
        ));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Row 0: Name & Category
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        generalPanel.add(new JLabel("Name:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.6;
        nameField = new JTextField();
        generalPanel.add(nameField, gbc);

        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 0;
        generalPanel.add(new JLabel("Category:"), gbc);
        gbc.gridx = 3; gbc.gridy = 0; gbc.weightx = 0.4;
        categoryField = new JTextField();
        generalPanel.add(categoryField, gbc);

        // Row 1: Method & URL
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        generalPanel.add(new JLabel("Method:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 0.2;
        methodCombo = new JComboBox<>(new String[]{"GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"});
        generalPanel.add(methodCombo, gbc);

        gbc.gridx = 2; gbc.gridy = 1; gbc.weightx = 0;
        generalPanel.add(new JLabel("URL:"), gbc);
        gbc.gridx = 3; gbc.gridy = 1; gbc.weightx = 0.8;
        urlField = new JTextField();
        generalPanel.add(urlField, gbc);

        // Row 2: Operation ID & Baseline
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        generalPanel.add(new JLabel("Operation ID:"), gbc);
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 0.4;
        operationIdField = new JTextField();
        operationIdField.setToolTipText("OpenAPI operationId for spec matching and overrides");
        generalPanel.add(operationIdField, gbc);

        gbc.gridx = 2; gbc.gridy = 2; gbc.weightx = 0;
        generalPanel.add(new JLabel("Baseline:"), gbc);
        gbc.gridx = 3; gbc.gridy = 2; gbc.weightx = 0.6;
        JPanel baselinePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        baselineStatusLabel = new JLabel("None");
        baselineStatusLabel.setFont(baselineStatusLabel.getFont().deriveFont(Font.ITALIC));
        revertBaselineBtn = new JButton("Revert");
        revertBaselineBtn.setToolTipText("Revert URL, Headers, and Body to baseline snapshot");
        revertBaselineBtn.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(this,
                    "Revert request to baseline snapshot?",
                    "Confirm Revert", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                testCase.revertToBaseline();
                populateFields();
            }
        });

        promoteBaselineBtn = new JButton("Promote to Baseline");
        promoteBaselineBtn.setToolTipText("Snapshot current URL, Headers, and Body as baseline");
        promoteBaselineBtn.addActionListener(e -> {
            testCase.setUrl(urlField.getText().trim());
            testCase.setBodyTemplate(bodyTextArea.getText());
            Map<String, String> hdrs = new LinkedHashMap<>();
            for (int r = 0; r < headersTableModel.getRowCount(); r++) {
                String k = (String) headersTableModel.getValueAt(r, 0);
                String v = (String) headersTableModel.getValueAt(r, 1);
                if (k != null && !k.trim().isEmpty()) hdrs.put(k.trim(), v != null ? v.trim() : "");
            }
            testCase.setHeaders(hdrs);
            testCase.promoteToBaseline();
            updateBaselineUi();
            JOptionPane.showMessageDialog(this, "Current configuration saved as baseline.", "Baseline Set", JOptionPane.INFORMATION_MESSAGE);
        });

        baselinePanel.add(baselineStatusLabel);
        baselinePanel.add(revertBaselineBtn);
        baselinePanel.add(promoteBaselineBtn);
        generalPanel.add(baselinePanel, gbc);

        add(generalPanel, BorderLayout.NORTH);

        // Center: Tabbed Pane for Headers, Auth, Body, Assertions, Token Configuration
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));

        tabbedPane.addTab("Headers", createHeadersTab());
        tabbedPane.addTab("Authorization", createAuthTab());
        tabbedPane.addTab("Body Template", createBodyTab());
        tabbedPane.addTab("Response Assertions", createAssertionsTab());
        tabbedPane.addTab("Token Provider (Authman)", createTokenTab());

        add(tabbedPane, BorderLayout.CENTER);

        // Bottom: Action Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        JButton saveBtn = new JButton("Save TestCase");
        saveBtn.addActionListener(e -> save());
        JButton cancelBtn = new JButton("Cancel");
        cancelBtn.addActionListener(e -> dispose());

        buttonPanel.add(cancelBtn);
        buttonPanel.add(saveBtn);
        add(buttonPanel, BorderLayout.SOUTH);
    }

    private JPanel createHeadersTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Header guidance banner
        JPanel infoPanel = new JPanel(new BorderLayout());
        infoPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(220, 224, 230)),
                BorderFactory.createEmptyBorder(0, 0, 8, 0)
        ));
        JLabel infoLabel = new JLabel("<html><b>HTTP Request Headers:</b> Configure headers sent with this request (e.g. <code>Authorization: Bearer {{token}}</code>).<br/>"
                + "Click <b>Add Header</b> to open the header entry dialog, or double-click any cell to edit directly.</html>");
        infoPanel.add(infoLabel, BorderLayout.CENTER);
        panel.add(infoPanel, BorderLayout.NORTH);

        headersTableModel = new DefaultTableModel(new String[]{"Header Name", "Header Value"}, 0);
        headersTable = new JTable(headersTableModel);
        headersTable.setRowHeight(24);
        headersTable.getColumnModel().getColumn(0).setPreferredWidth(220);
        headersTable.getColumnModel().getColumn(1).setPreferredWidth(450);
        // Commit a half-typed cell when focus leaves, otherwise Save reads the old (empty) value.
        headersTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        panel.add(new JScrollPane(headersTable), BorderLayout.CENTER);

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        JButton addBtn = new JButton("Add Header");
        addBtn.setToolTipText("Open dialog to specify header name and value");
        addBtn.addActionListener(e -> showAddOrEditHeaderDialog(null, "Authorization", ""));

        JButton addBlankBtn = new JButton("Add Blank Row");
        addBlankBtn.setToolTipText("Add an empty row to edit directly in the table");
        addBlankBtn.addActionListener(e -> addBlankHeaderRow());

        JButton editBtn = new JButton("Edit Selected");
        editBtn.setToolTipText("Edit selected header in dialog");
        editBtn.addActionListener(e -> {
            int selected = headersTable.getSelectedRow();
            if (selected < 0) {
                JOptionPane.showMessageDialog(this, "Please select a header row to edit.",
                        "No Header Selected", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String name = (String) headersTableModel.getValueAt(selected, 0);
            String val = (String) headersTableModel.getValueAt(selected, 1);
            showAddOrEditHeaderDialog(selected, name != null ? name : "", val != null ? val : "");
        });

        JButton removeBtn = new JButton("Remove Selected");
        removeBtn.addActionListener(e -> {
            int selected = headersTable.getSelectedRow();
            if (selected >= 0) headersTableModel.removeRow(selected);
        });

        btnPanel.add(addBtn);
        btnPanel.add(addBlankBtn);
        btnPanel.add(editBtn);
        btnPanel.add(removeBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);
        return panel;
    }

    private void addBlankHeaderRow() {
        headersTableModel.addRow(new Object[]{"", ""});
        int newRow = headersTableModel.getRowCount() - 1;
        headersTable.setRowSelectionInterval(newRow, newRow);
        headersTable.scrollRectToVisible(headersTable.getCellRect(newRow, 0, true));
        headersTable.editCellAt(newRow, 0);
        Component editor = headersTable.getEditorComponent();
        if (editor != null) {
            editor.requestFocusInWindow();
        }
    }

    private void showAddOrEditHeaderDialog(Integer rowIndex, String initialName, String initialValue) {
        boolean isEdit = (rowIndex != null);
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Top info banner in dialog
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2;
        JLabel banner = new JLabel("<html><b>" + (isEdit ? "Edit HTTP Request Header" : "Add HTTP Request Header") + "</b><br/>"
                + "Enter both the header name and value to send with this request.<br/>"
                + "Variables like <code>{{authToken}}</code> or <code>{{baseUrl}}</code> are supported in values.</html>");
        banner.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        form.add(banner, gbc);

        // Header Name
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0;
        form.add(new JLabel("Header Name: *"), gbc);

        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0;
        String[] commonHeaders = new String[]{
                "Authorization", "Content-Type", "Accept", "User-Agent",
                "X-API-Key", "X-Trace-Id", "Cache-Control", "Origin"
        };
        JComboBox<String> nameCombo = new JComboBox<>(commonHeaders);
        nameCombo.setEditable(true);
        if (initialName != null && !initialName.isBlank()) {
            nameCombo.setSelectedItem(initialName);
        }
        form.add(nameCombo, gbc);

        // Header Value
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        form.add(new JLabel("Header Value:"), gbc);

        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 1.0;
        JTextField valueField = new JTextField(initialValue != null ? initialValue : "", 26);
        valueField.setToolTipText("Enter header value. E.g. Bearer {{authToken}} or application/json");
        form.add(valueField, gbc);

        // Quick Token Insertion (if token variables exist in the suite)
        List<String> tokens = getAvailableTokenNames();
        if (!tokens.isEmpty()) {
            gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0;
            form.add(new JLabel("Token Helper:"), gbc);

            gbc.gridx = 1; gbc.gridy = 3; gbc.weightx = 1.0;
            JPanel tokenPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            JComboBox<String> tokenCombo = new JComboBox<>(tokens.toArray(new String[0]));
            JButton insertBearerBtn = new JButton("Use as Bearer Token");
            insertBearerBtn.setToolTipText("Sets Header Name to 'Authorization' and Value to 'Bearer {{" + tokenCombo.getSelectedItem() + "}}'");
            insertBearerBtn.addActionListener(e -> {
                nameCombo.setSelectedItem("Authorization");
                valueField.setText("Bearer {{" + tokenCombo.getSelectedItem() + "}}");
            });
            tokenPanel.add(new JLabel("Token:"));
            tokenPanel.add(tokenCombo);
            tokenPanel.add(insertBearerBtn);
            form.add(tokenPanel, gbc);
        }

        // Auto-suggest value when standard header name is chosen and value is empty
        nameCombo.addActionListener(e -> {
            String selected = (String) nameCombo.getSelectedItem();
            if (valueField.getText().trim().isEmpty() && selected != null) {
                if ("Content-Type".equalsIgnoreCase(selected) || "Accept".equalsIgnoreCase(selected)) {
                    valueField.setText("application/json");
                } else if ("Authorization".equalsIgnoreCase(selected) && !tokens.isEmpty()) {
                    valueField.setText("Bearer {{" + tokens.get(0) + "}}");
                }
            }
        });

        int option = JOptionPane.showConfirmDialog(
                this,
                form,
                isEdit ? "Edit Request Header" : "Add Request Header",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE
        );

        if (option == JOptionPane.OK_OPTION) {
            String name = (String) nameCombo.getSelectedItem();
            if (name == null || name.trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Header Name cannot be empty.", "Validation Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            String value = valueField.getText().trim();
            if (isEdit) {
                headersTableModel.setValueAt(name.trim(), rowIndex, 0);
                headersTableModel.setValueAt(value, rowIndex, 1);
            } else {
                headersTableModel.addRow(new Object[]{name.trim(), value});
                int newRow = headersTableModel.getRowCount() - 1;
                headersTable.setRowSelectionInterval(newRow, newRow);
                headersTable.scrollRectToVisible(headersTable.getCellRect(newRow, 0, true));
            }
        }
    }

    private JPanel createAuthTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        topPanel.add(new JLabel("Auth Type:"));
        authTypeCombo = new JComboBox<>(Auth.AuthType.values());
        authTypeCombo.addActionListener(e -> {
            Auth.AuthType type = (Auth.AuthType) authTypeCombo.getSelectedItem();
            authCardLayout.show(authCardsPanel, type != null ? type.name() : Auth.AuthType.NONE.name());
        });
        topPanel.add(authTypeCombo);
        panel.add(topPanel, BorderLayout.NORTH);

        authCardLayout = new CardLayout();
        authCardsPanel = new JPanel(authCardLayout);

        // NONE Card
        JPanel noneCard = new JPanel(new FlowLayout(FlowLayout.LEFT));
        noneCard.add(new JLabel("No authorization configured for this request."));
        authCardsPanel.add(noneCard, Auth.AuthType.NONE.name());

        // BEARER Card
        JPanel bearerCard = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        bearerCard.add(new JLabel("Bearer Token:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0;
        bearerTokenField = new JTextField();
        bearerCard.add(bearerTokenField, gbc);
        authCardsPanel.add(bearerCard, Auth.AuthType.BEARER.name());

        // BASIC Card
        JPanel basicCard = new JPanel(new GridBagLayout());
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        basicCard.add(new JLabel("Username:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0;
        basicUserField = new JTextField();
        basicCard.add(basicUserField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        basicCard.add(new JLabel("Password:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0;
        basicPassField = new JPasswordField();
        basicCard.add(basicPassField, gbc);
        authCardsPanel.add(basicCard, Auth.AuthType.BASIC.name());

        // API KEY Card
        JPanel apiKeyCard = new JPanel(new GridBagLayout());
        gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 6, 6, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        apiKeyCard.add(new JLabel("Key:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0;
        apiKeyKeyField = new JTextField();
        apiKeyCard.add(apiKeyKeyField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        apiKeyCard.add(new JLabel("Value:"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0;
        apiKeyValueField = new JTextField();
        apiKeyCard.add(apiKeyValueField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        apiKeyCard.add(new JLabel("Add to:"), gbc);
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 1.0;
        apiKeyAddToCombo = new JComboBox<>(new String[]{"HEADER", "QUERY"});
        apiKeyCard.add(apiKeyAddToCombo, gbc);
        authCardsPanel.add(apiKeyCard, Auth.AuthType.API_KEY.name());

        panel.add(authCardsPanel, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createBodyTab() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        panel.add(new JLabel("Raw JSON Body Template (may contain {{variable}} placeholders):"), BorderLayout.NORTH);
        bodyTextArea = new JTextArea();
        bodyTextArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        panel.add(new JScrollPane(bodyTextArea), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createAssertionsTab() {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        topPanel.add(new JLabel("Expected HTTP Status:"));
        expectedStatusSpinner = new JSpinner(new SpinnerNumberModel(200, 100, 599, 1));
        topPanel.add(expectedStatusSpinner);

        JButton guessVolatileBtn = new JButton("Guess Volatile Fields");
        guessVolatileBtn.setToolTipText("Auto-detect dynamic fields (id, timestamps, uuids) from the expected JSON");
        guessVolatileBtn.addActionListener(e -> guessVolatileFields());
        topPanel.add(guessVolatileBtn);

        panel.add(topPanel, BorderLayout.NORTH);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setResizeWeight(0.65);

        // Expected JSON
        JPanel expJsonPanel = new JPanel(new BorderLayout(4, 4));
        expJsonPanel.setBorder(BorderFactory.createTitledBorder("Expected JSON Body Template (Leave empty to skip body comparison)"));
        expectedBodyArea = new JTextArea();
        expectedBodyArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        expJsonPanel.add(new JScrollPane(expectedBodyArea), BorderLayout.CENTER);
        splitPane.setTopComponent(expJsonPanel);

        // Ignore paths
        JPanel ignorePanel = new JPanel(new BorderLayout(4, 4));
        ignorePanel.setBorder(BorderFactory.createTitledBorder("Ignore Paths (One per line, e.g. $.id, $.createdAt)"));
        ignorePathsArea = new JTextArea();
        ignorePathsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ignorePanel.add(new JScrollPane(ignorePathsArea), BorderLayout.CENTER);
        splitPane.setBottomComponent(ignorePanel);

        panel.add(splitPane, BorderLayout.CENTER);
        return panel;
    }

    private JPanel createTokenTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        // Token Provider Checkbox
        tokenProviderCheck = new JCheckBox("This API generates an authentication token (Authman / Token Provider)");
        tokenProviderCheck.setFont(tokenProviderCheck.getFont().deriveFont(Font.BOLD));
        tokenProviderCheck.addActionListener(e -> toggleTokenProviderMode(tokenProviderCheck.isSelected()));
        panel.add(tokenProviderCheck);
        panel.add(Box.createVerticalStrut(12));

        // Token Provider Details Panel
        tokenProviderPanel = new JPanel(new GridBagLayout());
        tokenProviderPanel.setBorder(BorderFactory.createTitledBorder("Token Extraction Settings"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(6, 8, 6, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        tokenProviderPanel.add(new JLabel("Save Token As Variable Name: *"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0;
        tokenVarNameField = new JTextField("authToken");
        tokenProviderPanel.add(tokenVarNameField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0;
        tokenProviderPanel.add(new JLabel("Token JSONPath (from Response Body):"), gbc);
        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0;
        tokenJsonPathField = new JTextField("$.access_token");
        tokenProviderPanel.add(tokenJsonPathField, gbc);

        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0;
        tokenProviderPanel.add(new JLabel("Response Header Source (Optional):"), gbc);
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 1.0;
        tokenHeaderSourceField = new JTextField();
        tokenHeaderSourceField.setToolTipText("If token is returned in a header (e.g. Set-Cookie, X-Auth-Token), enter header name");
        tokenProviderPanel.add(tokenHeaderSourceField, gbc);

        panel.add(tokenProviderPanel);
        panel.add(Box.createVerticalStrut(12));

        // Token Consumer Panel
        tokenConsumerPanel = new JPanel(new BorderLayout(8, 8));
        tokenConsumerPanel.setBorder(BorderFactory.createTitledBorder("Token Dependency (For Requests Using Generated Tokens)"));

        JPanel consumerInner = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        consumerInner.add(new JLabel("Depends on Token:"));
        availableTokensCombo = new JComboBox<>();
        populateAvailableTokens();
        consumerInner.add(availableTokensCombo);

        injectTokenBtn = new JButton("Inject into Header ('Authorization: Bearer {{token}}')");
        injectTokenBtn.addActionListener(e -> injectTokenIntoHeaders());
        consumerInner.add(injectTokenBtn);

        tokenConsumerPanel.add(consumerInner, BorderLayout.CENTER);
        panel.add(tokenConsumerPanel);

        return panel;
    }

    private List<String> getAvailableTokenNames() {
        List<String> list = new ArrayList<>();
        if (suite != null && suite.getTestCases() != null) {
            for (TestCase tc : suite.getTestCases()) {
                if (tc.isTokenProvider() && tc.getTokenVariableName() != null && !tc.getTokenVariableName().isBlank()) {
                    list.add(tc.getTokenVariableName().trim());
                }
            }
        }
        return list;
    }

    private void populateAvailableTokens() {
        availableTokensCombo.removeAllItems();
        availableTokensCombo.addItem("-- None --");
        for (String t : getAvailableTokenNames()) {
            availableTokensCombo.addItem(t);
        }
    }

    private void injectTokenIntoHeaders() {
        String selected = (String) availableTokensCombo.getSelectedItem();
        if (selected == null || selected.startsWith("--")) {
            JOptionPane.showMessageDialog(this, "Please select a token variable first.", "No Token Selected", JOptionPane.WARNING_MESSAGE);
            return;
        }

        String bearerVal = "Bearer {{" + selected + "}}";
        // Check if Authorization header already exists
        boolean found = false;
        for (int i = 0; i < headersTableModel.getRowCount(); i++) {
            String key = (String) headersTableModel.getValueAt(i, 0);
            if ("Authorization".equalsIgnoreCase(key)) {
                headersTableModel.setValueAt(bearerVal, i, 1);
                found = true;
                break;
            }
        }
        if (!found) {
            headersTableModel.addRow(new Object[]{"Authorization", bearerVal});
        }

        JOptionPane.showMessageDialog(this, "Injected 'Authorization: " + bearerVal + "' into Headers tab!",
                "Header Injected", JOptionPane.INFORMATION_MESSAGE);
    }

    private void toggleTokenProviderMode(boolean isProvider) {
        tokenProviderPanel.setVisible(isProvider);
        tokenConsumerPanel.setVisible(!isProvider);
    }

    private void guessVolatileFields() {
        String json = expectedBodyArea.getText();
        if (json == null || json.isBlank()) {
            JOptionPane.showMessageDialog(this, "Please enter an Expected Body JSON first.", "Empty JSON", JOptionPane.WARNING_MESSAGE);
            return;
        }
        List<String> guessed = IgnorePathHeuristic.guessVolatilePaths(json);
        if (guessed.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No volatile fields detected.", "Notice", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        Set<String> existing = new LinkedHashSet<>(Arrays.asList(ignorePathsArea.getText().split("\\R")));
        int added = 0;
        for (String g : guessed) {
            if (!existing.contains(g)) {
                existing.add(g);
                added++;
            }
        }

        ignorePathsArea.setText(String.join("\n", existing).trim());
        JOptionPane.showMessageDialog(this, "Added " + added + " volatile field(s) to Ignore Paths!", "Success", JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateBaselineUi() {
        if (testCase.getBaseline() != null) {
            String ts = testCase.getBaseline().getCapturedAt() != null
                    ? testCase.getBaseline().getCapturedAt().substring(0, Math.min(19, testCase.getBaseline().getCapturedAt().length()))
                    : "saved";
            baselineStatusLabel.setText("Active (" + ts + ")");
            baselineStatusLabel.setForeground(new Color(30, 120, 30));
            revertBaselineBtn.setEnabled(true);
        } else {
            baselineStatusLabel.setText("None");
            baselineStatusLabel.setForeground(Color.GRAY);
            revertBaselineBtn.setEnabled(false);
        }
    }

    private void populateFields() {
        nameField.setText(testCase.getName() != null ? testCase.getName() : "");
        categoryField.setText(testCase.getCategory() != null ? testCase.getCategory() : "");
        methodCombo.setSelectedItem(testCase.getMethod() != null ? testCase.getMethod() : "GET");
        urlField.setText(testCase.getUrl() != null ? testCase.getUrl() : "");
        operationIdField.setText(testCase.getOperationId() != null ? testCase.getOperationId() : "");
        updateBaselineUi();

        // Headers
        headersTableModel.setRowCount(0);
        if (testCase.getHeaders() != null) {
            for (Map.Entry<String, String> entry : testCase.getHeaders().entrySet()) {
                headersTableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
            }
        }

        // Auth
        Auth auth = testCase.getAuth();
        if (auth != null && auth.getType() != null) {
            authTypeCombo.setSelectedItem(auth.getType());
            bearerTokenField.setText(auth.getBearerToken() != null ? auth.getBearerToken() : "");
            basicUserField.setText(auth.getUsername() != null ? auth.getUsername() : "");
            basicPassField.setText(auth.getPassword() != null ? auth.getPassword() : "");
            apiKeyKeyField.setText(auth.getApiKeyKey() != null ? auth.getApiKeyKey() : "");
            apiKeyValueField.setText(auth.getApiKeyValue() != null ? auth.getApiKeyValue() : "");
            apiKeyAddToCombo.setSelectedItem(auth.getApiKeyAddTo() != null ? auth.getApiKeyAddTo() : "HEADER");
            authCardLayout.show(authCardsPanel, auth.getType().name());
        }

        // Body
        bodyTextArea.setText(testCase.getBodyTemplate() != null ? testCase.getBodyTemplate() : "");

        // Assertions
        expectedStatusSpinner.setValue(testCase.getExpectedStatus() > 0 ? testCase.getExpectedStatus() : 200);
        expectedBodyArea.setText(testCase.getExpectedBodyTemplate() != null ? testCase.getExpectedBodyTemplate() : "");
        if (testCase.getIgnorePaths() != null && !testCase.getIgnorePaths().isEmpty()) {
            ignorePathsArea.setText(String.join("\n", testCase.getIgnorePaths()));
        }

        // Token Provider
        tokenProviderCheck.setSelected(testCase.isTokenProvider());
        if (testCase.getTokenVariableName() != null) tokenVarNameField.setText(testCase.getTokenVariableName());
        if (testCase.getTokenJsonPath() != null) tokenJsonPathField.setText(testCase.getTokenJsonPath());
        if (testCase.getTokenHeaderSource() != null) tokenHeaderSourceField.setText(testCase.getTokenHeaderSource());

        if (testCase.getUsesTokenFrom() != null && !testCase.getUsesTokenFrom().isEmpty()) {
            String firstToken = testCase.getUsesTokenFrom().get(0);
            availableTokensCombo.setSelectedItem(firstToken);
        }

        toggleTokenProviderMode(testCase.isTokenProvider());
    }

    private void save() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            JOptionPane.showMessageDialog(this, "TestCase name is required.", "Validation Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            JOptionPane.showMessageDialog(this, "URL is required.", "Validation Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        testCase.setName(name);
        testCase.setCategory(categoryField.getText().trim());
        testCase.setMethod((String) methodCombo.getSelectedItem());
        testCase.setUrl(url);
        String opId = operationIdField.getText().trim();
        testCase.setOperationId(opId.isEmpty() ? null : opId);

        // Headers
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 0; i < headersTableModel.getRowCount(); i++) {
            String k = (String) headersTableModel.getValueAt(i, 0);
            String v = (String) headersTableModel.getValueAt(i, 1);
            if (k != null && !k.trim().isEmpty()) {
                headers.put(k.trim(), v != null ? v.trim() : "");
            }
        }
        testCase.setHeaders(headers);

        // Auth
        Auth.AuthType authType = (Auth.AuthType) authTypeCombo.getSelectedItem();
        if (authType == Auth.AuthType.NONE || authType == null) {
            testCase.setAuth(null);
        } else {
            Auth auth = new Auth();
            auth.setType(authType);
            switch (authType) {
                case BEARER -> auth.setBearerToken(bearerTokenField.getText().trim());
                case BASIC -> {
                    auth.setUsername(basicUserField.getText().trim());
                    auth.setPassword(new String(basicPassField.getPassword()));
                }
                case API_KEY -> {
                    auth.setApiKeyKey(apiKeyKeyField.getText().trim());
                    auth.setApiKeyValue(apiKeyValueField.getText().trim());
                    auth.setApiKeyAddTo((String) apiKeyAddToCombo.getSelectedItem());
                }
            }
            testCase.setAuth(auth);
        }

        // Body
        testCase.setBodyTemplate(bodyTextArea.getText());

        // Assertions
        testCase.setExpectedStatus((Integer) expectedStatusSpinner.getValue());
        testCase.setExpectedBodyTemplate(expectedBodyArea.getText().trim());

        List<String> ignorePaths = new ArrayList<>();
        for (String line : ignorePathsArea.getText().split("\\R")) {
            if (!line.trim().isEmpty()) {
                ignorePaths.add(line.trim());
            }
        }
        testCase.setIgnorePaths(ignorePaths);

        // Token Provider
        boolean isProvider = tokenProviderCheck.isSelected();
        testCase.setTokenProvider(isProvider);
        if (isProvider) {
            testCase.setTokenVariableName(tokenVarNameField.getText().trim());
            testCase.setTokenJsonPath(tokenJsonPathField.getText().trim());
            testCase.setTokenHeaderSource(tokenHeaderSourceField.getText().trim());
            testCase.setUsesTokenFrom(Collections.emptyList());
        } else {
            testCase.setTokenVariableName(null);
            testCase.setTokenJsonPath(null);
            testCase.setTokenHeaderSource(null);
            String selectedToken = (String) availableTokensCombo.getSelectedItem();
            if (selectedToken != null && !selectedToken.startsWith("--")) {
                testCase.setUsesTokenFrom(List.of(selectedToken));
            } else {
                testCase.setUsesTokenFrom(Collections.emptyList());
            }
        }

        saved = true;
        dispose();
    }

    public boolean isSaved() {
        return saved;
    }
}
