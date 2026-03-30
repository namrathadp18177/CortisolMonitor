package com.example.myapplication;

import org.junit.Test;

import static org.junit.Assert.*;

public class CortisolCalculationLogicTest {

    // Replace these thresholds if your app uses different ones
    private String classifyCortisol(double value) {
        if (value < 5.0) return "Low";
        if (value <= 18.0) return "Normal";
        return "High";
    }

    @Test
    public void cortisolValue_low_isClassifiedLow() {
        assertEquals("Low", classifyCortisol(3.2));
        assertEquals("Low", classifyCortisol(0.0));
    }

    @Test
    public void cortisolValue_normal_isClassifiedNormal() {
        assertEquals("Normal", classifyCortisol(5.0));
        assertEquals("Normal", classifyCortisol(12.4));
        assertEquals("Normal", classifyCortisol(18.0));
    }

    @Test
    public void cortisolValue_high_isClassifiedHigh() {
        assertEquals("High", classifyCortisol(18.1));
        assertEquals("High", classifyCortisol(30.5));
    }

    @Test
    public void cortisolBoundaryValues_areHandledCorrectly() {
        assertEquals("Low", classifyCortisol(4.99));
        assertEquals("Normal", classifyCortisol(5.0));
        assertEquals("Normal", classifyCortisol(18.0));
        assertEquals("High", classifyCortisol(18.01));
    }

    @Test
    public void cortisolNegativeValue_doesNotCrash() {
        String result = classifyCortisol(-1.0);
        assertNotNull(result);
    }
}