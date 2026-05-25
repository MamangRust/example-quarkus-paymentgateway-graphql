package com.example.service.card.stats;

import java.util.List;

import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.card.stats.balance.CardResponseMonthBalance;
import com.example.domain.responses.card.stats.balance.CardResponseYearBalance;

import io.smallrye.mutiny.Uni;

public interface CardBalanceService {
    Uni<ApiResponse<List<CardResponseMonthBalance>>> findMonthBalance(Long year);

    Uni<ApiResponse<List<CardResponseYearBalance>>> findYearBalance(Long year);
}
