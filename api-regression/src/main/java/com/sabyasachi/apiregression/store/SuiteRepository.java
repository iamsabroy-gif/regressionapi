package com.sabyasachi.apiregression.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sabyasachi.apiregression.model.TestSuite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SuiteRepository {

    private final ObjectMapper objectMapper;

    public SuiteRepository() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public SuiteRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void save(TestSuite suite, Path path) throws IOException {
        if (suite == null) {
            throw new IllegalArgumentException("TestSuite cannot be null");
        }
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        objectMapper.writeValue(path.toFile(), suite);
    }

    public TestSuite load(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("File does not exist: " + path);
        }
        return objectMapper.readValue(path.toFile(), TestSuite.class);
    }

    public String toJson(TestSuite suite) throws IOException {
        return objectMapper.writeValueAsString(suite);
    }

    public TestSuite fromJson(String json) throws IOException {
        return objectMapper.readValue(json, TestSuite.class);
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
