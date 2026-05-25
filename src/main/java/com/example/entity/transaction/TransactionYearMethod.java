package com.example.entity.transaction;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransactionYearMethod {
    private String year;
    private String paymentMethod;
    private Integer totalTransactions;
    private Long totalAmount;
}
