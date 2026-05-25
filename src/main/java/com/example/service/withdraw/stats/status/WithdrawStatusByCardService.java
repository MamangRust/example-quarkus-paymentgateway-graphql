package com.example.service.withdraw.stats.status;

import java.util.List;

import com.example.domain.requests.withdraws.statsbycard.MonthStatusWithdrawCardNumber;
import com.example.domain.requests.withdraws.statsbycard.YearStatusWithdrawCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseMonthStatusFailed;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseMonthStatusSuccess;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseYearStatusFailed;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseYearStatusSuccess;

import io.smallrye.mutiny.Uni;

public interface WithdrawStatusByCardService {
        Uni<ApiResponse<List<WithdrawResponseMonthStatusSuccess>>> findMonthStatusSuccessByCard(
                        MonthStatusWithdrawCardNumber req);

        Uni<ApiResponse<List<WithdrawResponseYearStatusSuccess>>> findYearlyStatusSuccessByCard(
                        YearStatusWithdrawCardNumber req);

        Uni<ApiResponse<List<WithdrawResponseMonthStatusFailed>>> findMonthStatusFailedByCard(
                        MonthStatusWithdrawCardNumber req);

        Uni<ApiResponse<List<WithdrawResponseYearStatusFailed>>> findYearlyStatusFailedByCard(
                        YearStatusWithdrawCardNumber req);
}
