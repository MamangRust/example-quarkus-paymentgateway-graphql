package com.example.domain.responses.topup.stats.status;

import com.example.entity.topup.TopupMonthStatusFailed;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopupResponseMonthStatusFailed {
    private String reportYear;
    private String monthName;
    private Long totalAmount;
    private Long totalFailed;

    public static TopupResponseMonthStatusFailed from(TopupMonthStatusFailed dto) {
        return TopupResponseMonthStatusFailed.builder()
                .reportYear(dto.getYear())
                .monthName(dto.getMonth())
                .totalAmount(dto.getTotalAmount().longValue())
                .totalFailed(dto.getTotalFailed().longValue())
                .build();
    }
}