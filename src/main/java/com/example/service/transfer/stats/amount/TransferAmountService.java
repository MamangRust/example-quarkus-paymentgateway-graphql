package com.example.service.transfer.stats.amount;

import java.util.List;

import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transfer.stats.amount.TransferMonthAmountResponse;
import com.example.domain.responses.transfer.stats.amount.TransferYearAmountResponse;

import io.smallrye.mutiny.Uni;

public interface TransferAmountService {
    Uni<ApiResponse<List<TransferMonthAmountResponse>>> findMonthlyAmounts(Long year);

    Uni<ApiResponse<List<TransferYearAmountResponse>>> findYearlyAmounts(Long year);
}
