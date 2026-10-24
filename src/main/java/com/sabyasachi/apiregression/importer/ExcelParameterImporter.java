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

    public static final int MAX_SHEETS_PER_WORKBOOK = 200;
    public static final int MAX_COLS_PER_ROW = 256;

    static {
        configureZipSecurity();
    }

    public static void configureZipSecurity() {
        try {
            org.apache.poi.openxml4j.util.ZipSecureFile.setMinInflateRatio(0.01);
            org.apache.poi.openxml4j.util.ZipSecureFile.setMaxEntrySize(100_000_000L);
            org.apache.poi.openxml4j.util.ZipSecureFile.setMaxTextSize(10_000_000L);
            org.apache.poi.util.IOUtils.setByteArrayMaxOverride(50_000_000);
        } catch (Throwable ignored) {
        }
    }

    private static final Set<String> ALLOWED_HEADERS = Set.of("parameter", "value", "type", "location", "enabled", "notes");
    private static final Set<String> REJECTED_HEADERS = Set.of("op", "path", "param", "action", "operation");

    public List<SheetParams> parse(InputStream inputStream, boolean skipHiddenSheets, int maxRowsPerSheet) throws IOException {
        configureZipSecurity();
        List<SheetParams> results = new ArrayList<>();

        InputStream is = org.apache.poi.poifs.filesystem.FileMagic.prepareToCheckMagic(inputStream);
        org.apache.poi.poifs.filesystem.FileMagic magic = org.apache.poi.poifs.filesystem.FileMagic.valueOf(is);
        if (magic == org.apache.poi.poifs.filesystem.FileMagic.OLE2) {
            try (org.apache.poi.poifs.filesystem.POIFSFileSystem poifs = new org.apache.poi.poifs.filesystem.POIFSFileSystem(is)) {
                if (poifs.getRoot().hasEntry("EncryptionInfo") || poifs.getRoot().hasEntry(org.apache.poi.poifs.crypt.EncryptionInfo.ENCRYPTION_INFO_ENTRY)) {
                    throw new OverrideException(ReasonCode.WORKBOOK_ENCRYPTED,
                            "The workbook is password-protected or encrypted. Please save an unprotected copy and re-import.");
                }
            } catch (OverrideException oe) {
                throw oe;
            } catch (Exception ignored) {
            }
            throw new OverrideException(ReasonCode.WORKBOOK_UNSUPPORTED,
                    "Only .xlsx Excel workbooks are supported. Please 'Save As .xlsx' in Excel and re-import.");
        } else if (magic != org.apache.poi.poifs.filesystem.FileMagic.OOXML) {
            throw new OverrideException(ReasonCode.WORKBOOK_UNSUPPORTED,
                    "Unsupported file format: expected .xlsx file.");
        }

        try (Workbook workbook = new XSSFWorkbook(is)) {
            int sheetCount = workbook.getNumberOfSheets();
            if (sheetCount > MAX_SHEETS_PER_WORKBOOK) {
                throw new OverrideException(ReasonCode.WORKBOOK_UNSUPPORTED,
                        "Workbook exceeds maximum allowed sheet count of " + MAX_SHEETS_PER_WORKBOOK + " (found " + sheetCount + ").");
            }
            for (int i = 0; i < sheetCount; i++) {
                String sheetName = workbook.getSheetName(i);
                boolean isHidden = workbook.isSheetHidden(i);
                boolean isVeryHidden = workbook.isSheetVeryHidden(i);

                SheetParams sheetParams = new SheetParams();
                sheetParams.setSheetName(sheetName);

                if (isVeryHidden) {
                    continue; // Very hidden always skipped
                }
                if (isHidden && skipHiddenSheets) {
                    sheetParams.setStatus("SKIPPED");
                    sheetParams.setSkipReason(ReasonCode.HIDDEN_SHEET.name());
                    results.add(sheetParams);
                    continue;
                }

                Sheet sheet = workbook.getSheetAt(i);
                List<SheetParams> parsedList = parseSheet(sheet, sheetParams, maxRowsPerSheet);
                results.addAll(parsedList);
            }

            int totalRuns = 0;
            for (SheetParams sp : results) {
                if (sp.getRunIndex() != null) {
                    totalRuns++;
                    if (totalRuns > 20000) {
                        throw new OverrideException(ReasonCode.ROW_CAP_EXCEEDED,
                                "Workbook exceeds maximum allowed run limit of 20,000 runs.");
                    }
                }
            }
        } catch (EncryptedDocumentException e) {
            throw new OverrideException(ReasonCode.WORKBOOK_ENCRYPTED,
                    "The workbook is password-protected or encrypted. Please save an unprotected copy and re-import.", e);
        } catch (OverrideException oe) {
            throw oe;
        } catch (Exception e) {
            String msg = (e.getMessage() != null ? e.getMessage() : "").toLowerCase();
            if (msg.contains("zip bomb") || msg.contains("exceed the max. ratio") || msg.contains("inflate")
                    || msg.contains("too large") || e instanceof org.apache.poi.util.RecordFormatException) {
                throw new OverrideException(ReasonCode.WORKBOOK_UNSUPPORTED,
                        "workbook rejected: too large when decompressed (potential zip bomb)", e);
            }
            throw new IOException("Failed to read .xlsx workbook: " + e.getMessage(), e);
        }

        return results;
    }

    private List<SheetParams> parseSheet(Sheet sheet, SheetParams sheetParams, int maxRowsPerSheet) {
        int firstRowNum = sheet.getFirstRowNum();
        int lastRowNum = sheet.getLastRowNum();

        if (firstRowNum < 0 || lastRowNum < 0 || lastRowNum < firstRowNum) {
            sheetParams.setTarget(sheet.getSheetName());
            return List.of(sheetParams);
        }

        List<CellRangeAddress> mergedRegions = sheet.getMergedRegions();

        // 1. Check A1/B1 target metadata & find header row
        int headerRowNum = -1;
        Map<String, Integer> colMap = new HashMap<>();

        for (int r = firstRowNum; r <= Math.min(lastRowNum, 50); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            // Reject op/path/param headers
            short minCol = row.getFirstCellNum();
            short maxCol = row.getLastCellNum();
            if (minCol >= 0 && maxCol >= 0) {
                int limit = Math.min((int) maxCol, MAX_COLS_PER_ROW);
                for (int c = minCol; c < limit; c++) {
                    Cell cell = row.getCell(c);
                    String val = getCellStringValue(cell);
                    if (val != null && !val.isBlank()) {
                        String clean = val.trim().toLowerCase(Locale.ROOT);
                        if (REJECTED_HEADERS.contains(clean)) {
                            throw new OverrideException(ReasonCode.WORKBOOK_UNSUPPORTED,
                                    "Invalid column header '" + val.trim() + "'. Expected columns: parameter, value, type, location, enabled, notes");
                        }
                    }
                }
            }

            int paramCol = findHeaderCell(row, "parameter");
            if (paramCol != -1) {
                headerRowNum = r;
                mapColumns(row, colMap, sheetParams);
                break;
            } else {
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
            return DataSheetPivot.parseDataDrivenSheet(sheet, sheetParams, mergedRegions, this);
        }

        int paramCol = colMap.getOrDefault("parameter", -1);
        int valueCol = colMap.getOrDefault("value", -1);
        int typeCol = colMap.getOrDefault("type", -1);
        int locCol = colMap.getOrDefault("location", -1);
        int enabledCol = colMap.getOrDefault("enabled", -1);
        int notesCol = colMap.getOrDefault("notes", -1);

        if (paramCol == -1 || valueCol == -1) {
            sheetParams.addSheetWarning("Required 'parameter' and/or 'value' columns were missing.");
            return List.of(sheetParams);
        }

        // Count data rows for row cap
        int dataRowCount = 0;
        for (int r = headerRowNum + 1; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            Cell pCell = row.getCell(paramCol);
            String pVal = getCellStringValue(pCell);
            if (pVal != null && !pVal.isBlank()) {
                dataRowCount++;
            }
        }

        if (dataRowCount > maxRowsPerSheet) {
            sheetParams.setStatus("NOT_APPLIED");
            sheetParams.setSkipReason(ReasonCode.ROW_CAP_EXCEEDED.name());
            sheetParams.getRows().clear();
            sheetParams.addSheetWarning("ROW_CAP_EXCEEDED: Sheet exceeds maximum allowed row limit of " + maxRowsPerSheet + " rows (" + dataRowCount + " rows found). Zero rows processed.");
            return List.of(sheetParams);
        }

        List<ParamRow> rawRows = new ArrayList<>();

        for (int r = headerRowNum + 1; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            Cell pCell = row.getCell(paramCol);
            String pVal = getCellStringValue(pCell);
            if (pVal == null || pVal.isBlank()) {
                continue;
            }

            int excelRowNumber = r + 1;
            ParamRow paramRow = new ParamRow();
            paramRow.setRow(excelRowNumber);
            paramRow.setParameter(pVal.trim());

            if (row.getZeroHeight()) {
                paramRow.addWarning("Row is hidden or filtered.");
            }

            Cell vCell = row.getCell(valueCol);
            if (vCell != null && isNonTopLeftMerged(vCell, mergedRegions)) {
                paramRow.setRawValue(null);
                paramRow.setCellKindEnum(ParamRow.CellKind.BLANK);
                paramRow.addWarning("Cell is inside a merged region and is not the top-left cell. Treated as blank.");
            } else {
                extractCellValue(vCell, paramRow);
            }

            if (typeCol != -1) {
                String t = getCellStringValue(row.getCell(typeCol));
                if (t != null && !t.isBlank()) {
                    paramRow.setExplicitType(t.trim().toLowerCase());
                }
            }

            if (locCol != -1) {
                String l = getCellStringValue(row.getCell(locCol));
                if (l != null && !l.isBlank()) {
                    paramRow.setExplicitLocation(l.trim().toLowerCase());
                }
            }
            if (paramRow.getExplicitLocation() == null && sheetParams.getDefaultLocation() != null) {
                paramRow.setExplicitLocation(sheetParams.getDefaultLocation());
            }

            if (enabledCol != -1) {
                String eStr = getCellStringValue(row.getCell(enabledCol));
                if (eStr != null && !eStr.isBlank()) {
                    String clean = eStr.trim().toLowerCase();
                    if ("n".equals(clean) || "no".equals(clean) || "false".equals(clean) || "0".equals(clean)) {
                        paramRow.setEnabled(false);
                    }
                }
            }

            if (notesCol != -1) {
                paramRow.setNotes(getCellStringValue(row.getCell(notesCol)));
            }

            rawRows.add(paramRow);
        }

        // 3. Duplicate parameter handling (§3a.2): Last row wins! Earlier rows marked duplicate
        Map<String, ParamRow> seen = new HashMap<>();
        for (ParamRow pr : rawRows) {
            String normKey = pr.getParameter().trim().toLowerCase(Locale.ROOT);
            if (seen.containsKey(normKey)) {
                ParamRow earlier = seen.get(normKey);
                earlier.setDuplicate(true);
                earlier.setSupersededByRow(pr.getRow());
                earlier.addWarning("DUPLICATE: parameter appears multiple times in sheet; superseded by row " + pr.getRow());

                pr.addWarning("DUPLICATE: parameter appears multiple times in sheet; superseded row " + earlier.getRow());
            }
            seen.put(normKey, pr);
        }
        sheetParams.getRows().addAll(rawRows);
        return List.of(sheetParams);
    }

    void extractCellValue(Cell cell, ParamRow paramRow) {
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

    boolean isNonTopLeftMerged(Cell cell, List<CellRangeAddress> mergedRegions) {
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

        int limit = Math.min((int) maxCol, MAX_COLS_PER_ROW);
        for (int c = minCol; c < limit; c++) {
            Cell cell = row.getCell(c);
            String val = getCellStringValue(cell);
            if (val != null) {
                String clean = val.trim().toLowerCase(Locale.ROOT);
                if (targetHeader.equalsIgnoreCase(clean)) {
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

        int limit = Math.min((int) maxCol, MAX_COLS_PER_ROW);
        for (int c = minCol; c < limit; c++) {
            Cell cell = headerRow.getCell(c);
            String val = getCellStringValue(cell);
            if (val != null && !val.isBlank()) {
                String clean = val.trim().toLowerCase(Locale.ROOT);
                if (REJECTED_HEADERS.contains(clean)) {
                    throw new OverrideException(ReasonCode.WORKBOOK_UNSUPPORTED,
                            "Invalid column header '" + val.trim() + "'. Expected columns: parameter, value, type, location, enabled, notes");
                }
                if (ALLOWED_HEADERS.contains(clean)) {
                    colMap.put(clean, c);
                } else {
                    sheetParams.addSheetWarning("Unknown column header in sheet: '" + val.trim() + "' (ignored).");
                }
            }
        }
    }

    String getCellStringValue(Cell cell) {
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
