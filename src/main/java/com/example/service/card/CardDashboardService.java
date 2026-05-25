package com.example.service.card;

import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.card.dashboard.CardDashboard;
import com.example.domain.responses.card.dashboard.CardDashboardCard;

import io.smallrye.mutiny.Uni;

public interface CardDashboardService {
    Uni<ApiResponse<CardDashboard>> dashboard();

    Uni<ApiResponse<CardDashboardCard>> dashboardByCard(String cardNumber);
}
