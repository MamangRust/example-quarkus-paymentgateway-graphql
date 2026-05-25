package com.example.service.transfer.stats.status;

import java.util.List;

import com.example.domain.requests.transfers.statsbycard.MonthStatusTransferCardNumber;
import com.example.domain.requests.transfers.statsbycard.YearStatusTransferCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transfer.stats.status.TransferResponseMonthStatusFailed;
import com.example.domain.responses.transfer.stats.status.TransferResponseMonthStatusSuccess;
import com.example.domain.responses.transfer.stats.status.TransferResponseYearStatusFailed;
import com.example.domain.responses.transfer.stats.status.TransferResponseYearStatusSuccess;

import io.smallrye.mutiny.Uni;

public interface TransferStatusByCardService {
        Uni<ApiResponse<List<TransferResponseMonthStatusSuccess>>> findMonthStatusSuccessByCard(
                        MonthStatusTransferCardNumber req);

        Uni<ApiResponse<List<TransferResponseYearStatusSuccess>>> findYearlyStatusSuccessByCard(
                        YearStatusTransferCardNumber req);

        Uni<ApiResponse<List<TransferResponseMonthStatusFailed>>> findMonthStatusFailedByCard(
                        MonthStatusTransferCardNumber req);

        Uni<ApiResponse<List<TransferResponseYearStatusFailed>>> findYearlyStatusFailedByCard(
                        YearStatusTransferCardNumber req);
}
