package com.example.service.withdraw.stats.status;

import java.util.List;

import com.example.domain.requests.withdraws.MonthStatusWithdraw;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseMonthStatusFailed;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseMonthStatusSuccess;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseYearStatusFailed;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseYearStatusSuccess;

import io.smallrye.mutiny.Uni;

public interface WithdrawStatusService {
    Uni<ApiResponse<List<WithdrawResponseMonthStatusSuccess>>> findMonthStatusSuccess(MonthStatusWithdraw req);

    Uni<ApiResponse<List<WithdrawResponseYearStatusSuccess>>> findYearlyStatusSuccess(Long year);

    Uni<ApiResponse<List<WithdrawResponseMonthStatusFailed>>> findMonthStatusFailed(MonthStatusWithdraw req);

    Uni<ApiResponse<List<WithdrawResponseYearStatusFailed>>> findYearlyStatusFailed(Long year);
}
