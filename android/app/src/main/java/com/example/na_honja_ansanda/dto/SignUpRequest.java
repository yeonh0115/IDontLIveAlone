package com.example.na_honja_ansanda.dto;

public class SignUpRequest {
    private String id;
    private String pw;
    private String name;
    private String phone;
    private String email;

    public SignUpRequest(String id, String pw, String name, String phone, String email) {
        this.id = id;
        this.pw = pw;
        this.name = name;
        this.phone = phone;
        this.email = email;
    }
}