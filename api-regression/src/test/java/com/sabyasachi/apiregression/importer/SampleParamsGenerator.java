package com.sabyasachi.apiregression.importer;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.nio.file.Path;

public class SampleParamsGenerator {

    public static void main(String[] args) throws Exception {
        Path targetPath = Path.of("../examples/sample_params.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            // Sheet 1: createCustomer
            Sheet s1 = wb.createSheet("createCustomer");
            Row h1 = s1.createRow(0);
            h1.createCell(0).setCellValue("parameter");
            h1.createCell(1).setCellValue("value");
            h1.createCell(2).setCellValue("type");
            h1.createCell(3).setCellValue("location");

            Row r1 = s1.createRow(1);
            r1.createCell(0).setCellValue("name");
            r1.createCell(1).setCellValue("John Doe");
            r1.createCell(2).setCellValue("string");

            Row r2 = s1.createRow(2);
            r2.createCell(0).setCellValue("email");
            r2.createCell(1).setCellValue("john.doe@example.com");
            r2.createCell(2).setCellValue("string");

            // Gap fill nested field
            Row r3 = s1.createRow(3);
            r3.createCell(0).setCellValue("address.city");
            r3.createCell(1).setCellValue("San Francisco");
            r3.createCell(2).setCellValue("string");

            // Query parameter
            Row r4 = s1.createRow(4);
            r4.createCell(0).setCellValue("channel");
            r4.createCell(1).setCellValue("mobile");
            r4.createCell(2).setCellValue("string");
            r4.createCell(3).setCellValue("query");

            // Deliberate NOT_IN_SPEC error
            Row r5 = s1.createRow(5);
            r5.createCell(0).setCellValue("invalidFieldXYZ");
            r5.createCell(1).setCellValue("testValue");

            // Sheet 2: Post Data Payload (matches sample_suite.json)
            Sheet s2 = wb.createSheet("Post Data Payload");
            Row h2 = s2.createRow(0);
            h2.createCell(0).setCellValue("parameter");
            h2.createCell(1).setCellValue("value");

            Row r21 = s2.createRow(1);
            r21.createCell(0).setCellValue("company");
            r21.createCell(1).setCellValue("Globex Corporation");

            Row r22 = s2.createRow(2);
            r22.createCell(0).setCellValue("dept");
            r22.createCell(1).setCellValue("Research & Development");

            try (FileOutputStream fos = new FileOutputStream(targetPath.toFile())) {
                wb.write(fos);
            }
        }
        System.out.println("Generated sample_params.xlsx successfully.");
    }
}
