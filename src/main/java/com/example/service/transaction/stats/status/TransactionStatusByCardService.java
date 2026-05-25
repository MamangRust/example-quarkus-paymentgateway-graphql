package com.example.service.transaction.stats.status;

import java.util.List;

import com.example.domain.requests.transaction.statsbycard.MonthStatusTransactionCardNumber;
import com.example.domain.requests.transaction.statsbycard.YearStatusTransactionCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transaction.stats.status.TransactionResponseMonthStatusFailed;
import com.example.domain.responses.transaction.stats.status.TransactionResponseMonthStatusSuccess;
import com.example.domain.responses.transaction.stats.status.TransactionResponseYearStatusFailed;
import com.example.domain.responses.transaction.stats.status.TransactionResponseYearStatusSuccess;

import io.smallrye.mutiny.Uni;

public interface TransactionStatusByCardService {
        Uni<ApiResponse<List<TransactionResponseMonthStatusSuccess>>> findMonthStatusSuccess(
                        MonthStatusTransactionCardNumber req);

        Uni<ApiResponse<List<TransactionResponseYearStatusSuccess>>> findYearlyStatusSuccess(
                        YearStatusTransactionCardNumber req);

        Uni<ApiResponse<List<TransactionResponseMonthStatusFailed>>> findMonthStatusFailed(
                        MonthStatusTransactionCardNumber req);

        Uni<ApiResponse<List<TransactionResponseYearStatusFailed>>> findYearlyStatusFailed(
                        YearStatusTransactionCardNumber req);
}
