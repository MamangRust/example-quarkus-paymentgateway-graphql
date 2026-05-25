package com.example.entity.card;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CardYearBalance {
    private String year;
    private Long totalBalance;
}
