package com.example.service.withdraw.stats.amount;

import java.util.List;

import com.example.domain.requests.withdraws.statsbycard.YearMonthCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.withdraw.stats.amount.WithdrawMonthlyAmountResponse;
import com.example.domain.responses.withdraw.stats.amount.WithdrawYearlyAmountResponse;

import io.smallrye.mutiny.Uni;

public interface WithdrawAmountByCardService {
    Uni<ApiResponse<List<WithdrawMonthlyAmountResponse>>> findMonthlyByCardNumber(YearMonthCardNumber req);

    Uni<ApiResponse<List<WithdrawYearlyAmountResponse>>> findYearlyByCardNumber(YearMonthCardNumber req);
}
