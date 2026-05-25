package com.example.service.transaction;

import com.example.domain.requests.transaction.CreateTransactionRequest;
import com.example.domain.requests.transaction.UpdateTransactionRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transaction.TransactionResponse;
import com.example.domain.responses.transaction.TransactionResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface TransactionCommandService {
    Uni<ApiResponse<TransactionResponse>> create(String apiKey, CreateTransactionRequest req);

    Uni<ApiResponse<TransactionResponse>> update(String apiKey, UpdateTransactionRequest req);

    Uni<ApiResponse<TransactionResponseDeleteAt>> trashed(Long transactionId);

    Uni<ApiResponse<TransactionResponseDeleteAt>> restore(Long transactionId);

    Uni<ApiResponse<Boolean>> deletePermanent(Long transactionId);

    Uni<ApiResponse<Boolean>> restoreAll();

    Uni<ApiResponse<Boolean>> deleteAll();
}
