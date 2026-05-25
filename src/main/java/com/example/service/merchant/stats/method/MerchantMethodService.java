package com.example.service.merchant.stats.method;

import java.util.List;

import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.merchant.stats.method.MerchantResponseMonthlyPaymentMethod;
import com.example.domain.responses.merchant.stats.method.MerchantResponseYearlyPaymentMethod;

import io.smallrye.mutiny.Uni;

public interface MerchantMethodService {
    Uni<ApiResponse<List<MerchantResponseMonthlyPaymentMethod>>> findMonthMethod(Long year);

    Uni<ApiResponse<List<MerchantResponseYearlyPaymentMethod>>> findYearMethod(Long year);
}
