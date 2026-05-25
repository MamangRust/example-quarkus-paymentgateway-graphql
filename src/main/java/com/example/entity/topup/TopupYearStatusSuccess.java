package com.example.entity.topup;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TopupYearStatusSuccess {
    private String year;
    private Integer totalSuccess;
    private Long totalAmount;
}
