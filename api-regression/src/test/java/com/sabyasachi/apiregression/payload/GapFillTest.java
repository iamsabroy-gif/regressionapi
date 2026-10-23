package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.sabyasachi.apiregression.payload.ConstraintPolicy;
import com.sabyasachi.apiregression.payload.OverrideAction;
import com.sabyasachi.apiregression.model.TestCase;
import com.sabyasachi.apiregression.model.TestSuite;
import com.sabyasachi.apiregression.spec.ConstraintValidator;
import com.sabyasachi.apiregression.spec.ProviderSpecLoader;
import com.sabyasachi.apiregression.spec.SchemaNavigator;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class GapFillTest {

    @Test
    public void testSchemaNavigatorFindsLeaf() throws Exception {
        Path specPath = Path.of("src/test/resources/specs/petstore-oas3.json");
        OpenAPI openAPI = ProviderSpecLoader.loadOpenAPI(specPath);
        assertNotNull(openAPI);

        SchemaNavigator navigator = new SchemaNavigator(openAPI);
        Schema petSchema = openAPI.getComponents().getSchemas().get("Pet");
        assertNotNull(petSchema);

        List<SchemaNavigator.ResolvedField> fields = navigator.findSchemaPathsForLeaf(petSchema, "name");
        assertFalse(fields.isEmpty());
        assertEquals("$.name", fields.get(0).getJsonPointer());
    }

    @Test
    public void testConstraintValidatorWarnVsStrict() {
        StringSchema emailSchema = new StringSchema();
        emailSchema.setFormat("email");

        // Invalid email format under WARN
        ConstraintValidator.ValidationResult warnRes = ConstraintValidator.validate(
                emailSchema,
                new TextNode("invalid-email"),
                "invalid-email",
                ConstraintPolicy.WARN,
                false
        );
        assertTrue(warnRes.isValid());
        assertNotNull(warnRes.getWarningMessage());

        // Invalid email format under STRICT
        ConstraintValidator.ValidationResult strictRes = ConstraintValidator.validate(
                emailSchema,
                new TextNode("invalid-email"),
                "invalid-email",
                ConstraintPolicy.STRICT,
                false
        );
        assertFalse(strictRes.isValid());
        assertNotNull(strictRes.getErrorMessage());

        // ReadOnly add
        IntegerSchema idSchema = new IntegerSchema();
        idSchema.setReadOnly(true);
        ConstraintValidator.ValidationResult readOnlyAdd = ConstraintValidator.validate(
                idSchema,
                new IntNode(123),
                "123",
                ConstraintPolicy.WARN,
                true // isAddFromSpec
        );
        assertFalse(readOnlyAdd.isValid(), "Adding readOnly property from spec must always error");
    }

    @Test
    public void testGapFillCreatesIntermediatesAndAddsFromSpec(@TempDir Path tempDir) throws Exception {
        Path specPath = Path.of("src/test/resources/specs/petstore-oas3.json");

        TestCase tc = new TestCase();
        tc.setName("Update Existing Pet");
        tc.setMethod("PUT");
        tc.setUrl("https://api.example.com/v1/pets/10");
        tc.setHeaders(new LinkedHashMap<>());
        // Initial body is minimal, missing category and price
        tc.setBodyTemplate("{\n  \"id\": 10,\n  \"name\": \"Fluffy\"\n}");

        TestSuite suite = new TestSuite("Pet Suite");
        suite.setTestCases(new ArrayList<>(List.of(tc)));

        Path excelPath = tempDir.resolve("gap_fill_params.xlsx");
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Update Existing Pet");
            Row h = sheet.createRow(0);
            h.createCell(0).setCellValue("parameter");
            h.createCell(1).setCellValue("value");
            h.createCell(2).setCellValue("type");

            // 1. Add missing category object and its name (nested intermediate creation)
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("category.name");
            r1.createCell(1).setCellValue("Domestic Cats");
            r1.createCell(2).setCellValue("string");

            // 2. Add missing scalar field from Pet schema
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("price");
            r2.createCell(1).setCellValue(35.50);
            r2.createCell(2).setCellValue("number");

            // 3. Deliberate NOT_IN_SPEC field
            Row r3 = sheet.createRow(3);
            r3.createCell(0).setCellValue("unknownFieldXYZ");
            r3.createCell(1).setCellValue("val");

            try (FileOutputStream fos = new FileOutputStream(excelPath.toFile())) {
                wb.write(fos);
            }
        }

        OverrideOptions options = new OverrideOptions();
        options.setConstraintPolicy(ConstraintPolicy.WARN);
        ParameterOverrideService service = new ParameterOverrideService();

        OverrideReport report = service.dryRun(suite, excelPath, specPath, options);
        assertNotNull(report);
        assertEquals(3, report.getEntries().size());

        // Entry 1: category.name ADDED
        OverrideEntry entry1 = report.getEntries().get(0);
        assertEquals(OverrideAction.ADDED, entry1.getAction());
        assertEquals("/category/name", entry1.getResolvedPath());

        // Entry 2: price ADDED
        OverrideEntry entry2 = report.getEntries().get(1);
        assertEquals(OverrideAction.ADDED, entry2.getAction());
        assertEquals("/price", entry2.getResolvedPath());

        // Entry 3: unknownFieldXYZ ERROR NOT_IN_SPEC
        OverrideEntry entry3 = report.getEntries().get(2);
        assertEquals(OverrideAction.ERROR, entry3.getAction());
        assertEquals("NOT_IN_SPEC", entry3.getReasonCode());
    }
}
