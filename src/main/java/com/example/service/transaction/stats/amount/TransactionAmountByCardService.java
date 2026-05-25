package com.example.service.transaction.stats.amount;

import java.util.List;

import com.example.domain.requests.transaction.stats.MonthYearPaymentMethod;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transaction.stats.amount.TransactionMonthAmountResponse;
import com.example.domain.responses.transaction.stats.amount.TransactionYearlyAmountResponse;

import io.smallrye.mutiny.Uni;

public interface TransactionAmountByCardService {
    Uni<ApiResponse<List<TransactionMonthAmountResponse>>> findMonthlyAmounts(MonthYearPaymentMethod req);

    Uni<ApiResponse<List<TransactionYearlyAmountResponse>>> findYearlyAmounts(MonthYearPaymentMethod req);
}
