package com.example.service.transfer.stats.status;

import java.util.List;

import com.example.domain.requests.transfers.stats.MonthStatusTransfer;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transfer.stats.status.TransferResponseMonthStatusFailed;
import com.example.domain.responses.transfer.stats.status.TransferResponseMonthStatusSuccess;
import com.example.domain.responses.transfer.stats.status.TransferResponseYearStatusFailed;
import com.example.domain.responses.transfer.stats.status.TransferResponseYearStatusSuccess;

import io.smallrye.mutiny.Uni;

public interface TransferStatusService {
    Uni<ApiResponse<List<TransferResponseMonthStatusSuccess>>> findMonthStatusSuccess(MonthStatusTransfer req);

    Uni<ApiResponse<List<TransferResponseMonthStatusFailed>>> findMonthStatusFailed(MonthStatusTransfer req);

    Uni<ApiResponse<List<TransferResponseYearStatusSuccess>>> findYearlyStatusSuccess(Long year);

    Uni<ApiResponse<List<TransferResponseYearStatusFailed>>> findYearlyStatusFailed(Long year);
}
