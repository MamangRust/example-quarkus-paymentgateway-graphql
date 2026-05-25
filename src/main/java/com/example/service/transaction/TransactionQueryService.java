package com.example.service.transaction;

import java.util.List;

import com.example.domain.requests.transaction.FindAllTransactionCardNumber;
import com.example.domain.requests.transaction.FindAllTransactions;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.transaction.TransactionResponse;
import com.example.domain.responses.transaction.TransactionResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface TransactionQueryService {
    Uni<ApiResponsePagination<List<TransactionResponse>>> findAll(FindAllTransactions req);

    Uni<ApiResponsePagination<List<TransactionResponse>>> findAllByCardNumber(FindAllTransactionCardNumber req);

    Uni<ApiResponsePagination<List<TransactionResponseDeleteAt>>> findByActive(FindAllTransactions req);

    Uni<ApiResponsePagination<List<TransactionResponseDeleteAt>>> findByTrashed(FindAllTransactions req);

    Uni<ApiResponse<TransactionResponse>> findById(Long transactionId);

    Uni<ApiResponse<List<TransactionResponse>>> findByMerchantId(Long merchantId);
}
