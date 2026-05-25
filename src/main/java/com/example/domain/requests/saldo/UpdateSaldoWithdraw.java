package com.example.domain.requests.saldo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UpdateSaldoWithdraw {

    @NotBlank(message = "Card number wajib diisi")
    private String cardNumber;

    @Min(value = 50000, message = "Minimal saldo 50.000")
    private Long totalBalance;

    @Min(value = 0, message = "Withdraw amount tidak boleh negatif")
    private Long withdrawAmount;

    private LocalDateTime withdrawTime;
}
