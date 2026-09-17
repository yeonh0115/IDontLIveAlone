package com.example.na_honja_ansanda.data.model;

import com.google.gson.annotations.SerializedName;

public class FaceInfo {
    @SerializedName("id") private Long id;
    @SerializedName("userId") private String userId;
    @SerializedName("filePath1") private String filePath1;
    @SerializedName("filePath2") private String filePath2;
    @SerializedName("filePath3") private String filePath3;
    @SerializedName("updatedAt") private String updatedAt;

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public String getFilePath1() { return filePath1; }
    public String getFilePath2() { return filePath2; }
    public String getFilePath3() { return filePath3; }
    public String getUpdatedAt() { return updatedAt; }
}