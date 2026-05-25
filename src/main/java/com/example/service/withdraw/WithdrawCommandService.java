package com.example.service.withdraw;

import com.example.domain.requests.withdraws.CreateWithdrawRequest;
import com.example.domain.requests.withdraws.UpdateWithdrawRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.withdraw.WithdrawResponse;
import com.example.domain.responses.withdraw.WithdrawResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface WithdrawCommandService {
    Uni<ApiResponse<WithdrawResponse>> create(CreateWithdrawRequest req);

    Uni<ApiResponse<WithdrawResponse>> update(UpdateWithdrawRequest req);

    Uni<ApiResponse<WithdrawResponseDeleteAt>> trashed(Long withdrawId);

    Uni<ApiResponse<WithdrawResponseDeleteAt>> restore(Long withdrawId);

    Uni<ApiResponse<Boolean>> deletePermanent(Long withdrawId);

    Uni<ApiResponse<Boolean>> restoreAll();

    Uni<ApiResponse<Boolean>> deleteAll();
}
