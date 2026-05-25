package com.example.service.card.statsbycard;

import java.util.List;

import com.example.domain.requests.card.MonthYearCardNumberCard;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.card.stats.balance.CardResponseMonthBalance;
import com.example.domain.responses.card.stats.balance.CardResponseYearBalance;

import io.smallrye.mutiny.Uni;

public interface CardBalanceByCardService {
    Uni<ApiResponse<List<CardResponseMonthBalance>>> findMonthBalanceByCard(MonthYearCardNumberCard req);

    Uni<ApiResponse<List<CardResponseYearBalance>>> findYearBalanceByCard(MonthYearCardNumberCard req);
}
