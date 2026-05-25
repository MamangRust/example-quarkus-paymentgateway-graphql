package com.example.service.card.statsbycard;

import java.util.List;

import com.example.domain.requests.card.MonthYearCardNumberCard;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.card.stats.amount.CardResponseMonthAmount;
import com.example.domain.responses.card.stats.amount.CardResponseYearAmount;

import io.smallrye.mutiny.Uni;

public interface CardTransferAmountByCardService {
    Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthAmountSender(MonthYearCardNumberCard req);

    Uni<ApiResponse<List<CardResponseYearAmount>>> findYearAmountSender(MonthYearCardNumberCard req);

    Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthAmountReceiver(MonthYearCardNumberCard req);

    Uni<ApiResponse<List<CardResponseYearAmount>>> findYearAmountReceiver(MonthYearCardNumberCard req);
}
