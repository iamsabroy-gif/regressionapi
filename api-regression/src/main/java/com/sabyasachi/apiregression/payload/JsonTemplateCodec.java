package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonTemplateCodec {

    private static final String MASK_PREFIX = "__AGY_UNQUOTED_PH_";
    private static final String MASK_SUFFIX = "__";

    private final ObjectMapper mapper;
    private final DefaultPrettyPrinter prettyPrinter;

    public JsonTemplateCodec() {
        this.mapper = new ObjectMapper();
        this.mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        this.mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.mapper.enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());

        DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
        DefaultPrettyPrinter.Indenter indenter = new DefaultIndenter("  ", "\n");
        pp.indentObjectsWith(indenter);
        pp.indentArraysWith(indenter);
        this.prettyPrinter = pp;
    }

    private static final JsonTemplateCodec DEFAULT_INSTANCE = new JsonTemplateCodec();

    public static ObjectNode readTree(String bodyTemplate) {
        try {
            return DEFAULT_INSTANCE.decode(bodyTemplate).getRootNode();
        } catch (Exception e) {
            throw new RuntimeException("Failed to decode JSON template: " + e.getMessage(), e);
        }
    }

    public static String writeTree(JsonNode node, String originalTemplate) {
        try {
            DecodedTemplate decoded = DEFAULT_INSTANCE.decode(originalTemplate);
            return DEFAULT_INSTANCE.encode((ObjectNode) node, decoded.getUnquotedPlaceholders());
        } catch (Exception e) {
            throw new RuntimeException("Failed to encode JSON template: " + e.getMessage(), e);
        }
    }

    public static class DecodedTemplate {
        private final ObjectNode rootNode;
        private final Map<String, String> unquotedPlaceholders;

        public DecodedTemplate(ObjectNode rootNode, Map<String, String> unquotedPlaceholders) {
            this.rootNode = rootNode;
            this.unquotedPlaceholders = unquotedPlaceholders != null ? unquotedPlaceholders : new HashMap<>();
        }

        public ObjectNode getRootNode() {
            return rootNode;
        }

        public Map<String, String> getUnquotedPlaceholders() {
            return unquotedPlaceholders;
        }
    }

    public DecodedTemplate decode(String bodyTemplate) throws IOException {
        if (bodyTemplate == null || bodyTemplate.isBlank()) {
            return new DecodedTemplate(mapper.createObjectNode(), new HashMap<>());
        }

        Map<String, String> unquotedMap = new HashMap<>();
        String maskedJson = maskUnquotedPlaceholders(bodyTemplate, unquotedMap);

        JsonNode root = mapper.readTree(maskedJson);
        if (!root.isObject()) {
            throw new IllegalArgumentException("JSON template body must be a JSON object, but was: " + root.getNodeType());
        }

        return new DecodedTemplate((ObjectNode) root, unquotedMap);
    }

    public String encode(ObjectNode rootNode, Map<String, String> unquotedPlaceholders) throws IOException {
        if (rootNode == null) {
            return "";
        }

        String json = mapper.writer(prettyPrinter).writeValueAsString(rootNode);

        // Restore unquoted placeholders: replace "\"__AGY_UNQUOTED_PH_id__\"" with "{{var}}"
        if (unquotedPlaceholders != null && !unquotedPlaceholders.isEmpty()) {
            for (Map.Entry<String, String> entry : unquotedPlaceholders.entrySet()) {
                String sentinelQuoted = "\"" + entry.getKey() + "\"";
                String originalUnquoted = "{{" + entry.getValue() + "}}";
                json = json.replace(sentinelQuoted, originalUnquoted);
            }
        }

        return json;
    }

    public ObjectMapper getMapper() {
        return mapper;
    }

    private String maskUnquotedPlaceholders(String input, Map<String, String> placeholderMap) {
        StringBuilder sb = new StringBuilder();
        boolean insideString = false;
        boolean escape = false;
        int len = input.length();
        int counter = 0;

        for (int i = 0; i < len; i++) {
            char c = input.charAt(i);

            if (insideString) {
                sb.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    insideString = false;
                }
            } else {
                if (c == '"') {
                    insideString = true;
                    sb.append(c);
                } else if (c == '{' && i + 1 < len && input.charAt(i + 1) == '{') {
                    // Start of placeholder outside of double quotes!
                    int end = input.indexOf("}}", i + 2);
                    if (end != -1) {
                        String varName = input.substring(i + 2, end).trim();
                        String sentinel = MASK_PREFIX + (++counter) + MASK_SUFFIX;
                        placeholderMap.put(sentinel, varName);
                        sb.append("\"").append(sentinel).append("\"");
                        i = end + 1; // skip past }}
                    } else {
                        sb.append(c);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        return sb.toString();
    }
}
