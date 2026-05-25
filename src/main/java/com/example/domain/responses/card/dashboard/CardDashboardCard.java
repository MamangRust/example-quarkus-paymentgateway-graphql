package com.example.domain.responses.card.dashboard;

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
public class CardDashboardCard {
    private Long totalBalance;
    private Long totalTopup;
    private Long totalWithdraw;
    private Long totalTransaction;
    private Long totalTransferSend;
    private Long totalTransferReceiver;
}
