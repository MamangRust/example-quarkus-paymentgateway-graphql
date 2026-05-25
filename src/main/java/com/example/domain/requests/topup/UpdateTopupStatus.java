package com.example.domain.requests.topup;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateTopupStatus {

    @Min(value = 1, message = "Topup ID wajib diisi")
    private Long topupId;

    @NotBlank(message = "Status wajib diisi")
    private String status;
}
