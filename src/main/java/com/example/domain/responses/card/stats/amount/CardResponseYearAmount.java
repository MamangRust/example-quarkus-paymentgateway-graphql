package com.example.domain.responses.card.stats.amount;

import com.example.entity.card.CardYearAmount;
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
public class CardResponseYearAmount {
    private String reportYear;
    private Long totalAmount;

    public static CardResponseYearAmount from(CardYearAmount model) {
        if (model == null) {
            return null;
        }
        return CardResponseYearAmount.builder()
                .reportYear(model.getYear())
                .totalAmount(model.getTotalAmount())
                .build();
    }
}
