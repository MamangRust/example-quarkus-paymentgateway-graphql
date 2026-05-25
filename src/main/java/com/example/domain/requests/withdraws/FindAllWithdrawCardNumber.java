package com.example.domain.requests.withdraws;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FindAllWithdrawCardNumber {
    @NotBlank(message = "Card number wajib diisi")
    private String cardNumber;

    @Min(value = 1, message = "Page minimal 1")
    private Integer page = 1;

    @Min(value = 1, message = "Page size minimal 1")
    private Integer pageSize = 10;

    private String search = "";
}
