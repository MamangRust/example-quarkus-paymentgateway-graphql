package com.example.domain.responses.card.stats.amount;

import com.example.entity.card.CardMonthAmount;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@RegisterForReflection
public class CardResponseMonthAmount {
    private String monthName;
    private Long totalAmount;

    public static CardResponseMonthAmount from(CardMonthAmount model) {
        if (model == null) {
            return null;
        }
        return CardResponseMonthAmount.builder()
                .monthName(model.getMonth())
                .totalAmount(model.getTotalAmount())
                .build();
    }
}
