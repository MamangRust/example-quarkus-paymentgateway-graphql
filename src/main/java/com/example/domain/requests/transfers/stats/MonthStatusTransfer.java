package com.example.domain.requests.transfers.stats;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.QueryParam;
import lombok.Data;

@Data
public class MonthStatusTransfer {
    @QueryParam("year")
    @Min(value = 2000, message = "Tahun tidak valid")
    @Max(value = 2100, message = "Tahun tidak valid")
    private int year;

    @QueryParam("month")
    @Min(value = 1, message = "Bulan harus antara 1 - 12")
    @Max(value = 12, message = "Bulan harus antara 1 - 12")
    private int month;
}
