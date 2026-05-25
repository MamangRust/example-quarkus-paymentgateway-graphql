package com.example.domain.responses.topup.stats.status;

import com.example.entity.topup.TopupYearStatusSuccess;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopupResponseYearStatusSuccess {
    private String reportYear;
    private Long totalAmount;
    private Long totalSuccess;

    public static TopupResponseYearStatusSuccess from(TopupYearStatusSuccess dto) {
        return TopupResponseYearStatusSuccess.builder()
                .reportYear(dto.getYear())
                .totalAmount(dto.getTotalAmount().longValue())
                .totalSuccess(dto.getTotalSuccess().longValue())
                .build();
    }
}
