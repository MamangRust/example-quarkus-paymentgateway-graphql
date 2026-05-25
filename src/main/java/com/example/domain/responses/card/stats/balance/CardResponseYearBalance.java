package com.example.domain.responses.card.stats.balance;

import com.example.entity.card.CardYearBalance;
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
public class CardResponseYearBalance {
    private String reportYear;
    private Long totalBalance;

    public static CardResponseYearBalance from(CardYearBalance model) {
        if (model == null) {
            return null;
        }
        return CardResponseYearBalance.builder()
                .reportYear(model.getYear())
                .totalBalance(model.getTotalBalance())
                .build();
    }
}
