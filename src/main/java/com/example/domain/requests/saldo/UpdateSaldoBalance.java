package com.example.domain.requests.saldo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateSaldoBalance {

    @NotBlank(message = "Card number wajib diisi")
    private String cardNumber;

    @Min(value = 50000, message = "Minimal saldo 50.000")
    private Long totalBalance;
}
