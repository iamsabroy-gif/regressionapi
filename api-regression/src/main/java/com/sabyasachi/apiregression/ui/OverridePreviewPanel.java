package com.sabyasachi.apiregression.ui;

import com.sabyasachi.apiregression.payload.*;
import com.sabyasachi.apiregression.runner.ReportWriter;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.io.File;
import java.util.List;

public class OverridePreviewPanel extends JPanel {

    private final OverrideReport report;
    private final Runnable onApply;

    private DefaultTableModel entriesTableModel;
    private JTable entriesTable;
    private TableRowSorter<DefaultTableModel> rowSorter;
    private JButton applyBtn;

    public OverridePreviewPanel(OverrideReport report, Runnable onApply, boolean applyWithErrors) {
        this.report = report;
        this.onApply = onApply;

        initUI(applyWithErrors);
    }

    private void initUI(boolean applyWithErrors) {
        setLayout(new BorderLayout(8, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // Top Summary Panel
        JPanel topPanel = new JPanel(new BorderLayout(8, 8));
        JPanel statsRow = new JPanel(new GridLayout(1, 4, 10, 10));

        statsRow.add(createStatCard("Modified", report.getTotalModified(), new Color(59, 130, 246)));
        statsRow.add(createStatCard("Added from Spec", report.getTotalAdded(), new Color(16, 185, 129)));
        statsRow.add(createStatCard("Skipped", report.getTotalSkipped(), new Color(100, 116, 139)));
        statsRow.add(createStatCard("Errors", report.getTotalErrors(), new Color(239, 68, 68)));

        topPanel.add(statsRow, BorderLayout.NORTH);

        // Spec messages if any
        if (!report.getSpecMessages().isEmpty()) {
            JLabel msgLbl = new JLabel("Spec messages: " + String.join("; ", report.getSpecMessages()));
            msgLbl.setForeground(new Color(202, 100, 0));
            msgLbl.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
            topPanel.add(msgLbl, BorderLayout.SOUTH);
        }

        add(topPanel, BorderLayout.NORTH);

        // Center Split Pane: Sheets Summary on Top, Entries on Bottom
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        splitPane.setResizeWeight(0.28);

        // Sheets Summary Table
        String[] sheetCols = {"Sheet", "Target TestCase", "Matched By", "Status", "Modified", "Added", "Skipped", "Errors"};
        DefaultTableModel sheetModel = new DefaultTableModel(sheetCols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };
        for (SheetSummary s : report.getSheets()) {
            sheetModel.addRow(new Object[]{
                    s.getSheet(),
                    s.getTargetTestCase(),
                    s.getMatchedBy(),
                    s.getStatus(),
                    s.getModified(),
                    s.getAdded(),
                    s.getSkipped(),
                    s.getErrors()
            });
        }
        JTable sheetTable = new JTable(sheetModel);
        sheetTable.setRowHeight(22);
        JPanel sheetPanel = new JPanel(new BorderLayout());
        sheetPanel.setBorder(BorderFactory.createTitledBorder("Sheet Summaries"));
        sheetPanel.add(new JScrollPane(sheetTable), BorderLayout.CENTER);
        splitPane.setTopComponent(sheetPanel);

        // Entries Table Panel with Filter Toolbar
        JPanel entriesPanel = new JPanel(new BorderLayout(6, 6));
        entriesPanel.setBorder(BorderFactory.createTitledBorder("Override Entries"));

        JPanel filterBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        filterBar.add(new JLabel("Filter by Action:"));
        JComboBox<String> filterCombo = new JComboBox<>(new String[]{"ALL", "MODIFIED", "ADDED", "SKIPPED", "ERROR"});
        filterCombo.addActionListener(e -> {
            String sel = (String) filterCombo.getSelectedItem();
            if ("ALL".equals(sel) || sel == null) {
                rowSorter.setRowFilter(null);
            } else {
                rowSorter.setRowFilter(RowFilter.regexFilter("^" + sel + "$", 3));
            }
        });
        filterBar.add(filterCombo);
        entriesPanel.add(filterBar, BorderLayout.NORTH);

        String[] entryCols = {"Sheet:Row", "Parameter", "Location", "Action", "Old Value", "New Value", "Reason / Spec Details"};
        entriesTableModel = new DefaultTableModel(entryCols, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return false; }
        };

        for (OverrideEntry e : report.getEntries()) {
            String oldVal = (e.getOldValue() != null) ? e.getOldValue().toString() : "-";
            String newVal = (e.getCoercedValue() != null) ? e.getCoercedValue().toString() : (e.getRawValue() != null ? e.getRawValue() : "-");
            String details = (e.getReason() != null ? e.getReason() : "") +
                    (e.getSpecPointer() != null ? " [" + e.getSpecPointer() + "]" : "") +
                    (!e.getWarnings().isEmpty() ? " Warn: " + String.join(", ", e.getWarnings()) : "");

            entriesTableModel.addRow(new Object[]{
                    e.getSheet() + ":" + e.getRow(),
                    e.getParameter(),
                    e.getLocation() != null ? e.getLocation().name() : "-",
                    e.getAction() != null ? e.getAction().name() : "-",
                    oldVal,
                    newVal,
                    details
            });
        }

        entriesTable = new JTable(entriesTableModel);
        entriesTable.setRowHeight(22);
        rowSorter = new TableRowSorter<>(entriesTableModel);
        entriesTable.setRowSorter(rowSorter);

        // Color coding renderer for Action
        entriesTable.getColumnModel().getColumn(3).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected && value != null) {
                    String act = value.toString();
                    if ("MODIFIED".equals(act)) c.setForeground(new Color(59, 130, 246));
                    else if ("ADDED".equals(act)) c.setForeground(new Color(16, 185, 129));
                    else if ("ERROR".equals(act)) c.setForeground(new Color(239, 68, 68));
                    else c.setForeground(Color.GRAY);
                    setFont(getFont().deriveFont(Font.BOLD));
                }
                return c;
            }
        });

        entriesPanel.add(new JScrollPane(entriesTable), BorderLayout.CENTER);
        splitPane.setBottomComponent(entriesPanel);

        add(splitPane, BorderLayout.CENTER);

        // Bottom Action Buttons
        JPanel bottomBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        JButton exportHtmlBtn = new JButton("Export HTML Report...");
        exportHtmlBtn.addActionListener(e -> exportHtml());
        JButton exportJsonBtn = new JButton("Export JSON Report...");
        exportJsonBtn.addActionListener(e -> exportJson());

        applyBtn = new JButton(report.isDryRun() ? "Apply Overrides to Suite" : "Applied");
        boolean canApply = report.isDryRun() && (report.getTotalErrors() == 0 || applyWithErrors);
        applyBtn.setEnabled(canApply);
        applyBtn.setFont(applyBtn.getFont().deriveFont(Font.BOLD));
        applyBtn.addActionListener(e -> {
            if (onApply != null) {
                onApply.run();
                applyBtn.setEnabled(false);
                applyBtn.setText("Applied");
            }
        });

        bottomBar.add(exportHtmlBtn);
        bottomBar.add(exportJsonBtn);
        bottomBar.add(applyBtn);
        add(bottomBar, BorderLayout.SOUTH);
    }

    private JPanel createStatCard(String label, int value, Color color) {
        JPanel card = new JPanel(new BorderLayout(4, 4));
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 220, 225)),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));
        card.setBackground(Color.WHITE);

        JLabel lbl = new JLabel(label.toUpperCase());
        lbl.setFont(lbl.getFont().deriveFont(11f));
        lbl.setForeground(Color.GRAY);

        JLabel valLbl = new JLabel(String.valueOf(value));
        valLbl.setFont(valLbl.getFont().deriveFont(Font.BOLD, 22f));
        valLbl.setForeground(color);

        card.add(lbl, BorderLayout.NORTH);
        card.add(valLbl, BorderLayout.CENTER);
        return card;
    }

    private void exportHtml() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("parameter-override-report.html"));
        chooser.setFileFilter(new FileNameExtensionFilter("HTML Report (*.html)", "html"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try {
                new ReportWriter().writeOverrideHtml(report, f.toPath());
                JOptionPane.showMessageDialog(this, "HTML report exported to:\n" + f.getAbsolutePath());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to export report: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void exportJson() {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("parameter-override-report.json"));
        chooser.setFileFilter(new FileNameExtensionFilter("JSON Report (*.json)", "json"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try {
                new ReportWriter().writeOverrideJson(report, f.toPath());
                JOptionPane.showMessageDialog(this, "JSON report exported to:\n" + f.getAbsolutePath());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to export report: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}
