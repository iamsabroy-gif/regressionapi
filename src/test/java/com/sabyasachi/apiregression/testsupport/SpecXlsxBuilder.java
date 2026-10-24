package com.sabyasachi.apiregression.testsupport;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Programmatic builder for provider specification Excel workbooks.
 */
public class SpecXlsxBuilder {

    public static class RowDef {
        public int depth = -1;
        public short cellIndent = -1;
        public String field;
        public Object example;
        public String type;
        public String required;
        public String pattern;
        public String formula;
        public Object formulaCachedVal;
        public boolean hasFormula = false;
        public boolean omitFormulaCache = false;
        public List<String> rawCells;

        public RowDef() {}
    }

    public static class SheetBuilder {
        private final SpecXlsxBuilder parent;
        private final String sheetName;
        private String operation;
        private String operationId;
        private String basePath;
        private final List<String> headers = new ArrayList<>();
        private final List<RowDef> rows = new ArrayList<>();
        private boolean hidden = false;
        private boolean veryHidden = false;

        public SheetBuilder(SpecXlsxBuilder parent, String sheetName) {
            this.parent = parent;
            this.sheetName = sheetName;
        }

        public SheetBuilder operation(String operation) {
            this.operation = operation;
            return this;
        }

        public SheetBuilder operationId(String operationId) {
            this.operationId = operationId;
            return this;
        }

        public SheetBuilder basePath(String basePath) {
            this.basePath = basePath;
            return this;
        }

        public SheetBuilder header(String... cols) {
            headers.clear();
            for (String col : cols) {
                headers.add(col);
            }
            return this;
        }

        public SheetBuilder row(int depth, String field, Object example, String type, String required, String pattern) {
            RowDef def = new RowDef();
            def.depth = depth;
            def.field = field;
            def.example = example;
            def.type = type;
            def.required = required;
            def.pattern = pattern;
            rows.add(def);
            return this;
        }

        public SheetBuilder rowWithCellIndent(short indent, String field, Object example, String type, String required, String pattern) {
            RowDef def = new RowDef();
            def.cellIndent = indent;
            def.field = field;
            def.example = example;
            def.type = type;
            def.required = required;
            def.pattern = pattern;
            rows.add(def);
            return this;
        }

        public SheetBuilder row(String field, Object example, String type, String required, String pattern) {
            RowDef def = new RowDef();
            def.field = field;
            def.example = example;
            def.type = type;
            def.required = required;
            def.pattern = pattern;
            rows.add(def);
            return this;
        }

        public SheetBuilder row(String... cells) {
            RowDef def = new RowDef();
            def.rawCells = new ArrayList<>();
            for (String c : cells) {
                def.rawCells.add(c);
            }
            rows.add(def);
            return this;
        }

        public SheetBuilder formulaRow(int depth, String field, String formula, Object cachedVal, String type, String required, String pattern) {
            RowDef def = new RowDef();
            def.depth = depth;
            def.field = field;
            def.hasFormula = true;
            def.formula = formula;
            def.formulaCachedVal = cachedVal;
            def.type = type;
            def.required = required;
            def.pattern = pattern;
            rows.add(def);
            return this;
        }

        public SheetBuilder formulaRowNoCache(int depth, String field, String formula, String type, String required, String pattern) {
            RowDef def = new RowDef();
            def.depth = depth;
            def.field = field;
            def.hasFormula = true;
            def.formula = formula;
            def.omitFormulaCache = true;
            def.type = type;
            def.required = required;
            def.pattern = pattern;
            rows.add(def);
            return this;
        }

        public SheetBuilder hidden() {
            this.hidden = true;
            return this;
        }

        public SheetBuilder veryHidden() {
            this.veryHidden = true;
            return this;
        }

        public SheetBuilder sheet(String name) {
            return parent.sheet(name);
        }

        public void build(Path path) throws IOException {
            parent.build(path);
        }

        public byte[] buildBytes() throws IOException {
            return parent.buildBytes();
        }
    }

    private final List<SheetBuilder> sheets = new ArrayList<>();

    public static SpecXlsxBuilder create() {
        return new SpecXlsxBuilder();
    }

    public SheetBuilder sheet(String name) {
        SheetBuilder sb = new SheetBuilder(this, name);
        sheets.add(sb);
        return sb;
    }

    public void build(Path path) throws IOException {
        byte[] bytes = buildBytes();
        try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
            fos.write(bytes);
        }
    }

    public byte[] buildBytes() throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            for (int i = 0; i < sheets.size(); i++) {
                SheetBuilder sb = sheets.get(i);
                XSSFSheet sheet = wb.createSheet(sb.sheetName);
                if (sb.veryHidden) {
                    wb.setSheetVisibility(i, SheetVisibility.VERY_HIDDEN);
                } else if (sb.hidden) {
                    wb.setSheetVisibility(i, SheetVisibility.HIDDEN);
                }

                int rowIndex = 0;

                // Prologue rows
                if (sb.operation != null && !sb.operation.isBlank()) {
                    Row r = sheet.createRow(rowIndex++);
                    r.createCell(0).setCellValue("operation");
                    r.createCell(1).setCellValue(sb.operation);
                }
                if (sb.operationId != null && !sb.operationId.isBlank()) {
                    Row r = sheet.createRow(rowIndex++);
                    r.createCell(0).setCellValue("operationId");
                    r.createCell(1).setCellValue(sb.operationId);
                }
                if (sb.basePath != null && !sb.basePath.isBlank()) {
                    Row r = sheet.createRow(rowIndex++);
                    r.createCell(0).setCellValue("basePath");
                    r.createCell(1).setCellValue(sb.basePath);
                }

                // Header row
                if (!sb.headers.isEmpty()) {
                    Row hRow = sheet.createRow(rowIndex++);
                    for (int c = 0; c < sb.headers.size(); c++) {
                        hRow.createCell(c).setCellValue(sb.headers.get(c));
                    }
                }

                // Data rows
                for (RowDef rd : sb.rows) {
                    Row r = sheet.createRow(rowIndex++);
                    if (rd.rawCells != null) {
                        for (int c = 0; c < rd.rawCells.size(); c++) {
                            r.createCell(c).setCellValue(rd.rawCells.get(c));
                        }
                        continue;
                    }

                    // Field (col 0)
                    Cell fieldCell = r.createCell(0);
                    String fieldText = rd.field != null ? rd.field : "";
                    if (rd.depth > 0) {
                        fieldText = "  ".repeat(rd.depth) + fieldText;
                    }
                    fieldCell.setCellValue(fieldText);

                    if (rd.cellIndent > 0) {
                        CellStyle indentStyle = wb.createCellStyle();
                        indentStyle.setIndention(rd.cellIndent);
                        fieldCell.setCellStyle(indentStyle);
                    }

                    // Example / Value (col 1)
                    Cell valCell = r.createCell(1);
                    if (rd.hasFormula) {
                        valCell.setCellFormula(rd.formula);
                        if (!rd.omitFormulaCache && rd.formulaCachedVal != null) {
                            setCellValue(valCell, rd.formulaCachedVal);
                        } else if (rd.omitFormulaCache) {
                            if (valCell instanceof org.apache.poi.xssf.usermodel.XSSFCell xc && xc.getCTCell().isSetV()) {
                                xc.getCTCell().unsetV();
                            }
                        }
                    } else if (rd.example != null) {
                        setCellValue(valCell, rd.example);
                    }

                    // Type (col 2)
                    if (rd.type != null) {
                        r.createCell(2).setCellValue(rd.type);
                    }

                    // Required (col 3)
                    if (rd.required != null) {
                        r.createCell(3).setCellValue(rd.required);
                    }

                    // Pattern (col 4)
                    if (rd.pattern != null) {
                        r.createCell(4).setCellValue(rd.pattern);
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            wb.write(baos);
            return baos.toByteArray();
        }
    }

    private static void setCellValue(Cell cell, Object val) {
        if (val instanceof Number n) {
            cell.setCellValue(n.doubleValue());
        } else if (val instanceof Boolean b) {
            cell.setCellValue(b);
        } else {
            cell.setCellValue(val.toString());
        }
    }
}
