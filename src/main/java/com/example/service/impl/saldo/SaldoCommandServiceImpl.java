package com.example.service.impl.saldo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.config.RedisService;
import com.example.domain.requests.saldo.CreateSaldoRequest;
import com.example.domain.requests.saldo.UpdateSaldoRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.saldo.SaldoResponse;
import com.example.domain.responses.saldo.SaldoResponseDeleteAt;
import com.example.entity.saldo.Saldo;
import com.example.exception.ResourceNotFoundException;
import com.example.repository.card.CardQueryRepository;
import com.example.repository.saldo.SaldoCommandRepository;
import com.example.repository.saldo.SaldoQueryRepository;
import com.example.service.saldo.SaldoCommandService;

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
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class SaldoCommandServiceImpl implements SaldoCommandService {
        private static final Logger logger = LoggerFactory.getLogger(SaldoCommandServiceImpl.class);

        private final CardQueryRepository cardQueryRepository;
        private final SaldoCommandRepository saldoCommandRepository;
        private final SaldoQueryRepository saldoQueryRepository;
        private final RedisService redisService;

        private final Tracer tracer;
        private final LongCounter requestsTotal;
        private final DoubleHistogram requestDurationSeconds;

        @Inject
        public SaldoCommandServiceImpl(CardQueryRepository cardQueryRepository,
                        SaldoCommandRepository saldoCommandRepository,
                        SaldoQueryRepository saldoQueryRepository,
                        OpenTelemetry openTelemetry,
                        RedisService redisService) {
                this.cardQueryRepository = cardQueryRepository;
                this.saldoCommandRepository = saldoCommandRepository;
                this.saldoQueryRepository = saldoQueryRepository;
                this.redisService = redisService;
                this.tracer = openTelemetry.getTracer("saldo-command-service", "1.0.0");
                Meter meter = openTelemetry.getMeter("saldo-command-service");

                this.requestsTotal = meter.counterBuilder("requests_total")
                                .setDescription("Total number of requests")
                                .build();
                this.requestDurationSeconds = meter.histogramBuilder("request_duration_seconds")
                                .setDescription("Request duration in seconds")
                                .setUnit("s")
                                .build();
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<SaldoResponse>> create(CreateSaldoRequest request) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("createSaldo")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "saldo-command-service")
                                .setAttribute("operation", "create_saldo")
                                .setAttribute("cardNumber", request.getCardNumber())
                                .startSpan();

                logger.info("Creating saldo for card_number={}", request.getCardNumber());

                return cardQueryRepository.findCardByCardNumber(request.getCardNumber())
                                .chain(card -> {
                                        if (card == null) {
                                                logger.error("Card {} not found", request.getCardNumber());
                                                throw new ResourceNotFoundException("Card not found");
                                        }

                                        Saldo saldo = new Saldo();
                                        saldo.setCardNumber(request.getCardNumber());
                                        saldo.setTotalBalance(request.getTotalBalance().intValue());
                                        saldo.setWithdrawAmount(0);
                                        saldo.setWithdrawTime(null);
                                        saldo.setCreatedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                                        saldo.setUpdatedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));

                                        return saldoCommandRepository.persist(saldo)
                                                        .chain(savedSaldo -> {
                                                                logger.info("Saldo created successfully with id={} for card={}",
                                                                                savedSaldo.getSaldoId(),
                                                                                request.getCardNumber());
                                                                String cacheKey = "saldo:card:"
                                                                                + request.getCardNumber();

                                                                return redisService.deleteReactive(cacheKey)
                                                                                .map(v -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "create_saldo",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Create saldo success",
                                                                                                        SaldoResponse.from(
                                                                                                                        savedSaldo));
                                                                                });
                                                        });
                                })
                                .onFailure().recoverWithItem(e -> {
                                        logger.error("💥 Failed to create saldo for card_number={}",
                                                        request.getCardNumber(), e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "create_saldo",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));

                                        return new ApiResponse<>("error", e.getMessage(), null);
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "create_saldo"));
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<SaldoResponse>> update(UpdateSaldoRequest request) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("updateSaldo")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "saldo-command-service")
                                .setAttribute("operation", "update_saldo")
                                .setAttribute("saldo.id", String.valueOf(request.getSaldoId()))
                                .setAttribute("cardNumber", request.getCardNumber())
                                .startSpan();

                logger.info("Updating saldo id={} for card={}", request.getSaldoId(), request.getCardNumber());

                if (request.getSaldoId() == null) {
                        logger.error("saldo_id is required");
                        span.setStatus(StatusCode.ERROR, "saldo_id is required");
                        span.end();
                        return Uni.createFrom().item(new ApiResponse<>("error", "saldo_id is required", null));
                }

                return cardQueryRepository.findCardByCardNumber(request.getCardNumber())
                                .chain(card -> {
                                        if (card == null) {
                                                logger.error("Card {} not found during update",
                                                                request.getCardNumber());
                                                throw new ResourceNotFoundException("Card not found");
                                        }
                                        return saldoQueryRepository.findById(request.getSaldoId());
                                })
                                .chain(saldo -> {
                                        if (saldo == null) {
                                                logger.error("Saldo not found with id {}", request.getSaldoId());
                                                throw new ResourceNotFoundException("Saldo not found");
                                        }

                                        saldo.setCardNumber(request.getCardNumber());
                                        saldo.setTotalBalance(request.getTotalBalance().intValue());
                                        saldo.setUpdatedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));

                                        return saldoCommandRepository.persist(saldo)
                                                        .chain(updatedSaldo -> {
                                                                logger.info("Saldo updated successfully with id={} for card={}",
                                                                                updatedSaldo.getSaldoId(),
                                                                                request.getCardNumber());
                                                                String cacheId = "saldo:id:" + request.getSaldoId();
                                                                String cacheCard = "saldo:card:"
                                                                                + request.getCardNumber();

                                                                return Uni.combine().all().unis(
                                                                                redisService.deleteReactive(cacheId),
                                                                                redisService.deleteReactive(cacheCard))
                                                                                .asTuple().map(t -> {
                                                                                        span.setStatus(StatusCode.OK);
                                                                                        requestsTotal.add(1,
                                                                                                        Attributes.of(
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "operation"),
                                                                                                                        "update_saldo",
                                                                                                                        AttributeKey.stringKey(
                                                                                                                                        "status"),
                                                                                                                        "success"));

                                                                                        return ApiResponse.success(
                                                                                                        "Update saldo success",
                                                                                                        SaldoResponse.from(
                                                                                                                        updatedSaldo));
                                                                                });
                                                        });
                                })
                                .onFailure().recoverWithItem(e -> {
                                        logger.error("💥 Failed to update saldo id={} for card={}",
                                                        request.getSaldoId(), request.getCardNumber(), e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "update_saldo",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));

                                        return new ApiResponse<>("error", e.getMessage(), null);
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "update_saldo"));
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<SaldoResponseDeleteAt>> trash(Long id) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("trashSaldo")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "saldo-command-service")
                                .setAttribute("operation", "trash_saldo")
                                .setAttribute("saldo.id", String.valueOf(id))
                                .startSpan();

                logger.info("🗑️ Trashing saldo id={}", id);

                return saldoCommandRepository.trashed(id)
                                .chain(saldo -> {
                                        if (saldo == null) {
                                                logger.error("Saldo not found with id {}", id);
                                                throw new ResourceNotFoundException("Saldo not found");
                                        }

                                        String cacheId = "saldo:id:" + id;
                                        String cacheCard = "saldo:card:" + saldo.getCardNumber();

                                        return Uni.combine().all().unis(
                                                        redisService.deleteReactive(cacheId),
                                                        redisService.deleteReactive(cacheCard)).asTuple().map(t -> {
                                                                span.setStatus(StatusCode.OK);
                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "trash_saldo",
                                                                                AttributeKey.stringKey("status"),
                                                                                "success"));

                                                                return ApiResponse.success("Trash saldo success",
                                                                                SaldoResponseDeleteAt.from(saldo));
                                                        });
                                })
                                .onFailure().recoverWithItem(e -> {
                                        logger.error("💥 Failed to trash saldo id={}", id, e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "trash_saldo",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));

                                        return new ApiResponse<>("error", e.getMessage(), null);
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "trash_saldo"));
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<SaldoResponseDeleteAt>> restore(Long id) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("restoreSaldo")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "saldo-command-service")
                                .setAttribute("operation", "restore_saldo")
                                .setAttribute("saldo.id", String.valueOf(id))
                                .startSpan();

                logger.info("♻️ Restoring saldo id={}", id);

                return saldoCommandRepository.restore(id)
                                .chain(saldo -> {
                                        if (saldo == null) {
                                                logger.error("Saldo not found with id {}", id);
                                                throw new ResourceNotFoundException("Saldo not found");
                                        }

                                        String cacheId = "saldo:id:" + id;
                                        String cacheCard = "saldo:card:" + saldo.getCardNumber();

                                        return Uni.combine().all().unis(
                                                        redisService.deleteReactive(cacheId),
                                                        redisService.deleteReactive(cacheCard)).asTuple().map(t -> {
                                                                span.setStatus(StatusCode.OK);
                                                                requestsTotal.add(1, Attributes.of(
                                                                                AttributeKey.stringKey("operation"),
                                                                                "restore_saldo",
                                                                                AttributeKey.stringKey("status"),
                                                                                "success"));

                                                                return ApiResponse.success("Restore saldo success",
                                                                                SaldoResponseDeleteAt.from(saldo));
                                                        });
                                })
                                .onFailure().recoverWithItem(e -> {
                                        logger.error("💥 Failed to restore saldo id={}", id, e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "restore_saldo",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));

                                        return new ApiResponse<>("error", e.getMessage(), null);
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "restore_saldo"));
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<Boolean>> delete(Long id) {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("deleteSaldo")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "saldo-command-service")
                                .setAttribute("operation", "delete_saldo")
                                .setAttribute("saldo.id", String.valueOf(id))
                                .startSpan();

                logger.info("🧨 Permanently deleting saldo id={}", id);

                return saldoQueryRepository.findById(id)
                                .chain(saldo -> {
                                        if (saldo == null) {
                                                logger.error("Saldo not found with id {}", id);
                                                throw new ResourceNotFoundException("Saldo not found");
                                        }

                                        String cacheId = "saldo:id:" + id;
                                        String cacheCard = "saldo:card:" + saldo.getCardNumber();

                                        return saldoCommandRepository.deletePermanent(id)
                                                        .chain(deleted -> Uni.combine().all().unis(
                                                                        redisService.deleteReactive(cacheId),
                                                                        redisService.deleteReactive(cacheCard))
                                                                        .asTuple().map(t -> {
                                                                                span.setStatus(StatusCode.OK);
                                                                                requestsTotal.add(1, Attributes.of(
                                                                                                AttributeKey.stringKey(
                                                                                                                "operation"),
                                                                                                "delete_saldo",
                                                                                                AttributeKey.stringKey(
                                                                                                                "status"),
                                                                                                "success"));

                                                                                return ApiResponse.success(
                                                                                                "Delete saldo success",
                                                                                                deleted);
                                                                        }));
                                })
                                .onFailure().recoverWithItem(e -> {
                                        logger.error("💥 Failed to permanently delete saldo id={}", id, e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "delete_saldo",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));

                                        return new ApiResponse<>("error", e.getMessage(), false);
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "delete_saldo"));
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<Boolean>> restoreAll() {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("restoreAllSaldos")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "saldo-command-service")
                                .setAttribute("operation", "restore_all_saldos")
                                .startSpan();

                logger.info("🔄 Restoring ALL trashed saldos");

                return saldoCommandRepository.restoreAllDeleted()
                                .map(restored -> {
                                        span.setStatus(StatusCode.OK);
                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "restore_all_saldos",
                                                        AttributeKey.stringKey("status"), "success"));

                                        return ApiResponse.success("Restore all saldo success", restored);
                                })
                                .onFailure().recoverWithItem(e -> {
                                        logger.error("💥 Failed to restore all saldos", e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "restore_all_saldos",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));

                                        return new ApiResponse<>("error", e.getMessage(), false);
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "restore_all_saldos"));
                                });
        }

        @Override
        @WithTransaction
        public Uni<ApiResponse<Boolean>> deleteAll() {
                long startTime = System.currentTimeMillis();
                Span span = tracer.spanBuilder("deleteAllSaldos")
                                .setSpanKind(SpanKind.SERVER)
                                .setAttribute("service.name", "saldo-command-service")
                                .setAttribute("operation", "delete_all_saldos")
                                .startSpan();

                logger.info("💣 Permanently deleting ALL trashed saldos");

                return saldoCommandRepository.deleteAllDeleted()
                                .map(deleted -> {
                                        span.setStatus(StatusCode.OK);
                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "delete_all_saldos",
                                                        AttributeKey.stringKey("status"), "success"));

                                        return ApiResponse.success("Delete all saldo success", deleted);
                                })
                                .onFailure().recoverWithItem(e -> {
                                        logger.error("💥 Failed to delete all saldos", e);
                                        span.recordException(e);
                                        span.setStatus(StatusCode.ERROR, e.getMessage());

                                        requestsTotal.add(1, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "delete_all_saldos",
                                                        AttributeKey.stringKey("status"), "failed",
                                                        AttributeKey.stringKey("error_type"),
                                                        e.getClass().getSimpleName()));

                                        return new ApiResponse<>("error", e.getMessage(), false);
                                })
                                .eventually(() -> {
                                        span.end();
                                        double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                                        requestDurationSeconds.record(duration, Attributes.of(
                                                        AttributeKey.stringKey("operation"), "delete_all_saldos"));
                                });
        }
}
