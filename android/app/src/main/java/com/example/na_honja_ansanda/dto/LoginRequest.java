package com.example.na_honja_ansanda.dto;

public class LoginRequest {
    private String id;
    private String pw;

    public LoginRequest(String id, String pw) {
        this.id = id;
        this.pw = pw;
    }

    public String getId() { return id; }
    public String getPw() { return pw; }
}