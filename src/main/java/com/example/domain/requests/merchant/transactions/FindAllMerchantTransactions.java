package com.example.domain.requests.merchant.transactions;

import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class FindAllMerchantTransactions {
    @Min(value = 1, message = "Page minimal 1")
    private Integer page = 1;

    @Min(value = 1, message = "Page size minimal 1")
    private Integer pageSize = 10;

    private String search = "";
}
