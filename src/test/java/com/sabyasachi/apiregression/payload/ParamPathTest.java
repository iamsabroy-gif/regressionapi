package com.sabyasachi.apiregression.payload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

public class ParamPathTest {

    @Test
    public void m10_validPaths() {
        ParamPath p1 = ParamPath.parse("$");
        assertEquals("$", p1.getRawPath());
        assertTrue(p1.getSegments().isEmpty());

        ParamPath p2 = ParamPath.parse("$.customer.address[0].city");
        assertEquals(4, p2.getSegments().size());
        assertEquals("customer", p2.getSegments().get(0).getPropertyName());
        assertEquals("address", p2.getSegments().get(1).getPropertyName());
        assertEquals(0, p2.getSegments().get(2).getArrayIndex());
        assertEquals("city", p2.getSegments().get(3).getPropertyName());
        assertEquals("city", p2.getLeafName());

        ParamPath p3 = ParamPath.parse("$['odd.key'].c");
        assertEquals(2, p3.getSegments().size());
        assertEquals("odd.key", p3.getSegments().get(0).getPropertyName());
        assertEquals("c", p3.getSegments().get(1).getPropertyName());

        ParamPath p4 = ParamPath.parse("$['x[0]']");
        assertEquals(1, p4.getSegments().size());
        assertEquals("x[0]", p4.getSegments().get(0).getPropertyName());

        ParamPath p5 = ParamPath.parse("a[0][1]");
        assertEquals(3, p5.getSegments().size());
        assertEquals("a", p5.getSegments().get(0).getPropertyName());
        assertEquals(0, p5.getSegments().get(1).getArrayIndex());
        assertEquals(1, p5.getSegments().get(2).getArrayIndex());
        assertEquals("a", p5.getLeafName());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[-1]",
            "a[-1]",
            "a[01]",
            "$..a",
            "a..b",
            "a.*",
            "a.*.b",
            "$[?(@.x)]",
            "a.",
            "a['unclosed",
            "a[abc]"
    })
    public void m10_invalidPathsThrowOverrideException(String invalidPath) {
        assertThrows(OverrideException.class, () -> ParamPath.parse(invalidPath));
    }
}
