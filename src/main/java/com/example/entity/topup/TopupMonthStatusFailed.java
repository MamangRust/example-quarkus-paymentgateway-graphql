package com.example.entity.topup;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TopupMonthStatusFailed {
    private String year;
    private String month;
    private Integer totalFailed;
    private Long totalAmount;
}
