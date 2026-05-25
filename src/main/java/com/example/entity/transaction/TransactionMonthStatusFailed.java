package com.example.entity.transaction;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionMonthStatusFailed {
    private String year;
    private String month;
    private Integer totalFailed;
    private Long totalAmount;
}
