package com.sabyasachi.apiregression;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExampleWorkbookGeneratorTest {

    @Test
    void generateMockRunsParamsWorkbook() throws IOException {
        Path target = Path.of("examples/mock_runs_params.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            // Sheet 1: Valid Customer
            Sheet s1 = wb.createSheet("Valid Customer");
            Row h1 = s1.createRow(0);
            h1.createCell(0).setCellValue("CustId");
            h1.createCell(1).setCellValue("accountNum");
            h1.createCell(2).setCellValue("SessionContext.userid");
            h1.createCell(3).setCellValue("run");

            Row r1 = s1.createRow(1);
            r1.createCell(0).setCellValue("100001");
            r1.createCell(1).setCellValue("87654321");
            r1.createCell(2).setCellValue("U-01");
            r1.createCell(3).setCellValue("run-1");

            Row r2 = s1.createRow(2);
            r2.createCell(0).setCellValue("100002");
            r2.createCell(1).setCellValue("87654322");
            r2.createCell(2).setCellValue("U-02");
            r2.createCell(3).setCellValue("run-2");

            Row r3 = s1.createRow(3);
            r3.createCell(0).setCellValue("100003");
            r3.createCell(1).setCellValue("87654323");
            r3.createCell(2).setCellValue("U-03");
            r3.createCell(3).setCellValue("run-3");

            // Sheet 2: Bad CustId
            Sheet s2 = wb.createSheet("Bad CustId");
            Row h2 = s2.createRow(0);
            h2.createCell(0).setCellValue("CustId");
            h2.createCell(1).setCellValue("run");
            h2.createCell(2).setCellValue("expectedStatus");

            Row br1 = s2.createRow(1);
            br1.createCell(0).setCellValue("999999");
            br1.createCell(1).setCellValue("not-found");
            br1.createCell(2).setCellValue(404);

            Row br2 = s2.createRow(2);
            br2.createCell(0).setCellValue("ABC-INVALID");
            br2.createCell(1).setCellValue("invalid-format");
            br2.createCell(2).setCellValue(400);

            try (FileOutputStream fos = new FileOutputStream(target.toFile())) {
                wb.write(fos);
            }
        }
        assertTrue(java.nio.file.Files.exists(target));
    }
}
