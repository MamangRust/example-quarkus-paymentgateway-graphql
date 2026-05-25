package com.example.service.transaction.stats.status;

import java.util.List;

import com.example.domain.requests.transaction.stats.MonthStatusTransaction;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transaction.stats.status.TransactionResponseMonthStatusFailed;
import com.example.domain.responses.transaction.stats.status.TransactionResponseMonthStatusSuccess;
import com.example.domain.responses.transaction.stats.status.TransactionResponseYearStatusFailed;
import com.example.domain.responses.transaction.stats.status.TransactionResponseYearStatusSuccess;

import io.smallrye.mutiny.Uni;

public interface TransactionStatusService {
    Uni<ApiResponse<List<TransactionResponseMonthStatusSuccess>>> findMonthStatusSuccess(MonthStatusTransaction req);

    Uni<ApiResponse<List<TransactionResponseYearStatusSuccess>>> findYearlyStatusSuccess(Long year);

    Uni<ApiResponse<List<TransactionResponseMonthStatusFailed>>> findMonthStatusFailed(MonthStatusTransaction req);

    Uni<ApiResponse<List<TransactionResponseYearStatusFailed>>> findYearlyStatusFailed(Long year);
}
