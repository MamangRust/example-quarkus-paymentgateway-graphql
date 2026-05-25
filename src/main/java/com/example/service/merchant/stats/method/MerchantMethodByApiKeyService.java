package com.example.service.merchant.stats.method;

import java.util.List;

import com.example.domain.requests.merchant.statsbyapikey.MonthYearPaymentMethodApiKey;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.merchant.stats.method.MerchantResponseMonthlyPaymentMethod;
import com.example.domain.responses.merchant.stats.method.MerchantResponseYearlyPaymentMethod;

import io.smallrye.mutiny.Uni;

public interface MerchantMethodByApiKeyService {
    Uni<ApiResponse<List<MerchantResponseMonthlyPaymentMethod>>> findMonthMethod(MonthYearPaymentMethodApiKey req);

    Uni<ApiResponse<List<MerchantResponseYearlyPaymentMethod>>> findYearMethod(MonthYearPaymentMethodApiKey req);
}
