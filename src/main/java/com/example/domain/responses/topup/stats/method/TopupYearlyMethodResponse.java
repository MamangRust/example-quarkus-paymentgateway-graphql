package com.example.domain.responses.topup.stats.method;

import com.example.entity.topup.TopupYearMethod;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopupYearlyMethodResponse {
    private String reportYear;
    private String topupMethod;
    private Long totalTopups;
    private Long totalAmount;

    public static TopupYearlyMethodResponse from(TopupYearMethod dto) {
        return TopupYearlyMethodResponse.builder()
                .reportYear(dto.getYear())
                .topupMethod(dto.getTopupMethod())
                .totalTopups(dto.getTotalTopups().longValue())
                .totalAmount(dto.getTotalAmount().longValue())
                .build();
    }
}