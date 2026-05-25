package com.example.service.saldo;

import com.example.domain.requests.saldo.CreateSaldoRequest;
import com.example.domain.requests.saldo.UpdateSaldoRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.saldo.SaldoResponse;
import com.example.domain.responses.saldo.SaldoResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface SaldoCommandService {
    Uni<ApiResponse<SaldoResponse>> create(CreateSaldoRequest request);

    Uni<ApiResponse<SaldoResponse>> update(UpdateSaldoRequest request);

    Uni<ApiResponse<SaldoResponseDeleteAt>> trash(Long id);

    Uni<ApiResponse<SaldoResponseDeleteAt>> restore(Long id);

    Uni<ApiResponse<Boolean>> delete(Long id);

    Uni<ApiResponse<Boolean>> restoreAll();

    Uni<ApiResponse<Boolean>> deleteAll();
}
