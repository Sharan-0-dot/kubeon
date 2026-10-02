package com.sharan.kubeon.reasoning;

public enum ConfidenceLevel {
    LOW,
    MEDIUM,
    HIGH;

    public static ConfidenceLevel fromString(String val) {
        if (val == null || val.isBlank()) {
            return LOW;
        }
        try {
            return ConfidenceLevel.valueOf(val.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return LOW;
        }
    }
}
