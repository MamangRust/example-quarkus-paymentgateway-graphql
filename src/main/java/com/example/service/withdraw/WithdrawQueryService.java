package com.example.service.withdraw;

import java.util.List;

import com.example.domain.requests.withdraws.FindAllWithdrawCardNumber;
import com.example.domain.requests.withdraws.FindAllWithdraws;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.withdraw.WithdrawResponse;
import com.example.domain.responses.withdraw.WithdrawResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface WithdrawQueryService {
    Uni<ApiResponsePagination<List<WithdrawResponse>>> findAll(FindAllWithdraws req);

    Uni<ApiResponsePagination<List<WithdrawResponse>>> findAllByCardNumber(FindAllWithdrawCardNumber req);

    Uni<ApiResponse<WithdrawResponse>> findById(Long withdrawId);

    Uni<ApiResponse<List<WithdrawResponse>>> findByCard(String cardNumber);

    Uni<ApiResponsePagination<List<WithdrawResponseDeleteAt>>> findByActive(FindAllWithdraws req);

    Uni<ApiResponsePagination<List<WithdrawResponseDeleteAt>>> findByTrashed(FindAllWithdraws req);
}
