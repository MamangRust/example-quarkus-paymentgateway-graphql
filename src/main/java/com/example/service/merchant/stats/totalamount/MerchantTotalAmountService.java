package com.example.service.merchant.stats.totalamount;

import java.util.List;

import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.merchant.stats.total_amount.MerchantResponseMonthlyTotalAmount;
import com.example.domain.responses.merchant.stats.total_amount.MerchantResponseYearlyTotalAmount;

import io.smallrye.mutiny.Uni;

public interface MerchantTotalAmountService {
    Uni<ApiResponse<List<MerchantResponseMonthlyTotalAmount>>> findMonthTotalAmount(Long year);

    Uni<ApiResponse<List<MerchantResponseYearlyTotalAmount>>> findYearTotalAmount(Long year);
}
