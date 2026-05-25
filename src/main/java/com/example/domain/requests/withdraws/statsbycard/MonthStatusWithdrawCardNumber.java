package com.example.domain.requests.withdraws.statsbycard;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.QueryParam;
import lombok.Data;

@Data
public class MonthStatusWithdrawCardNumber {
    @QueryParam("cardNumber")
    @NotBlank(message = "Card number wajib diisi")
    private String cardNumber;

    @QueryParam("year")
    @Min(value = 2000, message = "Tahun tidak valid")
    @Max(value = 2100, message = "Tahun tidak valid")
    private int year;

    @QueryParam("month")
    @Min(value = 1, message = "Bulan harus antara 1 - 12")
    @Max(value = 12, message = "Bulan harus antara 1 - 12")
    private int month;
}