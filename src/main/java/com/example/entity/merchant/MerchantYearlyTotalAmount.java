package com.example.entity.merchant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MerchantYearlyTotalAmount {
    private String year;
    private Long totalAmount;
}