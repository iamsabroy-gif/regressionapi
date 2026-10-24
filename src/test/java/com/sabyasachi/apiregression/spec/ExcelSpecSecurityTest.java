package com.sabyasachi.apiregression.spec;

import com.sabyasachi.apiregression.payload.OverrideException;
import com.sabyasachi.apiregression.payload.ReasonCode;
import com.sabyasachi.apiregression.testsupport.SpecXlsxBuilder;
import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

public class ExcelSpecSecurityTest {

    @Test
    void zipNamedJson_rejected(@TempDir Path tempDir) throws IOException {
        Path fakeJson = tempDir.resolve("spec.json");
        // Write PK zip header into .json file
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(fakeJson))) {
            zos.putNextEntry(new ZipEntry("test.txt"));
            zos.write("hello".getBytes());
            zos.closeEntry();
        }

        ProviderSpecLoader.LoadedSpec loaded = ProviderSpecLoader.load(fakeJson);
        assertNull(loaded.getOpenAPI());
        assertTrue(loaded.getMessages().stream().anyMatch(m -> m.contains("SPEC_PARSE_FAILED") || m.contains("ZIP")));
    }

    @Test
    void nonZipOrOle2_rejected(@TempDir Path tempDir) throws IOException {
        Path invalidXlsx = tempDir.resolve("bad.xlsx");
        Files.writeString(invalidXlsx, "<html><body>Not an excel file</body></html>");

        assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(invalidXlsx));
    }

    @Test
    void caps_rowCount_cappedBeforeIteration(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("too_many_rows.xlsx");
        SpecXlsxBuilder.SheetBuilder sb = SpecXlsxBuilder.create()
                .sheet("getCustDetails")
                .operation("POST /getCustDetails")
                .header("Field", "Example");

        for (int i = 0; i < 2005; i++) {
            sb.row(0, "field" + i, "val", null, null, null);
        }
        sb.build(path);

        OverrideException ex = assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(path));
        assertEquals(ReasonCode.SPEC_LIMIT_EXCEEDED, ex.getReasonCode());
        assertTrue(ex.getMessage().contains("row count") || ex.getMessage().contains("2000"));
    }

    @Test
    void caps_depthExceeded_rejected(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("too_deep.xlsx");
        SpecXlsxBuilder.SheetBuilder sb = SpecXlsxBuilder.create()
                .sheet("getCustDetails")
                .operation("POST /getCustDetails")
                .header("Field", "Example");

        for (int d = 0; d <= 9; d++) {
            sb.row(d, "level" + d, null, "object", null, null);
        }
        sb.build(path);

        OverrideException ex = assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(path));
        assertEquals(ReasonCode.SPEC_LIMIT_EXCEEDED, ex.getReasonCode());
        assertTrue(ex.getMessage().contains("depth") || ex.getMessage().contains("8"));
    }

    @Test
    void caps_fileSizeOver5MB_rejected(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("huge.xlsx");
        // Create a 5.5 MB file with random or dummy content
        byte[] buffer = new byte[1024 * 1024];
        try (var fos = Files.newOutputStream(path)) {
            for (int i = 0; i < 6; i++) {
                fos.write(buffer);
            }
        }

        OverrideException ex = assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(path));
        assertEquals(ReasonCode.SPEC_LIMIT_EXCEEDED, ex.getReasonCode());
        assertTrue(ex.getMessage().contains("5 MB"));
    }

    @Test
    void keyWithRefOrHash_rejected(@TempDir Path tempDir) throws Exception {
        Path path1 = tempDir.resolve("ref_key.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("POST /test")
                .header("Field", "Example")
                .row(0, "$ref", "http://evil.com", null, null, null)
                .build(path1);

        OverrideException ex1 = assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(path1));
        assertEquals(ReasonCode.SPEC_LAYOUT_INVALID, ex1.getReasonCode());

        Path path2 = tempDir.resolve("hash_key.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("POST /test")
                .header("Field", "Example")
                .row(0, "#/definitions/evil", "val", null, null, null)
                .build(path2);

        OverrideException ex2 = assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(path2));
        assertEquals(ReasonCode.SPEC_LAYOUT_INVALID, ex2.getReasonCode());
    }

    @Test
    void keyWithControlChars_rejected(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("ctrl_key.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("POST /test")
                .header("Field", "Example")
                .row(0, "bad\nkey", "val", null, null, null)
                .build(path);

        OverrideException ex = assertThrows(OverrideException.class, () -> ProviderSpecLoader.loadOpenAPI(path));
        assertEquals(ReasonCode.SPEC_LAYOUT_INVALID, ex.getReasonCode());
    }

    @Test
    void reDosPattern_acceptedAtLoad_guardedAtValidation(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("redos.xlsx");
        String evilRegex = "^(a+)+$";
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("POST /test")
                .header("Field", "Example", "Type", "Required", "Pattern")
                .row(0, "payload", "aaa", "string", "Y", evilRegex)
                .build(path);

        // Pattern compile succeeds at load time
        OpenAPI api = ProviderSpecLoader.loadOpenAPI(path);
        assertNotNull(api);
        io.swagger.v3.oas.models.media.Schema<?> schema = api.getPaths().get("/test").getPost().getRequestBody().getContent().get("application/json").getSchema();
        assertEquals(evilRegex, ((io.swagger.v3.oas.models.media.Schema<?>) schema.getProperties().get("payload")).getPattern());
    }

    @Test
    void externalLinks_warnsWithoutNetworkIo(@TempDir Path tempDir) throws Exception {
        Path path = tempDir.resolve("external_links.xlsx");
        SpecXlsxBuilder.create()
                .sheet("test")
                .operation("POST /test")
                .header("Field", "Example")
                .row(0, "id", "123", "string", "Y", null)
                .build(path);

        // Add externalLink part into OPCPackage
        try (org.apache.poi.openxml4j.opc.OPCPackage pkg = org.apache.poi.openxml4j.opc.OPCPackage.open(path.toFile(), org.apache.poi.openxml4j.opc.PackageAccess.READ_WRITE)) {
            org.apache.poi.openxml4j.opc.PackagePartName partName = org.apache.poi.openxml4j.opc.PackagingURIHelper.createPartName("/xl/externalLinks/externalLink1.xml");
            pkg.createPart(partName, "application/vnd.openxmlformats-officedocument.spreadsheetml.externalLink+xml");
            pkg.flush();
        }

        ProviderSpecLoader.LoadedSpec loaded = ExcelSpecLoader.load(path);
        OpenAPI api = loaded.getOpenAPI();
        assertNotNull(api);
        assertTrue(loaded.getMessages().stream().anyMatch(m -> m.contains("external links")));
    }
}
