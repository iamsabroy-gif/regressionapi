package com.sabyasachi.apiregression.payload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class ParamPath {

    public enum SegmentType {
        PROPERTY,
        ARRAY_INDEX
    }

    public static class Segment {
        private final SegmentType type;
        private final String propertyName;
        private final int arrayIndex;

        public Segment(String propertyName) {
            this.type = SegmentType.PROPERTY;
            this.propertyName = propertyName;
            this.arrayIndex = -1;
        }

        public Segment(int arrayIndex) {
            this.type = SegmentType.ARRAY_INDEX;
            this.propertyName = null;
            this.arrayIndex = arrayIndex;
        }

        public SegmentType getType() {
            return type;
        }

        public boolean isArray() {
            return type == SegmentType.ARRAY_INDEX;
        }

        public String getPropertyName() {
            return propertyName;
        }

        public int getArrayIndex() {
            return arrayIndex;
        }

        @Override
        public String toString() {
            return isArray() ? "[" + (arrayIndex == Integer.MAX_VALUE ? "" : arrayIndex) + "]" : propertyName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Segment segment)) return false;
            return arrayIndex == segment.arrayIndex && type == segment.type && Objects.equals(propertyName, segment.propertyName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, propertyName, arrayIndex);
        }
    }

    private final String rawPath;
    private final List<Segment> segments;
    private final boolean explicitPath;
    private final String leafName;

    private ParamPath(String rawPath, List<Segment> segments, boolean explicitPath, String leafName) {
        this.rawPath = rawPath;
        this.segments = Collections.unmodifiableList(segments);
        this.explicitPath = explicitPath;
        this.leafName = leafName;
    }

    public static ParamPath parse(String input) {
        if (input == null || input.isBlank()) {
            throw new OverrideException(ReasonCode.PATH_INVALID, "Parameter path cannot be null or empty.");
        }

        String raw = input.trim();

        if (raw.contains("*")) {
            throw new OverrideException(ReasonCode.PATH_UNSUPPORTED, "Wildcards ('*') are not permitted in parameter path: " + raw);
        }
        if (raw.contains("..")) {
            throw new OverrideException(ReasonCode.PATH_UNSUPPORTED, "Recursive descent ('..') is not permitted in parameter path: " + raw);
        }
        if (raw.contains("?(") || raw.contains("?@")) {
            throw new OverrideException(ReasonCode.PATH_UNSUPPORTED, "Filters ('?()') are not permitted in parameter path: " + raw);
        }

        // Root reference only: "$"
        if (raw.equals("$")) {
            return new ParamPath(raw, Collections.emptyList(), true, "$");
        }

        boolean explicit = raw.startsWith("$") || raw.contains(".") || raw.contains("[");

        String s = raw;
        if (s.startsWith("$.")) {
            s = s.substring(2);
        } else if (s.startsWith("$[")) {
            s = s.substring(1);
        } else if (s.startsWith("$")) {
            s = s.substring(1);
        }

        if (s.isEmpty()) {
            return new ParamPath(raw, Collections.emptyList(), true, "$");
        }

        if (!explicit) {
            // Bare name
            return new ParamPath(raw, List.of(new Segment(raw)), false, raw);
        }

        List<Segment> segList = new ArrayList<>();
        int i = 0;
        int len = s.length();

        while (i < len) {
            char c = s.charAt(i);

            if (c == '.') {
                if (i == len - 1) {
                    throw new OverrideException(ReasonCode.PATH_INVALID, "Trailing dot in path: " + raw);
                }
                i++;
                if (s.charAt(i) == '.') {
                    throw new OverrideException(ReasonCode.PATH_UNSUPPORTED, "Empty dot segment in path: " + raw);
                }
                continue;
            }

            if (c == '[') {
                i++; // skip '['
                if (i >= len) {
                    throw new OverrideException(ReasonCode.PATH_INVALID, "Unterminated bracket in path: " + raw);
                }

                char quote = s.charAt(i);
                if (quote == '\'' || quote == '"') {
                    // Quoted property: ['foo.bar']
                    i++;
                    int start = i;
                    while (i < len && s.charAt(i) != quote) {
                        i++;
                    }
                    if (i >= len) {
                        throw new OverrideException(ReasonCode.PATH_INVALID, "Unclosed quote in bracket notation: " + raw);
                    }
                    String propName = s.substring(start, i);
                    i++; // skip quote
                    if (i >= len || s.charAt(i) != ']') {
                        throw new OverrideException(ReasonCode.PATH_INVALID, "Expected ']' after quoted property in: " + raw);
                    }
                    i++; // skip ']'
                    segList.add(new Segment(propName));
                } else if (s.charAt(i) == ']') {
                    // Empty bracket: []
                    i++;
                    segList.add(new Segment(Integer.MAX_VALUE));
                } else {
                    // Array index: digits only
                    int start = i;
                    while (i < len && s.charAt(i) != ']') {
                        char digitChar = s.charAt(i);
                        if (!Character.isDigit(digitChar)) {
                            throw new OverrideException(ReasonCode.PATH_INVALID, "Non-digit character in array index: " + raw);
                        }
                        i++;
                    }
                    if (i >= len || s.charAt(i) != ']') {
                        throw new OverrideException(ReasonCode.PATH_INVALID, "Unterminated array index bracket in: " + raw);
                    }
                    String numStr = s.substring(start, i);
                    i++; // skip ']'

                    // Reject leading zero if multiple digits (e.g. 01)
                    if (numStr.length() > 1 && numStr.startsWith("0")) {
                        throw new OverrideException(ReasonCode.PATH_INVALID, "Leading zero not allowed in array index: " + numStr);
                    }
                    try {
                        int idx = Integer.parseInt(numStr);
                        if (idx < 0) {
                            throw new OverrideException(ReasonCode.PATH_INVALID, "Negative array index not allowed: " + idx);
                        }
                        segList.add(new Segment(idx));
                    } catch (NumberFormatException e) {
                        throw new OverrideException(ReasonCode.INDEX_OUT_OF_RANGE, "Array index overflow: " + numStr);
                    }
                }
            } else {
                // Property name up to next '.' or '['
                int start = i;
                while (i < len && s.charAt(i) != '.' && s.charAt(i) != '[') {
                    i++;
                }
                String propName = s.substring(start, i).trim();
                if (propName.isEmpty()) {
                    throw new OverrideException(ReasonCode.PATH_INVALID, "Empty property segment in path: " + raw);
                }
                segList.add(new Segment(propName));
            }
        }

        if (segList.isEmpty()) {
            throw new OverrideException(ReasonCode.PATH_INVALID, "No valid segments parsed from path: " + raw);
        }

        String leaf = raw;
        for (int k = segList.size() - 1; k >= 0; k--) {
            Segment seg = segList.get(k);
            if (!seg.isArray() && seg.getPropertyName() != null && !seg.getPropertyName().isEmpty()) {
                leaf = seg.getPropertyName();
                break;
            }
        }

        return new ParamPath(raw, segList, explicit, leaf);
    }

    public String getRawPath() {
        return rawPath;
    }

    public List<Segment> getSegments() {
        return segments;
    }

    public boolean isExplicitPath() {
        return explicitPath;
    }

    public boolean isBareName() {
        return !explicitPath;
    }

    public String getLeafName() {
        return leafName;
    }

    public String toJsonPointer() {
        if (segments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            sb.append("/");
            if (s.isArray()) {
                sb.append(s.getArrayIndex() == Integer.MAX_VALUE ? "-" : s.getArrayIndex());
            } else {
                sb.append(s.getPropertyName().replace("~", "~0").replace("/", "~1"));
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return rawPath;
    }
}
