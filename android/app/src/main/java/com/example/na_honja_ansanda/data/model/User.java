package com.example.na_honja_ansanda.data.model;

import com.google.gson.annotations.SerializedName;

public class User {
    @SerializedName("userNo") private Integer userNo;
    @SerializedName("userId") private String userId;
    @SerializedName("username") private String username;
    @SerializedName("passwordHash") private String passwordHash;
    @SerializedName("phone") private String phone;
    @SerializedName("email") private String email;
    @SerializedName("doorPassword") private String doorPassword;
    @SerializedName("createdAt") private String createdAt;

    // 💡 핵심: Gson 변환 및 튕김 방지를 위한 avatar 필드 정상 정의
    @SerializedName("avatar") private String avatar;

    // Getter & Setter
    public Integer getUserNo() { return userNo; }
    public void setUserNo(Integer userNo) { this.userNo = userNo; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getDoorPassword() { return doorPassword; }
    public void setDoorPassword(String doorPassword) { this.doorPassword = doorPassword; }

    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    // 💡 에러 원인 해결: MyPageFragment에서 호출할 수 있도록 명확히 추가
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }
}