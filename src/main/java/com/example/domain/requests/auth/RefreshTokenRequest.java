package com.example.domain.requests.auth;

import lombok.Data;

@Data
public class RefreshTokenRequest {
    public String refreshToken;
}