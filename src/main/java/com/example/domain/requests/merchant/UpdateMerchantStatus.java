package com.example.domain.requests.merchant;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateMerchantStatus {
    @Min(value = 1, message = "merchant_id minimal 1")
    private Long merchantId;

    @NotBlank(message = "Status wajib diisi")
    private String status;
}
