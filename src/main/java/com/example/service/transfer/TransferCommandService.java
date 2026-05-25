package com.example.service.transfer;

import com.example.domain.requests.transfers.CreateTransferRequest;
import com.example.domain.requests.transfers.UpdateTransferRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transfer.TransferResponse;
import com.example.domain.responses.transfer.TransferResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface TransferCommandService {
    Uni<ApiResponse<TransferResponse>> create(CreateTransferRequest req);

    Uni<ApiResponse<TransferResponse>> update(UpdateTransferRequest req);

    Uni<ApiResponse<TransferResponseDeleteAt>> trashed(Long transferId);

    Uni<ApiResponse<TransferResponseDeleteAt>> restore(Long transferId);

    Uni<ApiResponse<Boolean>> deletePermanent(Long transferId);

    Uni<ApiResponse<Boolean>> restoreAll();

    Uni<ApiResponse<Boolean>> deleteAll();
}
