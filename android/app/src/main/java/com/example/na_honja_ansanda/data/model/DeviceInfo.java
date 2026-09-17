package com.example.na_honja_ansanda.data.model;

public class DeviceInfo {
    private String deviceId;
    private String role;
    private String name;
    private String expiresAt;
    private boolean paired;
    private Integer userNo;

    public String getDeviceId() { return deviceId; }
    public String getRole() { return role; }
    public String getName() { return name; }
    public String getExpiresAt() { return expiresAt; }
    public boolean isPaired() { return paired; }
    public Integer getUserNo() { return userNo; }
}
