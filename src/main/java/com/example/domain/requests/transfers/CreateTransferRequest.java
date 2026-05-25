package com.example.domain.requests.transfers;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreateTransferRequest {
    @NotBlank(message = "Transfer from wajib diisi")
    private String transferFrom;

    @NotBlank(message = "Transfer to wajib diisi")
    private String transferTo;

    @Min(value = 50000, message = "Minimal transfer 50000")
    private Long transferAmount;
}