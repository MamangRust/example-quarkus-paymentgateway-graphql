package com.example.domain.responses.topup.stats.method;

import com.example.entity.topup.TopupMonthMethod;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TopupMonthMethodResponse {
    private String monthName;
    private String topupMethod;
    private Long totalTopups;
    private Long totalAmount;

    public static TopupMonthMethodResponse from(TopupMonthMethod dto) {
        return TopupMonthMethodResponse.builder()
                .monthName(dto.getMonth())
                .topupMethod(dto.getTopupMethod())
                .totalTopups(dto.getTotalTopups().longValue())
                .totalAmount(dto.getTotalAmount().longValue())
                .build();
    }
}