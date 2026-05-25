package com.example.domain.responses.transfer.stats.amount;

import com.example.entity.transfer.TransferYearAmount;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferYearAmountResponse {
    private String reportYear;
    private Long totalAmount;

    public static TransferYearAmountResponse from(TransferYearAmount dto) {
        return TransferYearAmountResponse.builder()
                .reportYear(dto.getYear())
                .totalAmount(dto.getTotalAmount())
                .build();
    }
}
