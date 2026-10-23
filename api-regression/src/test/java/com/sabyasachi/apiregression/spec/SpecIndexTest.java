package com.sabyasachi.apiregression.spec;

import io.swagger.v3.oas.models.OpenAPI;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SpecIndexTest {

    @Test
    void testOpenApi3LoadingAndLookup() throws Exception {
        Path specPath = Path.of("src/test/resources/specs/petstore-oas3.json");
        OpenAPI openAPI = ProviderSpecLoader.loadOpenAPI(specPath);
        assertNotNull(openAPI);

        SpecIndex index = new SpecIndex(openAPI);

        // Find by operationId
        var opById = index.findByOperationId("getPetById");
        assertTrue(opById.isPresent());
        assertEquals("GET", opById.get().getMethod());
        assertEquals("/pets/{petId}", opById.get().getPathPattern());

        // Check path parameters merged from path-item
        assertNotNull(opById.get().findParameter("petId"));
        assertEquals("path", opById.get().findParameter("petId").getIn());

        // Check operation-level query param
        assertNotNull(opById.get().findParameter("verbose"));
        assertEquals("query", opById.get().findParameter("verbose").getIn());

        // Find by Method and Path
        var opByPath = index.findByMethodAndPath("PUT", "https://api.example.com/v1/pets/123");
        assertTrue(opByPath.isPresent());
        assertEquals("updatePet", opByPath.get().getOperation().getOperationId());
    }

    @Test
    void testSwagger2LoadingAndLookup() throws Exception {
        Path specPath = Path.of("src/test/resources/specs/petstore-swagger2.json");
        OpenAPI openAPI = ProviderSpecLoader.loadOpenAPI(specPath);
        assertNotNull(openAPI);

        SpecIndex index = new SpecIndex(openAPI);
        var op = index.findByOperationId("getPetByIdSwagger2");
        assertTrue(op.isPresent());
        assertEquals("GET", op.get().getMethod());
        assertEquals("/pets/{petId}", op.get().getPathPattern());
    }
}
