package com.example.na_honja_ansanda.data.model;

public final class DevicePairing {
    private DevicePairing() { }

    public static String normalizedCode(String value) {
        if (value == null) return "";
        String normalized = value.replace(" ", "").replace("-", "").trim();
        return normalized.matches("[0-9]{8}") ? normalized : "";
    }

    public static boolean isKnownRole(String role) {
        return "CAMERA".equals(role) || "SENSOR".equals(role) || "REPORT".equals(role);
    }

    public static String roleLabel(String role) {
        if ("CAMERA".equals(role)) return "카메라·안면 인식 기기";
        if ("SENSOR".equals(role)) return "현관·센서 기기";
        if ("REPORT".equals(role)) return "PC AI 리포트";
        return "지원하지 않는 기기 종류";
    }
}
