package com.example.service.merchant.stats.totalamount;

import java.util.List;

import com.example.domain.requests.merchant.statsbyid.MonthYearTotalAmountMerchant;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.merchant.stats.total_amount.MerchantResponseMonthlyTotalAmount;
import com.example.domain.responses.merchant.stats.total_amount.MerchantResponseYearlyTotalAmount;

import io.smallrye.mutiny.Uni;

public interface MerchantTotalAmountByIdService {
    Uni<ApiResponse<List<MerchantResponseMonthlyTotalAmount>>> findMonthTotalAmountById(
            MonthYearTotalAmountMerchant req);

    Uni<ApiResponse<List<MerchantResponseYearlyTotalAmount>>> findYearTotalAmountById(MonthYearTotalAmountMerchant req);
}
