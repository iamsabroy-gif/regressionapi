package com.sabyasachi.apiregression.importer;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExcelParameterImporterTest {

    @Test
    void testLoadWorkbook(@TempDir Path tempDir) throws Exception {
        Path excelPath = tempDir.resolve("test_params.xlsx");

        try (Workbook wb = new XSSFWorkbook()) {
            // Sheet 1: GetPetById
            Sheet s1 = wb.createSheet("getPetById");
            Row header1 = s1.createRow(0);
            header1.createCell(0).setCellValue("path");
            header1.createCell(1).setCellValue("value");
            header1.createCell(2).setCellValue("type");
            header1.createCell(3).setCellValue("op");

            Row r1 = s1.createRow(1);
            r1.createCell(0).setCellValue("petId");
            r1.createCell(1).setCellValue(42);
            r1.createCell(2).setCellValue("integer");
            r1.createCell(3).setCellValue("replace");

            Row r2 = s1.createRow(2);
            r2.createCell(0).setCellValue("verbose");
            r2.createCell(1).setCellValue(true);
            r2.createCell(2).setCellValue("boolean");

            // Duplicate row for petId (last wins)
            Row r3 = s1.createRow(3);
            r3.createCell(0).setCellValue("petId");
            r3.createCell(1).setCellValue(99);
            r3.createCell(2).setCellValue("integer");

            // Sheet 2: Method + Path sheet
            Sheet s2 = wb.createSheet("PUT _pets_{petId}");
            Row header2 = s2.createRow(0);
            header2.createCell(0).setCellValue("path");
            header2.createCell(1).setCellValue("value");

            Row r4 = s2.createRow(1);
            r4.createCell(0).setCellValue("body.name");
            r4.createCell(1).setCellValue("Doggie");

            Row r5 = s2.createRow(2);
            r5.createCell(0).setCellValue("body.tag");
            r5.createCell(1).setCellValue("<null>");

            Row r6 = s2.createRow(3);
            r6.createCell(0).setCellValue("body.desc");
            r6.createCell(1).setCellValue("<empty>");

            // Hidden sheet
            Sheet sHidden = wb.createSheet("Notes");
            wb.setSheetHidden(wb.getSheetIndex(sHidden), true);
            Row hRow = sHidden.createRow(0);
            hRow.createCell(0).setCellValue("path");
            hRow.createCell(1).setCellValue("value");

            try (FileOutputStream fos = new FileOutputStream(excelPath.toFile())) {
                wb.write(fos);
            }
        }

        List<SheetParams> sheets = ExcelParameterImporter.load(excelPath);
        assertEquals(2, sheets.size(), "Hidden sheet should be ignored");

        SheetParams sheet1 = sheets.stream()
                .filter(s -> "getPetById".equals(s.getSheetName()))
                .findFirst().orElseThrow();
        assertEquals("getPetById", sheet1.getOperationId());
        // Deduplication: petId row 3 wins over row 1
        assertEquals(2, sheet1.getRows().size());

        ParamRow petIdRow = sheet1.getRows().stream()
                .filter(r -> "petId".equals(r.getPath()))
                .findFirst().orElseThrow();
        assertEquals("99", petIdRow.getRawValue());
        assertEquals("replace", petIdRow.getOp());

        SheetParams sheet2 = sheets.stream()
                .filter(s -> s.getSheetName().startsWith("PUT"))
                .findFirst().orElseThrow();
        assertEquals("PUT", sheet2.getMethod());
        assertEquals("/pets/{petId}", sheet2.getPath());

        ParamRow nullRow = sheet2.getRows().stream()
                .filter(r -> "body.tag".equals(r.getPath()))
                .findFirst().orElseThrow();
        assertEquals("<null>", nullRow.getRawValue());

        ParamRow emptyRow = sheet2.getRows().stream()
                .filter(r -> "body.desc".equals(r.getPath()))
                .findFirst().orElseThrow();
        assertEquals("<empty>", emptyRow.getRawValue());
    }
}
