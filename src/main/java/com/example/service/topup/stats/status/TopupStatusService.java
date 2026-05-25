package com.example.service.topup.stats.status;

import java.util.List;

import com.example.domain.requests.topup.stats.MonthTopupStatus;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.topup.stats.status.TopupResponseMonthStatusFailed;
import com.example.domain.responses.topup.stats.status.TopupResponseMonthStatusSuccess;
import com.example.domain.responses.topup.stats.status.TopupResponseYearStatusFailed;
import com.example.domain.responses.topup.stats.status.TopupResponseYearStatusSuccess;

import io.smallrye.mutiny.Uni;

public interface TopupStatusService {
    Uni<ApiResponse<List<TopupResponseMonthStatusSuccess>>> findMonthStatusSuccess(MonthTopupStatus req);

    Uni<ApiResponse<List<TopupResponseYearStatusSuccess>>> findYearlyStatusSuccess(Long year);

    Uni<ApiResponse<List<TopupResponseMonthStatusFailed>>> findMonthStatusFailed(MonthTopupStatus req);

    Uni<ApiResponse<List<TopupResponseYearStatusFailed>>> findYearlyStatusFailed(Long year);
}
