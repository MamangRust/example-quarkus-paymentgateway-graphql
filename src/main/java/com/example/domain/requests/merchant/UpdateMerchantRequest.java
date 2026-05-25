package com.example.domain.requests.merchant;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateMerchantRequest {
    private Long merchantId;

    @NotBlank(message = "Nama merchant wajib diisi")
    private String name;

    @Min(value = 1, message = "User ID minimal 1")
    private Long userId;

    @NotBlank(message = "Status wajib diisi")
    private String status;
}
