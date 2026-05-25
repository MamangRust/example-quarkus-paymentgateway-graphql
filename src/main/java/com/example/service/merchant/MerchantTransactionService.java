package com.example.service.merchant;

import java.util.List;

import com.example.domain.requests.merchant.FindAllMerchants;
import com.example.domain.requests.merchant.transactions.FindAllMerchantTransactionsByApiKey;
import com.example.domain.requests.merchant.transactions.FindAllMerchantTransactionsById;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.merchant.MerchantTransactionResponse;

import io.smallrye.mutiny.Uni;

public interface MerchantTransactionService {
    Uni<ApiResponsePagination<List<MerchantTransactionResponse>>> findAll(FindAllMerchants req);

    Uni<ApiResponsePagination<List<MerchantTransactionResponse>>> findById(FindAllMerchantTransactionsById req);

    Uni<ApiResponsePagination<List<MerchantTransactionResponse>>> findByApiKey(FindAllMerchantTransactionsByApiKey req);
}
