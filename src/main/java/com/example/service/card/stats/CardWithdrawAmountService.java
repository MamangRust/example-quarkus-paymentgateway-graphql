package com.example.service.card.stats;

import java.util.List;

import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.card.stats.amount.CardResponseMonthAmount;
import com.example.domain.responses.card.stats.amount.CardResponseYearAmount;

import io.smallrye.mutiny.Uni;

public interface CardWithdrawAmountService {
    Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthAmount(Long year);

    Uni<ApiResponse<List<CardResponseYearAmount>>> findYearAmount(Long year);
}
