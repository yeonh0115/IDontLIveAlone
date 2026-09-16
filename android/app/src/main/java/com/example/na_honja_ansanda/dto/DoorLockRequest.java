package com.example.na_honja_ansanda.dto;

import com.google.gson.annotations.SerializedName;

public class DoorLockRequest {

    @SerializedName("userId")
    private String userId;

    @SerializedName("currentAccountPw")
    private String currentAccountPw;

    @SerializedName("newDoorPw")
    private String newDoorPw;

    public DoorLockRequest(String userId, String currentAccountPw, String newDoorPw) {
        this.userId = userId;
        this.currentAccountPw = currentAccountPw;
        this.newDoorPw = newDoorPw;
    }

    public String getUserId() { return userId; }
    public String getCurrentAccountPw() { return currentAccountPw; }
    public String getNewDoorPw() { return newDoorPw; }
}