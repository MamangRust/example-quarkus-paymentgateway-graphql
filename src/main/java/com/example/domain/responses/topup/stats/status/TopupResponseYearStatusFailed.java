package com.example.domain.responses.topup.stats.status;

import com.example.entity.topup.TopupYearStatusFailed;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopupResponseYearStatusFailed {
    private String reportYear;
    private Long totalAmount;
    private Long totalFailed;

    public static TopupResponseYearStatusFailed from(TopupYearStatusFailed dto) {
        return TopupResponseYearStatusFailed.builder()
                .reportYear(dto.getYear())
                .totalAmount(dto.getTotalAmount().longValue())
                .totalFailed(dto.getTotalFailed().longValue())
                .build();
    }
}
