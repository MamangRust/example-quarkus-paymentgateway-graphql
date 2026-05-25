package com.example.service.card;

import com.example.domain.requests.card.CreateCardRequest;
import com.example.domain.requests.card.UpdateCardRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.card.CardResponse;
import com.example.domain.responses.card.CardResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface CardCommandService {
    Uni<ApiResponse<CardResponse>> createCard(CreateCardRequest req);

    Uni<ApiResponse<CardResponse>> updateCard(UpdateCardRequest req);

    Uni<ApiResponse<CardResponseDeleteAt>> trashCard(Long id);

    Uni<ApiResponse<CardResponseDeleteAt>> restoreCard(Long id);

    Uni<ApiResponse<Boolean>> deleteCard(Long id);

    Uni<ApiResponse<Boolean>> restoreAll();

    Uni<ApiResponse<Boolean>> deleteAll();
}
