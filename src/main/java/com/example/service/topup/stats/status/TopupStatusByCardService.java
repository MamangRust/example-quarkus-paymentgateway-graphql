package com.example.service.topup.stats.status;

import java.util.List;

import com.example.domain.requests.topup.statsbycard.MonthTopupStatusCardNumber;
import com.example.domain.requests.topup.statsbycard.YearTopupStatusCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.topup.stats.status.TopupResponseMonthStatusFailed;
import com.example.domain.responses.topup.stats.status.TopupResponseMonthStatusSuccess;
import com.example.domain.responses.topup.stats.status.TopupResponseYearStatusFailed;
import com.example.domain.responses.topup.stats.status.TopupResponseYearStatusSuccess;

import io.smallrye.mutiny.Uni;

public interface TopupStatusByCardService {
    Uni<ApiResponse<List<TopupResponseMonthStatusSuccess>>> findMonthStatusSuccess(MonthTopupStatusCardNumber req);

    Uni<ApiResponse<List<TopupResponseYearStatusSuccess>>> findYearlyStatusSuccess(YearTopupStatusCardNumber req);

    Uni<ApiResponse<List<TopupResponseMonthStatusFailed>>> findMonthStatusFailed(MonthTopupStatusCardNumber req);

    Uni<ApiResponse<List<TopupResponseYearStatusFailed>>> findYearlyStatusFailed(YearTopupStatusCardNumber req);
}
