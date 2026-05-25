package com.example.service.transaction.stats.method;

import java.util.List;

import com.example.domain.requests.transaction.stats.MonthYearPaymentMethod;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transaction.stats.method.TransactionMonthMethodResponse;
import com.example.domain.responses.transaction.stats.method.TransactionYearMethodResponse;

import io.smallrye.mutiny.Uni;

public interface TransactionMethodByCardService {
    Uni<ApiResponse<List<TransactionMonthMethodResponse>>> findMonthlyMethod(MonthYearPaymentMethod req);

    Uni<ApiResponse<List<TransactionYearMethodResponse>>> findYearlyMethod(MonthYearPaymentMethod req);
}
