package com.example.service.merchant.stats.method;

import java.util.List;

import com.example.domain.requests.merchant.statsbyid.MonthYearPaymentMethodMerchant;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.merchant.stats.method.MerchantResponseMonthlyPaymentMethod;
import com.example.domain.responses.merchant.stats.method.MerchantResponseYearlyPaymentMethod;

import io.smallrye.mutiny.Uni;

public interface MerchantMethodByIdService {
    Uni<ApiResponse<List<MerchantResponseMonthlyPaymentMethod>>> findMonthMethodById(
            MonthYearPaymentMethodMerchant req);

    Uni<ApiResponse<List<MerchantResponseYearlyPaymentMethod>>> findYearMethodById(MonthYearPaymentMethodMerchant req);
}
