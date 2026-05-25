package com.example.entity.card;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CardMonthBalance {
    private String month;
    private Long totalBalance;
}
