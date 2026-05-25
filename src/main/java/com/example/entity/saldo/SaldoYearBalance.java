package com.example.entity.saldo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SaldoYearBalance {
    private String year;
    private Long totalBalance;
}
