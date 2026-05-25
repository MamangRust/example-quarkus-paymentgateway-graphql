package com.example.service.merchant;

import java.util.List;

import com.example.domain.requests.merchant.FindAllMerchants;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.merchant.MerchantResponse;
import com.example.domain.responses.merchant.MerchantResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface MerchantQueryService {
    Uni<ApiResponsePagination<List<MerchantResponse>>> findAll(FindAllMerchants req);

    Uni<ApiResponsePagination<List<MerchantResponseDeleteAt>>> findByActive(FindAllMerchants req);

    Uni<ApiResponsePagination<List<MerchantResponseDeleteAt>>> findByTrashed(FindAllMerchants req);

    Uni<ApiResponse<MerchantResponse>> findById(Long merchantId);

    Uni<ApiResponse<MerchantResponse>> findByApiKey(String apiKey);

    Uni<ApiResponse<List<MerchantResponse>>> findByUserId(Long userId);
}
