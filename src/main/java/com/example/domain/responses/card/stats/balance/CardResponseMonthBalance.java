package com.example.domain.responses.card.stats.balance;

import com.example.entity.card.CardMonthBalance;
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
public class CardResponseMonthBalance {
    private String month;
    private Long totalBalance;

    public static CardResponseMonthBalance from(CardMonthBalance model) {
        if (model == null) {
            return null;
        }
        return CardResponseMonthBalance.builder()
                .month(model.getMonth())
                .totalBalance(model.getTotalBalance())
                .build();
    }
}
