package com.example.service.transaction.stats.method;

import java.util.List;

import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transaction.stats.method.TransactionMonthMethodResponse;
import com.example.domain.responses.transaction.stats.method.TransactionYearMethodResponse;

import io.smallrye.mutiny.Uni;

public interface TransactionMethodService {
    Uni<ApiResponse<List<TransactionMonthMethodResponse>>> findMonthlyMethod(Long year);

    Uni<ApiResponse<List<TransactionYearMethodResponse>>> findYearlyMethod(Long year);
}
