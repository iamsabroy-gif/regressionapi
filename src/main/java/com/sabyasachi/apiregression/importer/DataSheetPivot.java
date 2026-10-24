package com.sabyasachi.apiregression.importer;

import com.sabyasachi.apiregression.payload.ReasonCode;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;

import java.util.*;

public class DataSheetPivot {

    public static final int MAX_DATA_ROWS = 1000;
    public static final int MAX_PARAMETER_COLUMNS = 50;
    public static final int MAX_HEADER_LENGTH = 128;

    private static class ParamCol {
        final int columnIndex;
        final String name;
        int nonBlankCount = 0;
        int firstBlankRow = -1;

        ParamCol(int columnIndex, String name) {
            this.columnIndex = columnIndex;
            this.name = name;
        }
    }

    public static List<SheetParams> parseDataDrivenSheet(
            Sheet sheet,
            SheetParams sheetParams,
            List<CellRangeAddress> mergedRegions,
            ExcelParameterImporter importer) {

        int firstRowNum = sheet.getFirstRowNum();
        int lastRowNum = sheet.getLastRowNum();
        if (firstRowNum < 0 || lastRowNum < 0 || lastRowNum < firstRowNum) {
            return List.of(sheetParams);
        }

        // 1. Find data-driven header row
        int headerRowNum = -1;
        Row headerRow = null;
        List<ParamCol> paramCols = new ArrayList<>();
        int runCol = -1;
        int expectedStatusCol = -1;
        List<String> headerErrors = new ArrayList<>();
        String duplicateColName = null;
        String capExceededReason = null;

        for (int r = firstRowNum; r <= Math.min(lastRowNum, 50); r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            // Check if this row is purely metadata (e.g. col 0 is "target" or "location")
            Cell cell0 = row.getCell(0);
            String val0 = importer.getCellStringValue(cell0);
            if (val0 != null) {
                String clean0 = val0.trim().toLowerCase(Locale.ROOT);
                if (("target".equals(clean0) || "location".equals(clean0)) && row.getLastCellNum() <= 2) {
                    continue; // Skip metadata rows
                }
            }

            // Inspect cells in this row
            short minCol = row.getFirstCellNum();
            short maxCol = row.getLastCellNum();
            if (minCol < 0 || maxCol < 0) continue;

            List<ParamCol> candidateParams = new ArrayList<>();
            Set<String> seenHeaders = new HashSet<>();
            int candRunCol = -1;
            int candExpectedStatusCol = -1;
            boolean hasHeaderLengthError = false;
            String lengthErrorHeader = null;
            boolean hasDuplicate = false;
            String dupHeader = null;

            int limit = Math.min((int) maxCol, ExcelParameterImporter.MAX_COLS_PER_ROW);
            for (int c = minCol; c < limit; c++) {
                Cell cell = row.getCell(c);
                String val = importer.getCellStringValue(cell);
                if (val == null || val.isBlank()) continue;

                String header = val.trim();
                if (header.length() > MAX_HEADER_LENGTH) {
                    hasHeaderLengthError = true;
                    lengthErrorHeader = header;
                    break;
                }

                String lower = header.toLowerCase(Locale.ROOT);
                if ("parameter".equals(lower)) {
                    // Classic sheet header, not data-driven
                    candidateParams.clear();
                    break;
                } else if ("run".equals(lower)) {
                    candRunCol = c;
                } else if ("expectedstatus".equals(lower) || "expected_status".equals(lower) || "expected status".equals(lower)) {
                    candExpectedStatusCol = c;
                } else if ("note".equals(lower) || "notes".equals(lower)) {
                    // Ignored note column
                } else {
                    if (seenHeaders.contains(lower)) {
                        hasDuplicate = true;
                        dupHeader = header;
                    }
                    seenHeaders.add(lower);
                    candidateParams.add(new ParamCol(c, header));
                }
            }

            if (hasHeaderLengthError) {
                headerRowNum = r;
                capExceededReason = "Header column name exceeds maximum allowed length of " + MAX_HEADER_LENGTH + " characters: " + lengthErrorHeader;
                break;
            }

            if (hasDuplicate) {
                headerRowNum = r;
                duplicateColName = dupHeader;
                break;
            }

            if (!candidateParams.isEmpty()) {
                headerRowNum = r;
                headerRow = row;
                paramCols = candidateParams;
                runCol = candRunCol;
                expectedStatusCol = candExpectedStatusCol;
                break;
            }
        }

        if (headerRowNum == -1) {
            sheetParams.addSheetWarning("Header row with 'parameter' column was not found in sheet.");
            return List.of(sheetParams);
        }

        if (capExceededReason != null) {
            sheetParams.setStatus("NOT_APPLIED");
            sheetParams.setSkipReason(ReasonCode.ROW_CAP_EXCEEDED.name());
            sheetParams.addSheetWarning(capExceededReason);
            return List.of(sheetParams);
        }

        if (duplicateColName != null) {
            sheetParams.setStatus("NOT_APPLIED");
            sheetParams.setSkipReason("DUPLICATE");
            sheetParams.addSheetWarning("DUPLICATE: parameter column appears multiple times in sheet: " + duplicateColName);
            return List.of(sheetParams);
        }

        if (paramCols.size() > MAX_PARAMETER_COLUMNS) {
            sheetParams.setStatus("NOT_APPLIED");
            sheetParams.setSkipReason(ReasonCode.ROW_CAP_EXCEEDED.name());
            sheetParams.addSheetWarning("ROW_CAP_EXCEEDED: Sheet exceeds maximum allowed column limit of " + MAX_PARAMETER_COLUMNS + " columns (" + paramCols.size() + " columns found). Zero rows processed.");
            return List.of(sheetParams);
        }

        // 2. Count candidate data rows & verify row cap (1000)
        int candidateRowCount = 0;
        for (int r = headerRowNum + 1; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;
            boolean hasAnyCell = false;
            short minC = row.getFirstCellNum();
            short maxC = row.getLastCellNum();
            if (minC >= 0 && maxC >= 0) {
                for (int c = minC; c < maxC; c++) {
                    Cell cell = row.getCell(c);
                    String val = importer.getCellStringValue(cell);
                    if (val != null && !val.isBlank()) {
                        hasAnyCell = true;
                        break;
                    }
                }
            }
            if (hasAnyCell) {
                candidateRowCount++;
            }
        }

        if (candidateRowCount > MAX_DATA_ROWS) {
            sheetParams.setStatus("NOT_APPLIED");
            sheetParams.setSkipReason(ReasonCode.ROW_CAP_EXCEEDED.name());
            sheetParams.addSheetWarning("ROW_CAP_EXCEEDED: Sheet exceeds maximum allowed row limit of " + MAX_DATA_ROWS + " rows (" + candidateRowCount + " rows found). Zero rows processed.");
            return List.of(sheetParams);
        }

        // 3. Process data rows
        List<SheetParams> runs = new ArrayList<>();
        List<String> spacerWarnings = new ArrayList<>();

        for (int r = headerRowNum + 1; r <= lastRowNum; r++) {
            Row row = sheet.getRow(r);
            if (row == null) continue;

            // Check if all parameter cells are blank
            boolean allParamsBlank = true;
            for (ParamCol col : paramCols) {
                Cell cell = row.getCell(col.columnIndex);
                String val = importer.getCellStringValue(cell);
                if (val != null && !val.isBlank()) {
                    allParamsBlank = false;
                    break;
                }
            }

            if (allParamsBlank) {
                // Spacer row
                spacerWarnings.add("Spacer row at Excel row " + (r + 1) + " skipped.");
                continue;
            }

            int runIndex = runs.size() + 1;

            // Run label
            String runLabel = null;
            if (runCol != -1) {
                Cell runCell = row.getCell(runCol);
                String runVal = importer.getCellStringValue(runCell);
                if (runVal != null && !runVal.isBlank()) {
                    runLabel = runVal.trim();
                }
            }
            if (runLabel == null) {
                runLabel = String.valueOf(runIndex);
            }

            // Expected status
            Integer runExpectedStatus = null;
            if (expectedStatusCol != -1) {
                Cell esCell = row.getCell(expectedStatusCol);
                if (esCell != null) {
                    if (esCell.getCellType() == CellType.NUMERIC) {
                        runExpectedStatus = (int) Math.round(esCell.getNumericCellValue());
                    } else {
                        String esVal = importer.getCellStringValue(esCell);
                        if (esVal != null && !esVal.isBlank()) {
                            try {
                                runExpectedStatus = Integer.parseInt(esVal.trim());
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                }
            }

            // Extract parameter values for this run
            List<ParamRow> runRows = new ArrayList<>();
            for (ParamCol col : paramCols) {
                Cell cell = row.getCell(col.columnIndex);
                ParamRow paramRow = new ParamRow();
                paramRow.setRow(r + 1);
                paramRow.setParameter(col.name);
                if (sheetParams.getDefaultLocation() != null) {
                    paramRow.setExplicitLocation(sheetParams.getDefaultLocation());
                }
                if (row.getZeroHeight()) {
                    paramRow.addWarning("Row is hidden or filtered.");
                }
                if (cell != null && importer.isNonTopLeftMerged(cell, mergedRegions)) {
                    paramRow.setRawValue(null);
                    paramRow.setCellKindEnum(ParamRow.CellKind.BLANK);
                    paramRow.addWarning("Cell is inside a merged region and is not the top-left cell. Treated as blank.");
                } else {
                    importer.extractCellValue(cell, paramRow);
                }

                if (paramRow.getRawValue() != null && !paramRow.getRawValue().isBlank()) {
                    col.nonBlankCount++;
                } else {
                    if (col.firstBlankRow == -1) {
                        col.firstBlankRow = r + 1;
                    }
                }
                runRows.add(paramRow);
            }

            SheetParams runParams = new SheetParams();
            runParams.setSheetName(sheetParams.getSheetName());
            runParams.setTarget(sheetParams.getTarget());
            runParams.setDefaultLocation(sheetParams.getDefaultLocation());
            runParams.getMetadata().putAll(sheetParams.getMetadata());
            runParams.setRunIndex(runIndex);
            runParams.setRunLabel(runLabel);
            runParams.setRunExpectedStatus(runExpectedStatus);
            runParams.setRows(runRows);
            runs.add(runParams);
        }

        if (runs.isEmpty()) {
            sheetParams.setStatus("NOT_APPLIED");
            sheetParams.setSkipReason("NO_RUNS");
            sheetParams.addSheetWarning("Sheet has no data rows.");
            return List.of(sheetParams);
        }

        // Attach sheet-level warnings to the first run
        SheetParams firstRun = runs.get(0);
        for (String w : sheetParams.getSheetWarnings()) {
            firstRun.addSheetWarning(w);
        }
        for (String sw : spacerWarnings) {
            firstRun.addSheetWarning(sw);
        }

        int totalRuns = runs.size();
        for (ParamCol col : paramCols) {
            if (col.nonBlankCount == 0) {
                firstRun.addSheetWarning("column '" + col.name + "' has no values; ignored");
            } else if (col.nonBlankCount < totalRuns) {
                int unused = totalRuns - col.nonBlankCount;
                String runWord = unused == 1 ? "1 run uses the imported value" : unused + " runs use the imported value";
                String rowNote = col.firstBlankRow != -1 ? " (row " + col.firstBlankRow + ")" : "";
                firstRun.addSheetWarning("column '" + col.name + "': " + col.nonBlankCount + " of " + totalRuns + " runs have a value, " + runWord + rowNote);
            }
        }

        return runs;
    }
}
