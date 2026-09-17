package com.example.na_honja_ansanda.data.model;

import com.google.gson.annotations.SerializedName;

public class IntegratedLog {
    @SerializedName("logId") private Integer logId;
    @SerializedName("userNo") private Integer userNo;
    @SerializedName("logType") private String logType;     // SECURITY, SENSOR, ENV
    @SerializedName("subType") private String subType;     // door_force_open, motion, temperature_humidity 등
    @SerializedName("val1") private Float val1;
    @SerializedName("val2") private Float val2;
    @SerializedName("severity") private String severity;   // low, medium, high
    @SerializedName("description") private String description;
    @SerializedName("createdAt") private String createdAt;

    public Integer getLogId() { return logId; }
    public Integer getUserNo() { return userNo; }
    public String getLogType() { return logType; }
    public String getSubType() { return subType; }
    public Float getVal1() { return val1; }
    public Float getVal2() { return val2; }
    public String getSeverity() { return severity; }
    public String getDescription() { return description; }
    public String getCreatedAt() { return createdAt; }
}