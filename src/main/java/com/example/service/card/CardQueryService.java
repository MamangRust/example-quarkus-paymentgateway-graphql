package com.example.service.card;

import java.util.List;

import com.example.domain.requests.card.FindAllCards;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.card.CardResponse;
import com.example.domain.responses.card.CardResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface CardQueryService {
    Uni<ApiResponsePagination<List<CardResponse>>> findAll(FindAllCards req);

    Uni<ApiResponsePagination<List<CardResponseDeleteAt>>> findByActive(FindAllCards req);

    Uni<ApiResponsePagination<List<CardResponseDeleteAt>>> findByTrashed(FindAllCards req);

    Uni<ApiResponse<CardResponse>> findById(Long cardId);

    Uni<ApiResponse<CardResponse>> findByUserId(Long userId);

    Uni<ApiResponse<CardResponse>> findByCardNumber(String cardNumber);
}
