package com.example.entity.transaction;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionYearStatusSuccess {
    private String year;
    private Integer totalSuccess;
    private Long totalAmount;
}
