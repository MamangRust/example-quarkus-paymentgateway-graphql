package com.example.entity.topup;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TopupYearAmount {
    private String year;
    private Long totalAmount;
}
