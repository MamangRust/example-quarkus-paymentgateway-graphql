package com.example.entity.transaction;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionYearStatusFailed {
    private String year;
    private Integer totalFailed;
    private Long totalAmount;
}
