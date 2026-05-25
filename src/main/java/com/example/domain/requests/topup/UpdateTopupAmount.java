package com.example.domain.requests.topup;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class UpdateTopupAmount {

    @Min(value = 1, message = "Topup ID wajib diisi")
    private Long topupId;

    @Min(value = 50000, message = "Minimal topup 50.000")
    private Long topupAmount;
}
