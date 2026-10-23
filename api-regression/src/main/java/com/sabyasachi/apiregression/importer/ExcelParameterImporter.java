package com.sabyasachi.apiregression.importer;

import com.sabyasachi.apiregression.payload.OverrideException;
import com.sabyasachi.apiregression.payload.ReasonCode;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;

public class ExcelParameterImporter {

    public static final long MAX_WORKBOOK_SIZE_BYTES = 20 * 1024 * 1024; // 20 MB

    public static List<SheetParams> load(Path path) throws IOException {
        return new ExcelParameterImporter().parse(path, true, 5000);
    }

    public static List<SheetParams> load(Path path, boolean skipHiddenSheets) throws IOException {
        return new ExcelParameterImporter().parse(path, skipHiddenSheets, 5000);
    }

    public List<SheetParams> parse(Path path, boolean skipHiddenSheets, int maxRowsPerSheet) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("Excel file does not exist: " + path);
        }

        String fileName = path.getFileName().toString().toLowerCase();
        if (!fileName.endsWith(".xlsx")) {
            if (fileName.endsWith(".xls") || fileName.endsWith(".csv")) {
                throw new OverrideException(ReasonCode.WORKBOOK_UNSUPPORTED,
                        "Only .xlsx Excel workbooks are supported. Please 'Save As .xlsx' in Excel and re-import.");
            }
            throw new OverrideException(ReasonCode.WORKBOOK_UNSUPPORTED,
                    "Unsupported file format: expected .xlsx file.");
        }

        long size = Files.size(path);
        if (size > MAX_WORKBOOK_SIZE_BYTES) {
            throw new OverrideException(ReasonCode.WORKBOOK_UNSUPPORTED,
                    "Workbook exceeds maximum allowed size of 20 MB (" + (size / 1024 / 1024) + " MB).");
        }

        // Open read-only via InputStream wrapped in try-with-resources
        try (InputStream is = new FileInputStream(path.toFile())) {
            return parse(is, skipHiddenSheets, maxRowsPerSheet);
        } catch (EncryptedDocumentException e) {
            throw new OverrideException(ReasonCode.WORKBOOK_ENCRYPTED,
                    "The workbook is password-protected or encrypted. Please save an unprotected copy and re-import.", e);
        }
    }

    public List<SheetParams> parse(InputStream inputStream, boolean skipHiddenSheets, int maxRowsPerSheet) throws IOException {
        List<SheetParams> results = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(inputStream)) {
            int sheetCount = workbook.getNumberOfSheets();
            for (int i = 0; i < sheetCount; i++) {
                String sheetName = workbook.getSheetName(i);
                boolean isHidden = workbook.isSheetHidden(i);
                boolean isVeryHidden = workbook.isSheetVeryHidden(i);

                SheetParams sheetParams = new SheetParams();
                sheetParams.setSheetName(sheetName);

                if (isVeryHidden || (isHidden && skipHiddenSheets)) {
                    continue;
                }

                Sheet sheet = workbook.getSheetAt(i);
                parseSheet(sheet, sheetParams, maxRowsPerSheet);
                results.add(sheetParams);
            }
        } catch (EncryptedDocumentException e) {
            throw new OverrideException(ReasonCode.WORKBOOK_ENCRYPTED,
                    "The workbook is password-protected or encrypted. Please save an unprotected copy and re-import.", e);
        } catch (OverrideException oe) {
            throw oe;
        } catch (Exception e) {
            throw new IOException("Failed to read .xlsx workbook: " + e.getMessage(), e);
        }

        return results;
    }

    private void parseSheet(Sheet sheet, SheetParams sheetParams, int maxRowsPerSheet) {
        int firstRowNum = sheet.getFirstRowNum();
        int lastRowNum = sheet.getLastRowNum();

        if (firstRowNum < 0 || lastRowNum < 0 || lastRowNum < firstRowNum) {
            sheetParams.setTarget(sheet.getSheetName());
            return;
        }

        // Check for merged regions to handle top-left cell detection
        List<CellRangeAddress> mergedRegions = sheet.getMergedRegions();

        // 1. Check A1/B1 target metadata & find header row
        int headerRowNum = -1;
        Map<String, Integer> colMap = new HashMap<>();

        for (int r = firstRowNum; r <= Math.min(lastRowNum, 50); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            // Check if this row is the header row
            int paramCol = findHeaderCell(row, "parameter");
            if (paramCol != -1) {
                headerRowNum = r;
                mapColumns(row, colMap, sheetParams);
                break;
            } else {
                // Check metadata key/value in col A & B
                Cell cellA = row.getCell(0);
                Cell cellB = row.getCell(1);
                String key = getCellStringValue(cellA);
                String val = getCellStringValue(cellB);
                if (key != null && !key.isBlank()) {
                    String cleanKey = key.trim().toLowerCase();
                    if ("target".equals(cleanKey)) {
                        sheetParams.setTarget(val != null ? val.trim() : "");
                    } else if ("location".equals(cleanKey)) {
                        sheetParams.setDefaultLocation(val != null ? val.trim().toLowerCase() : null);
                    }
                    if (val != null) {
                        sheetParams.getMetadata().put(key.trim(), val.trim());
                    }
                }
            }
        }

        if (sheetParams.getTarget() == null || sheetParams.getTarget().isBlank()) {
            sheetParams.setTarget(sheet.getSheetName());
        }

        if (headerRowNum == -1) {
            sheetParams.addSheetWarning("Header row with 'parameter' column was not found in sheet.");
            return;
        }

        // 2. Parse data rows after header row
        int paramCol = colMap.getOrDefault("parameter", -1);
        int valueCol = colMap.getOrDefault("value", -1);
        int typeCol = colMap.getOrDefault("type", -1);
        int opCol = colMap.getOrDefault("op", -1);
        int locCol = colMap.getOrDefault("location", -1);
        int enabledCol = colMap.getOrDefault("enabled", -1);
        int notesCol = colMap.getOrDefault("notes", -1);

        if (paramCol == -1 || valueCol == -1) {
            sheetParams.addSheetWarning("Required 'parameter' and/or 'value' columns were missing.");
            return;
        }

        int rowCount = 0;
        List<ParamRow> rawRows = new ArrayList<>();

        for (int r = headerRowNum + 1; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            rowCount++;
            if (rowCount > maxRowsPerSheet) {
                sheetParams.addSheetWarning("ROW_CAP_EXCEEDED: Sheet exceeds maximum allowed row limit of " + maxRowsPerSheet + " rows. Excess rows ignored.");
                break;
            }

            // Check if row is blank
            Cell pCell = row.getCell(paramCol);
            String pVal = getCellStringValue(pCell);
            if (pVal == null || pVal.isBlank()) {
                continue; // Skip blank parameter rows
            }

            int excelRowNumber = r + 1; // 1-based row
            ParamRow paramRow = new ParamRow();
            paramRow.setRow(excelRowNumber);
            paramRow.setParameter(pVal.trim());

            // Check if value cell is part of merged region (non-top-left)
            Cell vCell = row.getCell(valueCol);
            if (vCell != null && isNonTopLeftMerged(vCell, mergedRegions)) {
                paramRow.setRawValue(null);
                paramRow.setCellKindEnum(ParamRow.CellKind.BLANK);
                paramRow.addWarning("Cell is inside a merged region and is not the top-left cell. Treated as blank.");
            } else {
                extractCellValue(vCell, paramRow);
            }

            // Type column
            if (typeCol != -1) {
                String t = getCellStringValue(row.getCell(typeCol));
                if (t != null && !t.isBlank()) {
                    paramRow.setExplicitType(t.trim().toLowerCase());
                }
            }

            // Location column
            if (locCol != -1) {
                String l = getCellStringValue(row.getCell(locCol));
                if (l != null && !l.isBlank()) {
                    paramRow.setExplicitLocation(l.trim().toLowerCase());
                }
            }
            if (paramRow.getExplicitLocation() == null && sheetParams.getDefaultLocation() != null) {
                paramRow.setExplicitLocation(sheetParams.getDefaultLocation());
            }

            // Enabled column
            if (enabledCol != -1) {
                String eStr = getCellStringValue(row.getCell(enabledCol));
                if (eStr != null && !eStr.isBlank()) {
                    String clean = eStr.trim().toLowerCase();
                    if ("n".equals(clean) || "no".equals(clean) || "false".equals(clean) || "0".equals(clean)) {
                        paramRow.setEnabled(false);
                    }
                }
            }

            // Notes column
            if (notesCol != -1) {
                paramRow.setNotes(getCellStringValue(row.getCell(notesCol)));
            }

            // Op column
            if (opCol != -1) {
                String opVal = getCellStringValue(row.getCell(opCol));
                if (opVal != null && !opVal.isBlank()) {
                    paramRow.setOp(opVal.trim());
                }
            }

            rawRows.add(paramRow);
        }

        // 3. Duplicate parameter handling (§3a.2): Last row wins!
        Map<String, ParamRow> winningRows = new LinkedHashMap<>();
        for (ParamRow pr : rawRows) {
            String key = pr.getParameter().toLowerCase();
            if (winningRows.containsKey(key)) {
                ParamRow prev = winningRows.get(key);
                if (pr.getOp() == null || pr.getOp().equals("replace")) {
                    if (prev.getOp() != null) {
                        pr.setOp(prev.getOp());
                    }
                }
                pr.addWarning("DUPLICATE: parameter appears multiple times in sheet; superseded row " + prev.getRow());
            }
            winningRows.put(key, pr);
        }
        sheetParams.getRows().addAll(winningRows.values());
    }

    private void extractCellValue(Cell cell, ParamRow paramRow) {
        if (cell == null) {
            paramRow.setRawValue(null);
            paramRow.setCellKindEnum(ParamRow.CellKind.BLANK);
            return;
        }

        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            boolean hasCache = true;
            if (cell instanceof org.apache.poi.xssf.usermodel.XSSFCell xssfCell) {
                if (!xssfCell.getCTCell().isSetV()) {
                    hasCache = false;
                }
            }
            CellType cachedType = cell.getCachedFormulaResultType();
            if (!hasCache || cachedType == CellType._NONE || cachedType == CellType.BLANK) {
                paramRow.setRawValue(null);
                paramRow.setCellKindEnum(ParamRow.CellKind.NO_CACHE);
                paramRow.addWarning("Formula has no cached value.");
                return;
            } else if (cachedType == CellType.ERROR) {
                paramRow.setRawValue("#ERROR!");
                paramRow.setCellKindEnum(ParamRow.CellKind.ERROR);
                paramRow.addWarning("Formula resulted in cached error: " + cell.getErrorCellValue());
                return;
            }
            type = cachedType;
        }

        switch (type) {
            case BLANK:
                paramRow.setRawValue(null);
                paramRow.setCellKindEnum(ParamRow.CellKind.BLANK);
                break;
            case BOOLEAN:
                paramRow.setRawValue(String.valueOf(cell.getBooleanCellValue()));
                paramRow.setCellKindEnum(ParamRow.CellKind.BOOLEAN);
                break;
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    Date date = cell.getDateCellValue();
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
                    paramRow.setRawValue(sdf.format(date));
                    paramRow.setCellKindEnum(ParamRow.CellKind.DATE);
                } else {
                    double numVal = cell.getNumericCellValue();
                    // Check 15 significant digits precision limit: |value| >= 1e15
                    if (Math.abs(numVal) >= 1e15) {
                        paramRow.setCellKindEnum(ParamRow.CellKind.TOO_PRECISE);
                        paramRow.addWarning("NUMBER_PRECISION_LOSS: Numeric value has 15+ digits and may lose precision. Please format cell as Text in Excel.");
                    } else {
                        paramRow.setCellKindEnum(ParamRow.CellKind.NUMERIC);
                    }
                    BigDecimal bd = BigDecimal.valueOf(numVal);
                    // Strip trailing zeros for integers (e.g. 10.0 -> "10")
                    String numStr = bd.stripTrailingZeros().toPlainString();
                    paramRow.setRawValue(numStr);
                }
                break;
            case STRING:
                String text = cell.getStringCellValue();
                if (text == null) {
                    paramRow.setRawValue(null);
                    paramRow.setCellKindEnum(ParamRow.CellKind.BLANK);
                } else if ("<null>".equalsIgnoreCase(text.trim())) {
                    paramRow.setRawValue("<null>");
                    paramRow.setCellKindEnum(ParamRow.CellKind.TEXT);
                } else if ("<empty>".equalsIgnoreCase(text.trim())) {
                    paramRow.setRawValue("<empty>");
                    paramRow.setCellKindEnum(ParamRow.CellKind.TEXT);
                } else {
                    if (text.startsWith(" ") || text.endsWith(" ")) {
                        paramRow.addWarning("Value cell has leading or trailing whitespace.");
                    }
                    paramRow.setRawValue(text);
                    paramRow.setCellKindEnum(ParamRow.CellKind.TEXT);
                }
                break;
            case ERROR:
                paramRow.setRawValue("#ERROR!");
                paramRow.setCellKindEnum(ParamRow.CellKind.ERROR);
                paramRow.addWarning("Cell contains error: " + cell.getErrorCellValue());
                break;
            default:
                paramRow.setRawValue(cell.toString());
                paramRow.setCellKindEnum(ParamRow.CellKind.TEXT);
                break;
        }
    }

    private boolean isNonTopLeftMerged(Cell cell, List<CellRangeAddress> mergedRegions) {
        if (mergedRegions == null || mergedRegions.isEmpty()) return false;
        int r = cell.getRowIndex();
        int c = cell.getColumnIndex();

        for (CellRangeAddress range : mergedRegions) {
            if (range.isInRange(r, c)) {
                return (r != range.getFirstRow() || c != range.getFirstColumn());
            }
        }
        return false;
    }

    private int findHeaderCell(Row row, String targetHeader) {
        short minCol = row.getFirstCellNum();
        short maxCol = row.getLastCellNum();
        if (minCol < 0 || maxCol < 0) return -1;

        for (int c = minCol; c < maxCol; c++) {
            Cell cell = row.getCell(c);
            String val = getCellStringValue(cell);
            if (val != null) {
                String clean = val.trim().toLowerCase();
                if ("parameter".equals(targetHeader)) {
                    if ("parameter".equals(clean) || "path".equals(clean) || "param".equals(clean)) {
                        return c;
                    }
                } else if (clean.equals(targetHeader.toLowerCase())) {
                    return c;
                }
            }
        }
        return -1;
    }

    private void mapColumns(Row headerRow, Map<String, Integer> colMap, SheetParams sheetParams) {
        short minCol = headerRow.getFirstCellNum();
        short maxCol = headerRow.getLastCellNum();
        if (minCol < 0 || maxCol < 0) return;

        for (int c = minCol; c < maxCol; c++) {
            Cell cell = headerRow.getCell(c);
            String val = getCellStringValue(cell);
            if (val != null && !val.isBlank()) {
                String clean = val.trim().toLowerCase();
                if ("parameter".equals(clean) || "path".equals(clean) || "param".equals(clean)) {
                    colMap.put("parameter", c);
                } else if ("op".equals(clean) || "action".equals(clean) || "operation".equals(clean)) {
                    colMap.put("op", c);
                } else if ("value".equals(clean) || "type".equals(clean) || "location".equals(clean) || "enabled".equals(clean) || "notes".equals(clean)) {
                    colMap.put(clean, c);
                } else {
                    sheetParams.addSheetWarning("Unknown column header in sheet: '" + val.trim() + "' (ignored).");
                }
            }
        }
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return null;
        CellType type = cell.getCellType();
        if (type == CellType.FORMULA) {
            type = cell.getCachedFormulaResultType();
        }
        switch (type) {
            case STRING:
                return cell.getStringCellValue();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(cell.getDateCellValue());
                }
                BigDecimal bd = BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros();
                return bd.toPlainString();
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            default:
                return null;
        }
    }
}
