package com.example.service.impl.card;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.card.dashboard.CardDashboard;
import com.example.domain.responses.card.dashboard.CardDashboardCard;
import com.example.repository.card.CardDashboardByCardRepository;
import com.example.repository.card.CardDashboardRepository;
import com.example.service.card.CardDashboardService;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class CardDashboardServiceImpl implements CardDashboardService {
        private static final Logger logger = LoggerFactory.getLogger(CardDashboardServiceImpl.class);

        private final CardDashboardRepository cardDashboardRepository;
        private final CardDashboardByCardRepository cardDashboardByCardRepository;
        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        @Inject
        public CardDashboardServiceImpl(CardDashboardRepository cardDashboardRepository,
                        CardDashboardByCardRepository cardDashboardByCardRepository,
                        OpenTelemetry openTelemetry) {
                this.cardDashboardRepository = cardDashboardRepository;
                this.cardDashboardByCardRepository = cardDashboardByCardRepository;
                this.tracer = openTelemetry.getTracer("card-dashboard-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("card-dashboard-service");

                this.requestsTotal = meter.counterBuilder("requests_total")
                                .setDescription("Total number of requests")
                                .build();
                this.requestDurationSeconds = meter.histogramBuilder("request_duration_seconds")
                                .setDescription("Request duration in seconds")
                                .setUnit("s")
                                .build();
        }

        @Override
        public Uni<ApiResponse<CardDashboard>> dashboard() {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("getGlobalDashboard")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "card-dashboard-service")
                                .setAttribute("operation", "get_global_dashboard")
                                .startSpan();

                logger.info("📊 Fetching global dashboard statistics");

                return Uni.combine().all().unis(
                                cardDashboardRepository.getTotalBalance(),
                                cardDashboardRepository.getTotalTopupAmount(),
                                cardDashboardRepository.getTotalTransactionAmount(),
                                cardDashboardRepository.getTotalTransferAmount(),
                                cardDashboardRepository.getTotalWithdrawAmount()).asTuple().map(tuple -> {
                                        CardDashboard dashboard = CardDashboard.builder()
                                                        .totalBalance(tuple.getItem1())
                                                        .totalTopup(tuple.getItem2())
                                                        .totalTransaction(tuple.getItem3())
                                                        .totalTransfer(tuple.getItem4())
                                                        .totalWithdraw(tuple.getItem5())
                                                        .build();

                                        logger.info("✅ Global dashboard retrieved successfully");
                                        span.setStatus(StatusCode.OK);

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "get_global_dashboard",
                                                        AttributeKey.stringKey("status"), "success"));

                                        return ApiResponse.success("Global dashboard retrieved successfully",
                                                        dashboard);
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("❌ Failed to fetch dashboard data", e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "get_global_dashboard",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "get_global_dashboard"));
                                        logger.debug("Get global dashboard operation completed in {} seconds",
                                                        duration);
                                });
        }

        @Override
        public Uni<ApiResponse<CardDashboardCard>> dashboardByCard(String cardNumber) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("getDashboardByCard")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "card-dashboard-service")
                                .setAttribute("operation", "get_dashboard_by_card")
                                .setAttribute("card.number", cardNumber)
                                .startSpan();

                logger.info("💳📊 Fetching dashboard for card: {}", cardNumber);

                return Uni.combine().all().unis(
                                cardDashboardByCardRepository.getTotalBalanceByCard(cardNumber),
                                cardDashboardByCardRepository.getTotalTopupAmountByCard(cardNumber),
                                cardDashboardByCardRepository.getTotalTransactionAmountByCard(cardNumber),
                                cardDashboardByCardRepository.getTotalTransferAmountBySender(cardNumber),
                                cardDashboardByCardRepository.getTotalTransferAmountByReceiver(cardNumber),
                                cardDashboardByCardRepository.getTotalWithdrawAmountByCard(cardNumber)).asTuple()
                                .map(tuple -> {
                                        CardDashboardCard dashboardCard = CardDashboardCard.builder()
                                                        .totalBalance(tuple.getItem1())
                                                        .totalTopup(tuple.getItem2())
                                                        .totalTransaction(tuple.getItem3())
                                                        .totalTransferSend(tuple.getItem4())
                                                        .totalTransferReceiver(tuple.getItem5())
                                                        .totalWithdraw(tuple.getItem6())
                                                        .build();

                                        logger.info("✅ Dashboard for card {} retrieved successfully", cardNumber);
                                        span.setStatus(StatusCode.OK);

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "get_dashboard_by_card",
                                                        AttributeKey.stringKey("status"), "success"));

                                        return ApiResponse.success(
                                                        "Dashboard for card " + cardNumber + " retrieved successfully",
                                                        dashboardCard);
                                })
                                .onFailure().invoke(e -> {
                                        logger.error("❌ Failed to fetch dashboard for card {}", cardNumber, e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "get_dashboard_by_card",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "get_dashboard_by_card"));
                                        logger.debug("Get dashboard by card operation completed in {} seconds",
                                                        duration);
                                });
        }
}
