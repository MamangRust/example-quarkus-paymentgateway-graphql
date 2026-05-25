package com.example.service.card.stats;

import java.util.List;

import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.card.stats.amount.CardResponseMonthAmount;
import com.example.domain.responses.card.stats.amount.CardResponseYearAmount;

import io.smallrye.mutiny.Uni;

public interface CardTransferAmountService {
    Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthAmountSender(Long year);

    Uni<ApiResponse<List<CardResponseYearAmount>>> findYearAmountSender(Long year);

    Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthAmountReceiver(Long year);

    Uni<ApiResponse<List<CardResponseYearAmount>>> findYearAmountReceiver(Long year);
}
