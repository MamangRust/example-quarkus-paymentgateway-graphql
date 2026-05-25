package com.example.entity.merchant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MerchantYearlyPaymentMethod {
    private String year;
    private String paymentMethod;
    private Long totalAmount;
}
