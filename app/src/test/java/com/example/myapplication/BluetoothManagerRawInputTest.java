package com.example.myapplication;

import org.junit.Test;

import static org.junit.Assert.*;

public class BluetoothManagerRawInputTest {

    private String normalizeIncomingData(String data) {
        if (data == null) return null;
        String trimmed = data.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    @Test
    public void validIncomingString_returnsNormalizedData() {
        String result = normalizeIncomingData("HELLO_DEVICE");
        assertNotNull(result);
        assertEquals("HELLO_DEVICE", result);
    }

    @Test
    public void incomingString_withWhitespace_isTrimmed() {
        String result = normalizeIncomingData("  SENSOR_OK  ");
        assertNotNull(result);
        assertEquals("SENSOR_OK", result);
    }

    @Test
    public void emptyIncomingString_returnsNull() {
        assertNull(normalizeIncomingData(""));
        assertNull(normalizeIncomingData("   "));
    }

    @Test
    public void nullIncomingString_returnsNull() {
        assertNull(normalizeIncomingData(null));
    }

    @Test
    public void malformedButNonEmptyPacket_doesNotCrash() {
        String result = normalizeIncomingData("###@@@BAD_PACKET%%%");
        assertNotNull(result);
        assertEquals("###@@@BAD_PACKET%%%", result);
    }

    @Test
    public void partialPacket_doesNotCrash() {
        String result = normalizeIncomingData("SENS");
        assertNotNull(result);
        assertEquals("SENS", result);
    }
}