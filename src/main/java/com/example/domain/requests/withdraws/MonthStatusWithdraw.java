package com.example.domain.requests.withdraws;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.QueryParam;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MonthStatusWithdraw {

    @QueryParam("year")
    @Min(value = 2000, message = "Tahun tidak valid (minimal 2000)")
    @Max(value = 2100, message = "Tahun tidak valid (maksimal 2100)")
    private Long year;

    @QueryParam("month")
    @Min(value = 1, message = "Bulan harus antara 1 - 12")
    @Max(value = 12, message = "Bulan harus antara 1 - 12")
    private int month;
}
