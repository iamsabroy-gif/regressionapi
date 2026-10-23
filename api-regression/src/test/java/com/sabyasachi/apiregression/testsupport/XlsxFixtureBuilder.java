package com.sabyasachi.apiregression.testsupport;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

public class XlsxFixtureBuilder {

    public static class RowDef {
        public String parameter;
        public Object value;
        public String cellKind; // TEXT, NUMERIC, BOOLEAN, DATE, FORMULA, FORMULA_ERROR, NO_CACHE, BLANK
        public String formulaExpr;
        public byte formulaErrorCode;
        public String explicitType;
        public String explicitLocation;
        public String enabled = "Y";
        public String notes;
        public boolean isDateFormatted = false;
        public boolean isDateTime = false;

        public RowDef(String parameter) {
            this.parameter = parameter;
        }
    }

    public static class SheetBuilder {
        private final XlsxFixtureBuilder parent;
        private final String sheetName;
        private String target;
        private final Map<String, String> metadata = new LinkedHashMap<>();
        private final List<RowDef> rows = new ArrayList<>();
        private boolean hidden = false;
        private boolean veryHidden = false;
        private final List<CellRangeAddress> mergedRegions = new ArrayList<>();
        private final Set<Integer> hiddenRows = new HashSet<>();

        public SheetBuilder(XlsxFixtureBuilder parent, String sheetName) {
            this.parent = parent;
            this.sheetName = sheetName;
        }

        public SheetBuilder target(String target) {
            this.target = target;
            return this;
        }

        public SheetBuilder meta(String key, String value) {
            metadata.put(key, value);
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

        public SheetBuilder merge(int r1, int c1, int r2, int c2) {
            mergedRegions.add(new CellRangeAddress(r1, r2, c1, c2));
            return this;
        }

        public SheetBuilder hiddenRow(int rowIdx) {
            hiddenRows.add(rowIdx);
            return this;
        }

        public SheetBuilder row(String parameter, Object value) {
            RowDef r = new RowDef(parameter);
            r.value = value;
            if (value == null) {
                r.cellKind = "BLANK";
            } else if (value instanceof Number) {
                r.cellKind = "NUMERIC";
            } else if (value instanceof Boolean) {
                r.cellKind = "BOOLEAN";
            } else {
                r.cellKind = "TEXT";
            }
            rows.add(r);
            return this;
        }

        public SheetBuilder row(String parameter, Object value, String type, String location) {
            RowDef r = new RowDef(parameter);
            r.value = value;
            r.explicitType = type;
            r.explicitLocation = location;
            if (value == null) r.cellKind = "BLANK";
            else if (value instanceof Number) r.cellKind = "NUMERIC";
            else if (value instanceof Boolean) r.cellKind = "BOOLEAN";
            else r.cellKind = "TEXT";
            rows.add(r);
            return this;
        }

        public SheetBuilder text(String parameter, String value) {
            RowDef r = new RowDef(parameter);
            r.value = value;
            r.cellKind = "TEXT";
            rows.add(r);
            return this;
        }

        public SheetBuilder number(String parameter, double value) {
            RowDef r = new RowDef(parameter);
            r.value = value;
            r.cellKind = "NUMERIC";
            rows.add(r);
            return this;
        }

        public SheetBuilder bool(String parameter, boolean value) {
            RowDef r = new RowDef(parameter);
            r.value = value;
            r.cellKind = "BOOLEAN";
            rows.add(r);
            return this;
        }

        public SheetBuilder blank(String parameter) {
            RowDef r = new RowDef(parameter);
            r.value = null;
            r.cellKind = "BLANK";
            rows.add(r);
            return this;
        }

        public SheetBuilder date(String parameter, Date dateVal, boolean isDateTime) {
            RowDef r = new RowDef(parameter);
            r.value = dateVal;
            r.cellKind = "DATE";
            r.isDateFormatted = true;
            r.isDateTime = isDateTime;
            rows.add(r);
            return this;
        }

        public SheetBuilder formula(String parameter, String formulaExpr, Object cachedVal) {
            RowDef r = new RowDef(parameter);
            r.formulaExpr = formulaExpr;
            r.value = cachedVal;
            r.cellKind = "FORMULA";
            rows.add(r);
            return this;
        }

        public SheetBuilder formulaError(String parameter, String formulaExpr, FormulaError error) {
            RowDef r = new RowDef(parameter);
            r.formulaExpr = formulaExpr;
            r.cellKind = "FORMULA_ERROR";
            r.formulaErrorCode = error.getCode();
            rows.add(r);
            return this;
        }

        public SheetBuilder formulaNoCache(String parameter, String formulaExpr) {
            RowDef r = new RowDef(parameter);
            r.formulaExpr = formulaExpr;
            r.cellKind = "NO_CACHE";
            rows.add(r);
            return this;
        }

        public SheetBuilder enabled(String parameter, Object value, String enabled) {
            RowDef r = new RowDef(parameter);
            r.value = value;
            r.enabled = enabled;
            r.cellKind = value instanceof Number ? "NUMERIC" : "TEXT";
            rows.add(r);
            return this;
        }

        public SheetBuilder sheet(String name) {
            return parent.sheet(name);
        }

        public XlsxFixtureBuilder and() {
            return parent;
        }

        public void build(Path path) throws IOException {
            parent.build(path);
        }
    }

    private final List<SheetBuilder> sheets = new ArrayList<>();
    private boolean legacyHeaders = false;

    public static XlsxFixtureBuilder create() {
        return new XlsxFixtureBuilder();
    }

    public XlsxFixtureBuilder legacyHeaders(boolean legacy) {
        this.legacyHeaders = legacy;
        return this;
    }

    public SheetBuilder sheet(String name) {
        SheetBuilder sb = new SheetBuilder(this, name);
        sheets.add(sb);
        return sb;
    }

    public void build(Path path) throws IOException {
        try (Workbook wb = new XSSFWorkbook()) {
            CreationHelper createHelper = wb.getCreationHelper();
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(createHelper.createDataFormat().getFormat("yyyy-mm-dd"));
            CellStyle dateTimeStyle = wb.createCellStyle();
            dateTimeStyle.setDataFormat(createHelper.createDataFormat().getFormat("yyyy-mm-dd hh:mm:ss"));

            for (SheetBuilder sb : sheets) {
                XSSFSheet sheet = (XSSFSheet) wb.createSheet(sb.sheetName);

                int rowIndex = 0;
                // Target in A1/B1 or metadata
                if (sb.target != null) {
                    Row targetRow = sheet.createRow(rowIndex++);
                    targetRow.createCell(0).setCellValue("target");
                    targetRow.createCell(1).setCellValue(sb.target);
                }
                for (Map.Entry<String, String> meta : sb.metadata.entrySet()) {
                    Row metaRow = sheet.createRow(rowIndex++);
                    metaRow.createCell(0).setCellValue(meta.getKey());
                    metaRow.createCell(1).setCellValue(meta.getValue());
                }

                // Header row
                Row header = sheet.createRow(rowIndex++);
                if (legacyHeaders) {
                    header.createCell(0).setCellValue("path");
                    header.createCell(1).setCellValue("value");
                    header.createCell(2).setCellValue("type");
                    header.createCell(3).setCellValue("operation");
                } else {
                    header.createCell(0).setCellValue("parameter");
                    header.createCell(1).setCellValue("value");
                    header.createCell(2).setCellValue("type");
                    header.createCell(3).setCellValue("location");
                    header.createCell(4).setCellValue("enabled");
                    header.createCell(5).setCellValue("notes");
                }

                // Rows
                for (RowDef rd : sb.rows) {
                    Row row = sheet.createRow(rowIndex);
                    if (sb.hiddenRows.contains(rowIndex)) {
                        row.setZeroHeight(true);
                    }
                    rowIndex++;

                    row.createCell(0).setCellValue(rd.parameter);
                    Cell valCell = row.createCell(1);

                    if ("BLANK".equals(rd.cellKind)) {
                        valCell.setBlank();
                    } else if ("NUMERIC".equals(rd.cellKind) && rd.value instanceof Number num) {
                        valCell.setCellValue(num.doubleValue());
                    } else if ("BOOLEAN".equals(rd.cellKind) && rd.value instanceof Boolean b) {
                        valCell.setCellValue(b);
                    } else if ("DATE".equals(rd.cellKind) && rd.value instanceof Date d) {
                        valCell.setCellValue(d);
                        valCell.setCellStyle(rd.isDateTime ? dateTimeStyle : dateStyle);
                    } else if ("FORMULA".equals(rd.cellKind)) {
                        valCell.setCellFormula(rd.formulaExpr);
                        if (rd.value instanceof Number n) {
                            ((XSSFCell) valCell).getCTCell().setV(n.toString());
                        } else if (rd.value != null) {
                            ((XSSFCell) valCell).getCTCell().setV(rd.value.toString());
                        }
                    } else if ("FORMULA_ERROR".equals(rd.cellKind)) {
                        valCell.setCellFormula(rd.formulaExpr);
                        valCell.setCellErrorValue(rd.formulaErrorCode);
                    } else if ("NO_CACHE".equals(rd.cellKind)) {
                        valCell.setCellFormula(rd.formulaExpr);
                        if (((XSSFCell) valCell).getCTCell().isSetV()) {
                            ((XSSFCell) valCell).getCTCell().unsetV();
                        }
                    } else {
                        valCell.setCellValue(rd.value != null ? rd.value.toString() : "");
                    }

                    if (!legacyHeaders) {
                        if (rd.explicitType != null) row.createCell(2).setCellValue(rd.explicitType);
                        if (rd.explicitLocation != null) row.createCell(3).setCellValue(rd.explicitLocation);
                        if (rd.enabled != null) row.createCell(4).setCellValue(rd.enabled);
                        if (rd.notes != null) row.createCell(5).setCellValue(rd.notes);
                    }
                }

                // Merged regions
                for (CellRangeAddress cra : sb.mergedRegions) {
                    sheet.addMergedRegion(cra);
                }

                // Hidden sheet flags
                int sheetIndex = wb.getSheetIndex(sheet);
                if (sb.veryHidden) {
                    wb.setSheetVisibility(sheetIndex, SheetVisibility.VERY_HIDDEN);
                } else if (sb.hidden) {
                    wb.setSheetVisibility(sheetIndex, SheetVisibility.HIDDEN);
                }
            }

            try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
                wb.write(fos);
            }
        }
    }

    public static void createEncryptedWorkbook(Path path, String password) throws Exception {
        try (org.apache.poi.poifs.filesystem.POIFSFileSystem fs = new org.apache.poi.poifs.filesystem.POIFSFileSystem()) {
            org.apache.poi.poifs.crypt.EncryptionInfo info = new org.apache.poi.poifs.crypt.EncryptionInfo(org.apache.poi.poifs.crypt.EncryptionMode.agile);
            org.apache.poi.poifs.crypt.Encryptor encryptor = info.getEncryptor();
            encryptor.confirmPassword(password);
            try (Workbook wb = new XSSFWorkbook();
                 java.io.OutputStream os = encryptor.getDataStream(fs)) {
                Sheet s = wb.createSheet("Sheet1");
                Row r = s.createRow(0);
                r.createCell(0).setCellValue("parameter");
                r.createCell(1).setCellValue("value");
                Row r1 = s.createRow(1);
                r1.createCell(0).setCellValue("name");
                r1.createCell(1).setCellValue("secret");
                wb.write(os);
            }
            try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
                fs.writeFilesystem(fos);
            }
        }
    }

    public static void customersWorkbook(Path path) throws IOException {
        create()
                .sheet("Create Customer")
                .target("POST /customers")
                .text("name", "Jane Doe")
                .number("limit", 20)
                .and()
                .sheet("Add Address and Query")
                .target("POST /customers")
                .text("address.city", "Springfield")
                .text("channel", "web")
                .and()
                .sheet("Not in Spec")
                .target("POST /customers")
                .text("nonExistentField", "invalid")
                .and()
                .sheet("Ambiguous City")
                .target("POST /customers")
                .text("city", "Shelbyville")
                .and()
                .sheet("Null Field")
                .target("POST /customers")
                .text("notes", "<null>")
                .build(path);
    }
}
