package com.example.domain.requests.transaction.statsbycard;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.QueryParam;
import lombok.Data;

@Data
public class MonthStatusTransactionCardNumber {

    @QueryParam("cardNumber")
    @NotBlank(message = "Card number wajib diisi")
    private String cardNumber;

    @QueryParam("year")
    @Min(value = 2000, message = "Tahun tidak valid")
    @Max(value = 2100, message = "Tahun tidak valid")
    private Long year;

    @QueryParam("month")
    @Min(value = 1, message = "Bulan harus >= 1")
    @Max(value = 12, message = "Bulan harus <= 12")
    private int month;
}