package com.sabyasachi.apiregression.payload;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class JsonTemplateCodec {

    public static final String MASK_PREFIX = "__AGY_UNQUOTED_PH_";
    public static final String MASK_SUFFIX = "__";
    private static final AtomicInteger SENTINEL_COUNTER = new AtomicInteger(1000);

    public enum BodyState {
        ABSENT,
        JSON,
        NOT_JSON
    }

    public static class ParsedBody {
        private final BodyState state;
        private final JsonNode rootNode;
        private final Map<String, String> unquotedPlaceholders;
        private final String failureReason;
        private final String originalRaw;

        private ParsedBody(BodyState state, JsonNode rootNode, Map<String, String> unquotedPlaceholders, String failureReason, String originalRaw) {
            this.state = state;
            this.rootNode = rootNode;
            this.unquotedPlaceholders = unquotedPlaceholders != null ? unquotedPlaceholders : new HashMap<>();
            this.failureReason = failureReason;
            this.originalRaw = originalRaw;
        }

        public static ParsedBody absent(String raw) {
            return new ParsedBody(BodyState.ABSENT, null, new HashMap<>(), null, raw);
        }

        public static ParsedBody json(JsonNode rootNode, Map<String, String> unquotedPlaceholders, String raw) {
            return new ParsedBody(BodyState.JSON, rootNode, unquotedPlaceholders, null, raw);
        }

        public static ParsedBody json(JsonNode rootNode) {
            return new ParsedBody(BodyState.JSON, rootNode, new HashMap<>(), null, "");
        }

        public static ParsedBody notJson(String reason, String raw) {
            return new ParsedBody(BodyState.NOT_JSON, null, new HashMap<>(), reason, raw);
        }

        public BodyState getState() {
            return state;
        }

        public boolean isAbsent() {
            return state == BodyState.ABSENT;
        }

        public boolean isJson() {
            return state == BodyState.JSON;
        }

        public boolean isNotJson() {
            return state == BodyState.NOT_JSON;
        }

        public JsonNode getRootNode() {
            return rootNode;
        }

        public ObjectNode getObjectNode() {
            return (rootNode instanceof ObjectNode) ? (ObjectNode) rootNode : null;
        }

        public ArrayNode getArrayNode() {
            return (rootNode instanceof ArrayNode) ? (ArrayNode) rootNode : null;
        }

        public Map<String, String> getUnquotedPlaceholders() {
            return unquotedPlaceholders;
        }

        public String getFailureReason() {
            return failureReason;
        }

        public String getOriginalRaw() {
            return originalRaw;
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

    private final ObjectMapper mapper;
    private final DefaultPrettyPrinter prettyPrinter;

    public JsonTemplateCodec() {
        this.mapper = new ObjectMapper();
        this.mapper.setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
        this.mapper.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

        DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
        pp.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        pp.indentObjectsWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
        this.prettyPrinter = pp;
    }

    public static boolean isSentinel(String text) {
        return text != null && text.startsWith(MASK_PREFIX) && text.endsWith(MASK_SUFFIX);
    }

    public static String createSentinel(String placeholder) {
        String name = placeholder.trim();
        if (name.startsWith("{{") && name.endsWith("}}")) {
            name = name.substring(2, name.length() - 2).trim();
        }
        return MASK_PREFIX + "RAW_" + name + "_" + SENTINEL_COUNTER.incrementAndGet() + MASK_SUFFIX;
    }

    public static String createSentinel(String varName, Map<String, String> unquotedPlaceholders) {
        String sentinel = MASK_PREFIX + SENTINEL_COUNTER.incrementAndGet() + MASK_SUFFIX;
        if (unquotedPlaceholders != null) {
            unquotedPlaceholders.put(sentinel, varName);
        }
        return sentinel;
    }

    public ParsedBody parse(String bodyTemplate) {
        if (bodyTemplate == null || bodyTemplate.isBlank()) {
            return ParsedBody.absent(bodyTemplate);
        }

        Map<String, String> unquotedMap = new HashMap<>();
        String maskedJson = maskUnquotedPlaceholders(bodyTemplate, unquotedMap);

        try {
            JsonNode root = mapper.readTree(maskedJson);
            if (root.isObject() || root.isArray()) {
                return ParsedBody.json(root, unquotedMap, bodyTemplate);
            } else {
                return ParsedBody.notJson("Root node is not an object or array: " + root.getNodeType(), bodyTemplate);
            }
        } catch (JsonParseException e) {
            return ParsedBody.notJson("Invalid JSON syntax: " + e.getOriginalMessage(), bodyTemplate);
        } catch (Exception e) {
            return ParsedBody.notJson(e.getMessage(), bodyTemplate);
        }
    }

    public DecodedTemplate decode(String bodyTemplate) throws IOException {
        ParsedBody pb = parse(bodyTemplate);
        if (pb.isAbsent()) {
            return new DecodedTemplate(mapper.createObjectNode(), new HashMap<>());
        }
        if (pb.isNotJson()) {
            throw new OverrideException(ReasonCode.BODY_NOT_JSON, "Failed to parse JSON body template: " + pb.getFailureReason());
        }
        if (pb.getObjectNode() != null) {
            return new DecodedTemplate(pb.getObjectNode(), pb.getUnquotedPlaceholders());
        }
        throw new OverrideException(ReasonCode.BODY_NOT_JSON, "Expected JSON object at root, but was array.");
    }

    public String encode(JsonNode rootNode, Map<String, String> unquotedPlaceholders) throws IOException {
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

        // Also restore raw sentinels created without map: e.g. MASK_PREFIX + "RAW_" + name + "_" + id + MASK_SUFFIX
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + MASK_PREFIX + "RAW_(.+?)_\\d+" + MASK_SUFFIX + "\"").matcher(json);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, "{{" + java.util.regex.Matcher.quoteReplacement(m.group(1)) + "}}");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public String encode(ObjectNode rootNode, Map<String, String> unquotedPlaceholders) throws IOException {
        return encode((JsonNode) rootNode, unquotedPlaceholders);
    }

    private static final JsonTemplateCodec DEFAULT_INSTANCE = new JsonTemplateCodec();

    public static JsonNode readTree(String json) {
        if (json == null) return null;
        try {
            ParsedBody pb = DEFAULT_INSTANCE.parse(json);
            return pb.getRootNode();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read JSON tree: " + e.getMessage(), e);
        }
    }

    public static String writeTree(JsonNode root, String originalTemplate) {
        try {
            Map<String, String> unquoted = Collections.emptyMap();
            if (originalTemplate != null && !originalTemplate.isBlank()) {
                ParsedBody pb = DEFAULT_INSTANCE.parse(originalTemplate);
                unquoted = pb.getUnquotedPlaceholders();
            }
            return DEFAULT_INSTANCE.encode(root, unquoted);
        } catch (Exception e) {
            throw new RuntimeException("Failed to write JSON tree: " + e.getMessage(), e);
        }
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
                    // Start of placeholder outside of double quotes
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
