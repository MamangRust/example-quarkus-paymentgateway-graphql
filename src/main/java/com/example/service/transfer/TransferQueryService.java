package com.example.service.transfer;

import java.util.List;

import com.example.domain.requests.transfers.FindAllTransfers;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.transfer.TransferResponse;
import com.example.domain.responses.transfer.TransferResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface TransferQueryService {
    Uni<ApiResponsePagination<List<TransferResponse>>> findAll(FindAllTransfers req);

    Uni<ApiResponse<TransferResponse>> findById(Long transferId);

    Uni<ApiResponsePagination<List<TransferResponseDeleteAt>>> findByActive(FindAllTransfers req);

    Uni<ApiResponsePagination<List<TransferResponseDeleteAt>>> findByTrashed(FindAllTransfers req);

    Uni<ApiResponse<List<TransferResponse>>> findByTransferFrom(String transferFrom);

    Uni<ApiResponse<List<TransferResponse>>> findByTransferTo(String transferTo);
}
