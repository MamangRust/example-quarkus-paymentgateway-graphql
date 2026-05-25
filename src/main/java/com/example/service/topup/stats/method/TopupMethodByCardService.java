package com.example.service.topup.stats.method;

import java.util.List;

import com.example.domain.requests.topup.stats.YearMonthMethod;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.topup.stats.method.TopupMonthMethodResponse;
import com.example.domain.responses.topup.stats.method.TopupYearlyMethodResponse;

import io.smallrye.mutiny.Uni;

public interface TopupMethodByCardService {
    Uni<ApiResponse<List<TopupMonthMethodResponse>>> findMonthlyMethods(YearMonthMethod req);

    Uni<ApiResponse<List<TopupYearlyMethodResponse>>> findYearlyMethods(YearMonthMethod req);
}
