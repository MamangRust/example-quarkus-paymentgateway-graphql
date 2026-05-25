package com.example.service.saldo;

import java.util.List;

import com.example.domain.requests.saldo.FindAllSaldos;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.saldo.SaldoResponse;
import com.example.domain.responses.saldo.SaldoResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface SaldoQueryService {
    Uni<ApiResponsePagination<List<SaldoResponse>>> findAll(FindAllSaldos req);

    Uni<ApiResponsePagination<List<SaldoResponseDeleteAt>>> findActive(FindAllSaldos req);

    Uni<ApiResponsePagination<List<SaldoResponseDeleteAt>>> findTrashed(FindAllSaldos req);

    Uni<ApiResponse<SaldoResponse>> findByCard(String cardNumber);

    Uni<ApiResponse<SaldoResponse>> findById(Long id);
}
