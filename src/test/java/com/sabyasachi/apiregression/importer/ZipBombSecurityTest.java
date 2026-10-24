package com.sabyasachi.apiregression.importer;

import com.sabyasachi.apiregression.payload.OverrideException;
import com.sabyasachi.apiregression.payload.ReasonCode;
import com.sabyasachi.apiregression.testsupport.XlsxFixtureBuilder;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class ZipBombSecurityTest {

    @Test
    void s5_normalWorkbookStillLoads(@TempDir Path tempDir) throws IOException {
        Path xlsx = tempDir.resolve("normal.xlsx");
        new XlsxFixtureBuilder()
                .sheet("users")
                .target("POST /users")
                .row("name", "Alice")
                .build(xlsx);

        ExcelParameterImporter importer = new ExcelParameterImporter();
        List<SheetParams> params = importer.parse(xlsx, true, 100);
        assertEquals(1, params.size());
        assertEquals("POST /users", params.get(0).getTarget());
        assertEquals(1, params.get(0).getRows().size());
    }

    @Test
    void s5_highRatioWorkbook_rejectedCleanly(@TempDir Path tempDir) throws IOException {
        Path bombFile = tempDir.resolve("zipbomb.xlsx");

        // Construct a ZIP with high compression ratio (e.g. 20 MB of repeated zeros compressing to ~20 KB)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            ZipEntry entry = new ZipEntry("xl/worksheets/sheet1.xml");
            zos.putNextEntry(entry);
            byte[] chunk = new byte[8192];
            // Write ~25 MB of repeated zeros
            for (int i = 0; i < 3000; i++) {
                zos.write(chunk);
            }
            zos.closeEntry();
        }
        Files.write(bombFile, baos.toByteArray());

        ExcelParameterImporter importer = new ExcelParameterImporter();
        OverrideException ex = assertThrows(OverrideException.class, () -> {
            importer.parse(bombFile, true, 100);
        });

        assertEquals(ReasonCode.WORKBOOK_UNSUPPORTED, ex.getReasonCode());
    }
}
