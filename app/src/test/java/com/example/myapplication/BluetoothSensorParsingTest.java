package com.example.myapplication;

import org.junit.Test;

import static org.junit.Assert.*;

public class BluetoothSensorParsingTest {

    // Replace this with your real parser method later
    private Integer parseSensorPacket(String packet) {
        if (packet == null || packet.trim().isEmpty()) return null;

        // Example expected format: "SENSOR:123"
        if (!packet.startsWith("SENSOR:")) return null;

        String valuePart = packet.substring("SENSOR:".length()).trim();
        if (valuePart.isEmpty()) return null;

        try {
            return Integer.parseInt(valuePart);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Test
    public void validPacket_parsesCorrectly() {
        Integer result = parseSensorPacket("SENSOR:123");
        assertNotNull(result);
        assertEquals(Integer.valueOf(123), result);
    }

    @Test
    public void malformedPacket_returnsNull() {
        assertNull(parseSensorPacket("BAD_PACKET"));
        assertNull(parseSensorPacket("SENSOR:ABC"));
        assertNull(parseSensorPacket("123"));
    }

    @Test
    public void emptyPacket_returnsNull() {
        assertNull(parseSensorPacket(""));
        assertNull(parseSensorPacket("   "));
        assertNull(parseSensorPacket(null));
    }

    @Test
    public void truncatedPacket_returnsNull() {
        assertNull(parseSensorPacket("SENSOR:"));
        assertNull(parseSensorPacket("SENS"));
    }

    @Test
    public void negativeOrZeroValue_handlesSafely() {
        Integer zero = parseSensorPacket("SENSOR:0");
        assertNotNull(zero);
        assertEquals(Integer.valueOf(0), zero);

        Integer negative = parseSensorPacket("SENSOR:-5");
        assertNotNull(negative);
        assertEquals(Integer.valueOf(-5), negative);
    }
}