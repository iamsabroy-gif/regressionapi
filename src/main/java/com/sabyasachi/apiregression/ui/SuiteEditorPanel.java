package com.sabyasachi.apiregression.ui;

import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestSuite;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SuiteEditorPanel extends JPanel {

    private final Frame owner;
    private TestSuite currentSuite;

    // Suite metadata inputs
    private JTextField suiteNameField;
    private JTextField baseUrlField;

    // Variables table
    private DefaultTableModel varsTableModel;
    private JTable varsTable;

    // Test cases table
    private DefaultTableModel testCasesTableModel;
    private JTable testCasesTable;

    public SuiteEditorPanel(Frame owner, TestSuite currentSuite) {
        this.owner = owner;
        this.currentSuite = currentSuite != null ? currentSuite : new TestSuite("New Test Suite");

        initUI();
        loadSuiteData();
    }

    private void initUI() {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        // Top: Suite metadata and Suite Variables
        JPanel topPanel = new JPanel(new BorderLayout(8, 8));
        topPanel.setBorder(BorderFactory.createTitledBorder("Suite Configuration & Variables"));

        JPanel metaPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 6, 4, 6);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0;
        metaPanel.add(new JLabel("Suite Name:"), gbc);
        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 0.5;
        suiteNameField = new JTextField();
        metaPanel.add(suiteNameField, gbc);

        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 0;
        metaPanel.add(new JLabel("Base URL:"), gbc);
        gbc.gridx = 3; gbc.gridy = 0; gbc.weightx = 0.5;
        baseUrlField = new JTextField();
        metaPanel.add(baseUrlField, gbc);

        topPanel.add(metaPanel, BorderLayout.NORTH);

        // Variables table panel
        JPanel varsPanel = new JPanel(new BorderLayout(4, 4));
        varsPanel.setBorder(BorderFactory.createTitledBorder("Shared Suite Variables ({{variable}} syntax)"));
        varsTableModel = new DefaultTableModel(new String[]{"Variable Name", "Value"}, 0);
        varsTable = new JTable(varsTableModel);
        varsTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        varsTable.setPreferredScrollableViewportSize(new Dimension(500, 70));
        varsPanel.add(new JScrollPane(varsTable), BorderLayout.CENTER);

        JPanel varsBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton addVarBtn = new JButton("Add Variable");
        addVarBtn.addActionListener(e -> varsTableModel.addRow(new Object[]{"newVar", "value"}));
        JButton removeVarBtn = new JButton("Remove Selected Variable");
        removeVarBtn.addActionListener(e -> {
            int sel = varsTable.getSelectedRow();
            if (sel >= 0) varsTableModel.removeRow(sel);
        });
        varsBtnRow.add(addVarBtn);
        varsBtnRow.add(removeVarBtn);
        varsPanel.add(varsBtnRow, BorderLayout.SOUTH);

        topPanel.add(varsPanel, BorderLayout.CENTER);
        add(topPanel, BorderLayout.NORTH);

        // Center: TestCases table
        JPanel testCasesPanel = new JPanel(new BorderLayout(6, 6));
        testCasesPanel.setBorder(BorderFactory.createTitledBorder("Test Cases"));

        String[] columns = {"#", "Method", "Name", "Category", "URL", "Token Role", "Expected Status"};
        testCasesTableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        testCasesTable = new JTable(testCasesTableModel);
        testCasesTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        testCasesTable.setRowHeight(24);

        // Adjust column widths
        testCasesTable.getColumnModel().getColumn(0).setPreferredWidth(40);
        testCasesTable.getColumnModel().getColumn(1).setPreferredWidth(70);
        testCasesTable.getColumnModel().getColumn(2).setPreferredWidth(200);
        testCasesTable.getColumnModel().getColumn(3).setPreferredWidth(120);
        testCasesTable.getColumnModel().getColumn(4).setPreferredWidth(280);
        testCasesTable.getColumnModel().getColumn(5).setPreferredWidth(130);
        testCasesTable.getColumnModel().getColumn(6).setPreferredWidth(100);

        testCasesTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    editSelectedTestCase();
                }
            }
        });

        testCasesPanel.add(new JScrollPane(testCasesTable), BorderLayout.CENTER);

        // Right / Bottom Buttons for TestCases
        JPanel actionBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        JButton addTcBtn = new JButton("Add TestCase");
        addTcBtn.addActionListener(e -> addNewTestCase());

        JButton editTcBtn = new JButton("Edit Selected");
        editTcBtn.addActionListener(e -> editSelectedTestCase());

        JButton duplicateTcBtn = new JButton("Duplicate");
        duplicateTcBtn.addActionListener(e -> duplicateSelectedTestCase());

        JButton deleteTcBtn = new JButton("Delete");
        deleteTcBtn.addActionListener(e -> deleteSelectedTestCase());

        JButton moveUpBtn = new JButton("Move Up ↑");
        moveUpBtn.addActionListener(e -> moveTestCase(-1));

        JButton moveDownBtn = new JButton("Move Down ↓");
        moveDownBtn.addActionListener(e -> moveTestCase(1));

        actionBtnRow.add(addTcBtn);
        actionBtnRow.add(editTcBtn);
        actionBtnRow.add(duplicateTcBtn);
        actionBtnRow.add(deleteTcBtn);
        actionBtnRow.add(new JSeparator(SwingConstants.VERTICAL));
        actionBtnRow.add(moveUpBtn);
        actionBtnRow.add(moveDownBtn);

        testCasesPanel.add(actionBtnRow, BorderLayout.SOUTH);
        add(testCasesPanel, BorderLayout.CENTER);
    }

    public void setSuite(TestSuite suite) {
        this.currentSuite = suite != null ? suite : new TestSuite("New Test Suite");
        loadSuiteData();
    }

    public TestSuite getSuite() {
        syncSuiteData();
        return currentSuite;
    }

    public void syncSuiteData() {
        if (currentSuite == null) return;
        currentSuite.setName(suiteNameField.getText().trim());
        currentSuite.setBaseUrl(baseUrlField.getText().trim());

        Map<String, String> vars = new LinkedHashMap<>();
        for (int i = 0; i < varsTableModel.getRowCount(); i++) {
            String k = (String) varsTableModel.getValueAt(i, 0);
            String v = (String) varsTableModel.getValueAt(i, 1);
            if (k != null && !k.trim().isEmpty()) {
                vars.put(k.trim(), v != null ? v.trim() : "");
            }
        }
        currentSuite.setVariables(vars);
    }

    public void loadSuiteData() {
        if (currentSuite == null) return;

        suiteNameField.setText(currentSuite.getName() != null ? currentSuite.getName() : "New Test Suite");
        baseUrlField.setText(currentSuite.getBaseUrl() != null ? currentSuite.getBaseUrl() : "");

        varsTableModel.setRowCount(0);
        if (currentSuite.getVariables() != null) {
            for (Map.Entry<String, String> entry : currentSuite.getVariables().entrySet()) {
                varsTableModel.addRow(new Object[]{entry.getKey(), entry.getValue()});
            }
        }

        refreshTestCasesTable();
    }

    public void refreshTestCasesTable() {
        testCasesTableModel.setRowCount(0);
        if (currentSuite == null || currentSuite.getTestCases() == null) return;

        int index = 1;
        for (TestCase tc : currentSuite.getTestCases()) {
            String tokenRole = "-";
            if (tc.isTokenProvider()) {
                tokenRole = "PROVIDES: " + (tc.getTokenVariableName() != null ? tc.getTokenVariableName() : "token");
            } else if (tc.getUsesTokenFrom() != null && !tc.getUsesTokenFrom().isEmpty()) {
                tokenRole = "USES: " + String.join(", ", tc.getUsesTokenFrom());
            }

            String displayName = (tc.isTemplate() ? "[template] " : "") + (tc.getName() != null ? tc.getName() : "");
            testCasesTableModel.addRow(new Object[]{
                    index++,
                    tc.getMethod(),
                    displayName,
                    tc.getCategory() != null ? tc.getCategory() : "",
                    tc.getUrl(),
                    tokenRole,
                    tc.getExpectedStatus()
            });
        }
    }

    private void addNewTestCase() {
        syncSuiteData();
        TestCase newTc = new TestCase();
        newTc.setName("New Request " + (currentSuite.getTestCases().size() + 1));
        newTc.setMethod("GET");
        newTc.setUrl("{{baseUrl}}/api/resource");

        TestCaseEditDialog dialog = new TestCaseEditDialog(owner, newTc, currentSuite);
        dialog.setVisible(true);

        if (dialog.isSaved()) {
            currentSuite.getTestCases().add(newTc);
            refreshTestCasesTable();
            testCasesTable.setRowSelectionInterval(currentSuite.getTestCases().size() - 1, currentSuite.getTestCases().size() - 1);
        }
    }

    private void editSelectedTestCase() {
        syncSuiteData();
        int selected = testCasesTable.getSelectedRow();
        if (selected < 0 || selected >= currentSuite.getTestCases().size()) {
            JOptionPane.showMessageDialog(this, "Please select a TestCase to edit.", "Selection Required", JOptionPane.WARNING_MESSAGE);
            return;
        }

        TestCase tc = currentSuite.getTestCases().get(selected);
        TestCaseEditDialog dialog = new TestCaseEditDialog(owner, tc, currentSuite);
        dialog.setVisible(true);

        if (dialog.isSaved()) {
            refreshTestCasesTable();
            testCasesTable.setRowSelectionInterval(selected, selected);
        }
    }

    private void duplicateSelectedTestCase() {
        int selected = testCasesTable.getSelectedRow();
        if (selected < 0 || selected >= currentSuite.getTestCases().size()) {
            return;
        }
        TestCase source = currentSuite.getTestCases().get(selected);
        TestCase copy = new TestCase();
        copy.setName(source.getName() + " (Copy)");
        copy.setCategory(source.getCategory());
        copy.setMethod(source.getMethod());
        copy.setUrl(source.getUrl());
        copy.setHeaders(new LinkedHashMap<>(source.getHeaders()));
        copy.setBodyTemplate(source.getBodyTemplate());
        copy.setExpectedStatus(source.getExpectedStatus());
        copy.setExpectedBodyTemplate(source.getExpectedBodyTemplate());
        copy.setIgnorePaths(new ArrayList<>(source.getIgnorePaths()));
        copy.setTokenProvider(source.isTokenProvider());
        copy.setTokenVariableName(source.getTokenVariableName());
        copy.setTokenJsonPath(source.getTokenJsonPath());
        copy.setTokenHeaderSource(source.getTokenHeaderSource());
        copy.setUsesTokenFrom(new ArrayList<>(source.getUsesTokenFrom()));

        currentSuite.getTestCases().add(selected + 1, copy);
        refreshTestCasesTable();
        testCasesTable.setRowSelectionInterval(selected + 1, selected + 1);
    }

    private void deleteSelectedTestCase() {
        int selected = testCasesTable.getSelectedRow();
        if (selected < 0 || selected >= currentSuite.getTestCases().size()) {
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(this,
                "Are you sure you want to delete TestCase: '" + currentSuite.getTestCases().get(selected).getName() + "'?",
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            currentSuite.getTestCases().remove(selected);
            refreshTestCasesTable();
            if (currentSuite.getTestCases().size() > 0) {
                int newSel = Math.min(selected, currentSuite.getTestCases().size() - 1);
                testCasesTable.setRowSelectionInterval(newSel, newSel);
            }
        }
    }

    private void moveTestCase(int delta) {
        int selected = testCasesTable.getSelectedRow();
        if (selected < 0) return;
        int target = selected + delta;
        if (target < 0 || target >= currentSuite.getTestCases().size()) return;

        TestCase item = currentSuite.getTestCases().remove(selected);
        currentSuite.getTestCases().add(target, item);
        refreshTestCasesTable();
        testCasesTable.setRowSelectionInterval(target, target);
    }
}
