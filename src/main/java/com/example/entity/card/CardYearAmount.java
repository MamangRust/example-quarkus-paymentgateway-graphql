package com.example.entity.card;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CardYearAmount {
    private String year;
    private Long totalAmount;
}
