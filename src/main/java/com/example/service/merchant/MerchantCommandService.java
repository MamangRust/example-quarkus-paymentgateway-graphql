package com.example.service.merchant;

import com.example.domain.requests.merchant.CreateMerchantRequest;
import com.example.domain.requests.merchant.UpdateMerchantRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.merchant.MerchantResponse;
import com.example.domain.responses.merchant.MerchantResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface MerchantCommandService {
    Uni<ApiResponse<MerchantResponse>> createMerchant(CreateMerchantRequest req);

    Uni<ApiResponse<MerchantResponse>> updateMerchant(UpdateMerchantRequest req);

    Uni<ApiResponse<MerchantResponseDeleteAt>> trashMerchant(Long id);

    Uni<ApiResponse<MerchantResponseDeleteAt>> restoreMerchant(Long id);

    Uni<ApiResponse<Boolean>> deleteMerchant(Long id);

    Uni<ApiResponse<Boolean>> restoreAll();

    Uni<ApiResponse<Boolean>> deleteAll();
}
