package com.example.domain.requests.saldo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateSaldoRequest {

    @Min(value = 1, message = "Saldo ID minimal 1")
    private Long saldoId;

    @NotBlank(message = "Card number wajib diisi")
    private String cardNumber;

    @Min(value = 1, message = "Total balance wajib diisi")
    private Long totalBalance;
}
