package com.example.service.transfer.stats.amount;

import java.util.List;

import com.example.domain.requests.transfers.statsbycard.MonthYearCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transfer.stats.amount.TransferMonthAmountResponse;
import com.example.domain.responses.transfer.stats.amount.TransferYearAmountResponse;

import io.smallrye.mutiny.Uni;

public interface TransferAmountByCardService {
    Uni<ApiResponse<List<TransferMonthAmountResponse>>> findMonthlyAmountsBySender(MonthYearCardNumber req);

    Uni<ApiResponse<List<TransferMonthAmountResponse>>> findMonthlyAmountsByReceiver(MonthYearCardNumber req);

    Uni<ApiResponse<List<TransferYearAmountResponse>>> findYearlyAmountsBySender(MonthYearCardNumber req);

    Uni<ApiResponse<List<TransferYearAmountResponse>>> findYearlyAmountsByReceiver(MonthYearCardNumber req);
}
