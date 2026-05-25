package com.example.service.topup;

import com.example.domain.requests.topup.CreateTopupRequest;
import com.example.domain.requests.topup.UpdateTopupRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.topup.TopupResponse;
import com.example.domain.responses.topup.TopupResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface TopupCommandService {
    Uni<ApiResponse<TopupResponse>> create(CreateTopupRequest req);

    Uni<ApiResponse<TopupResponse>> update(UpdateTopupRequest req);

    Uni<ApiResponse<TopupResponseDeleteAt>> trashed(Long topupId);

    Uni<ApiResponse<TopupResponseDeleteAt>> restore(Long topupId);

    Uni<ApiResponse<Boolean>> deletePermanent(Long topupId);

    Uni<ApiResponse<Boolean>> restoreAll();

    Uni<ApiResponse<Boolean>> deleteAll();
}
