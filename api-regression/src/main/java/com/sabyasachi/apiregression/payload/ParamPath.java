package com.sabyasachi.apiregression.payload;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            return isArray() ? "[" + arrayIndex + "]" : propertyName;
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
            throw new IllegalArgumentException("Parameter path cannot be null or empty.");
        }

        String raw = input.trim();

        // Guard against wildcards, recursive descent, filters
        if (raw.contains("*")) {
            throw new IllegalArgumentException("Wildcards ('*') are not permitted in parameter path: " + raw);
        }
        if (raw.contains("..")) {
            throw new IllegalArgumentException("Recursive descent ('..') is not permitted in parameter path: " + raw);
        }
        if (raw.contains("?(") || raw.contains("?@")) {
            throw new IllegalArgumentException("Filters ('?()') are not permitted in parameter path: " + raw);
        }

        // Check if explicit path: starts with '$', or contains '.' or '['
        boolean explicit = raw.startsWith("$") || raw.contains(".") || raw.contains("[");

        String toTokenize = raw;
        if (toTokenize.startsWith("$.")) {
            toTokenize = toTokenize.substring(2);
        } else if (toTokenize.startsWith("$")) {
            toTokenize = toTokenize.substring(1);
        }

        List<Segment> segList = new ArrayList<>();

        if (!explicit) {
            // Bare name
            segList.add(new Segment(raw));
            return new ParamPath(raw, segList, false, raw);
        }

        // Tokenize explicit path: handles dot segments `.foo`, array brackets `[0]`, empty `[]`, and bracket quotes `['foo.bar']`
        Pattern tokenPattern = Pattern.compile("\\[(['\"])(.*?)\\1\\]|\\[(\\d+)\\]|(\\[\\])|\\.([^.\\[]+)|([^.\\[]+)");
        Matcher matcher = tokenPattern.matcher(toTokenize);

        while (matcher.find()) {
            if (matcher.group(2) != null) {
                // Bracket quote: ['odd.key']
                segList.add(new Segment(matcher.group(2)));
            } else if (matcher.group(3) != null) {
                // Array index: [0]
                int idx = Integer.parseInt(matcher.group(3));
                segList.add(new Segment(idx));
            } else if (matcher.group(4) != null) {
                // Empty array bracket: [] (append marker, index Integer.MAX_VALUE)
                segList.add(new Segment(Integer.MAX_VALUE));
            } else if (matcher.group(5) != null) {
                // Dot segment: .city
                String seg = matcher.group(5).trim();
                if (!seg.isEmpty()) {
                    segList.add(new Segment(seg));
                }
            } else if (matcher.group(6) != null) {
                // Start segment: customer
                String seg = matcher.group(6).trim();
                if (!seg.isEmpty()) {
                    segList.add(new Segment(seg));
                }
            }
        }

        if (segList.isEmpty()) {
            segList.add(new Segment(raw));
        }

        // Leaf name
        String leaf = raw;
        for (int i = segList.size() - 1; i >= 0; i--) {
            Segment s = segList.get(i);
            if (!s.isArray() && s.getPropertyName() != null && !s.getPropertyName().isEmpty()) {
                leaf = s.getPropertyName();
                break;
            }
        }

        return new ParamPath(raw, segList, true, leaf);
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
        StringBuilder sb = new StringBuilder();
        for (Segment s : segments) {
            sb.append("/");
            if (s.isArray()) {
                sb.append(s.getArrayIndex());
            } else {
                // JSON Pointer escaping: ~ -> ~0, / -> ~1
                String name = s.getPropertyName();
                sb.append(name.replace("~", "~0").replace("/", "~1"));
            }
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return rawPath;
    }
}
