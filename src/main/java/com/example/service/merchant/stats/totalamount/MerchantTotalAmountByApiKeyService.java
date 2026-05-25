package com.example.service.merchant.stats.totalamount;

import java.util.List;

import com.example.domain.requests.merchant.statsbyapikey.MonthYearTotalAmountApiKey;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.merchant.stats.total_amount.MerchantResponseMonthlyTotalAmount;
import com.example.domain.responses.merchant.stats.total_amount.MerchantResponseYearlyTotalAmount;

import io.smallrye.mutiny.Uni;

public interface MerchantTotalAmountByApiKeyService {
        Uni<ApiResponse<List<MerchantResponseMonthlyTotalAmount>>> findMonthTotalAmountByApiKey(
                        MonthYearTotalAmountApiKey req);

        Uni<ApiResponse<List<MerchantResponseYearlyTotalAmount>>> findYearTotalAmountByApiKey(
                        MonthYearTotalAmountApiKey req);
}
