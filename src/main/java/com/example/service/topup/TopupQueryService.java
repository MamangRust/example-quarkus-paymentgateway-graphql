package com.example.service.topup;

import java.util.List;

import com.example.domain.requests.topup.FindAllTopups;
import com.example.domain.requests.topup.FindAllTopupsByCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.topup.TopupResponse;
import com.example.domain.responses.topup.TopupResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface TopupQueryService {
    Uni<ApiResponsePagination<List<TopupResponse>>> findAll(FindAllTopups req);

    Uni<ApiResponsePagination<List<TopupResponse>>> findAllByCardNumber(FindAllTopupsByCardNumber req);

    Uni<ApiResponsePagination<List<TopupResponseDeleteAt>>> findActive(FindAllTopups req);

    Uni<ApiResponsePagination<List<TopupResponseDeleteAt>>> findTrashed(FindAllTopups req);

    Uni<ApiResponse<List<TopupResponse>>> findByCard(String cardNumber);

    Uni<ApiResponse<TopupResponse>> findById(Long topupId);
}
