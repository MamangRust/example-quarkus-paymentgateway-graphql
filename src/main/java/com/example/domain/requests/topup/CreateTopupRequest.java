package com.example.domain.requests.topup;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateTopupRequest {

    @NotBlank(message = "Card number wajib diisi")
    private String cardNumber;

    @Min(value = 50000, message = "Minimal topup 50.000")
    private Long topupAmount;

    @NotBlank(message = "Topup method wajib diisi")
    private String topupMethod;
}
