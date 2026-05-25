package com.example.entity.topup;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TopupMonthMethod {
    private String month;
    private String topupMethod;
    private Integer totalTopups;
    private Long totalAmount;
}
