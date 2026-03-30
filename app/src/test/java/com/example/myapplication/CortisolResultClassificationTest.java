package com.example.myapplication;

import org.junit.Test;

import static org.junit.Assert.*;

public class CortisolResultClassificationTest {

    private String classifyCortisol(double value) {
        if (value <= 5.0) {
            return "Low";
        } else if (value <= 18.0) {
            return "Normal";
        } else {
            return "High";
        }
    }

    @Test
    public void cortisolValue_low_isClassifiedLow() {
        assertEquals("Low", classifyCortisol(3.2));
        assertEquals("Low", classifyCortisol(0.0));
        assertEquals("Low", classifyCortisol(5.0));
    }

    @Test
    public void cortisolValue_normal_isClassifiedNormal() {
        assertEquals("Normal", classifyCortisol(5.1));
        assertEquals("Normal", classifyCortisol(12.4));
        assertEquals("Normal", classifyCortisol(18.0));
    }

    @Test
    public void cortisolValue_high_isClassifiedHigh() {
        assertEquals("High", classifyCortisol(18.1));
        assertEquals("High", classifyCortisol(30.5));
        assertEquals("High", classifyCortisol(100.0));
    }

    @Test
    public void cortisolBoundaryValues_areHandledCorrectly() {
        assertEquals("Low", classifyCortisol(4.99));
        assertEquals("Low", classifyCortisol(5.0));
        assertEquals("Normal", classifyCortisol(5.01));
        assertEquals("Normal", classifyCortisol(17.99));
        assertEquals("Normal", classifyCortisol(18.0));
        assertEquals("High", classifyCortisol(18.01));
    }

    @Test
    public void cortisolNegativeValue_doesNotCrash() {
        String result = classifyCortisol(-1.0);
        assertNotNull(result);
        assertEquals("Low", result);
    }

    @Test
    public void cortisolVeryLargeValue_doesNotCrash() {
        String result = classifyCortisol(9999.99);
        assertNotNull(result);
        assertEquals("High", result);
    }
}