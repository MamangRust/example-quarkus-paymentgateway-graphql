package com.example.service.impl.withdraw;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.config.RedisService;
import com.example.domain.requests.withdraws.CreateWithdrawRequest;
import com.example.domain.requests.withdraws.UpdateWithdrawRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.withdraw.WithdrawResponse;
import com.example.domain.responses.withdraw.WithdrawResponseDeleteAt;
import com.example.entity.withdraw.Withdraw;
import com.example.enums.Status;
import com.example.exception.ResourceNotFoundException;
import com.example.repository.card.CardQueryRepository;
import com.example.repository.saldo.SaldoCommandRepository;
import com.example.repository.saldo.SaldoQueryRepository;
import com.example.repository.withdraw.WithdrawCommandRepository;
import com.example.repository.withdraw.WithdrawQueryRepository;
import com.example.service.withdraw.WithdrawCommandService;

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
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;

@ApplicationScoped
public class WithdrawCommandServiceImpl implements WithdrawCommandService {
    private static final Logger logger = LoggerFactory.getLogger(WithdrawCommandServiceImpl.class);

    private final WithdrawQueryRepository withdrawQueryRepository;
    private final WithdrawCommandRepository withdrawCommandRepository;
    private final CardQueryRepository cardQueryRepository;
    private final SaldoQueryRepository saldoQueryRepository;
    private final SaldoCommandRepository saldoCommandRepository;
    private final Validator validator;
    private final RedisService redisService;

    private final Tracer tracer;
    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDurationSeconds;

    @Inject
    public WithdrawCommandServiceImpl(WithdrawQueryRepository withdrawQueryRepository,
            WithdrawCommandRepository withdrawCommandRepository,
            CardQueryRepository cardQueryRepository,
            SaldoQueryRepository saldoQueryRepository,
            SaldoCommandRepository saldoCommandRepository,
            Validator validator,
            RedisService redisService,
            OpenTelemetry openTelemetry) {
        this.withdrawQueryRepository = withdrawQueryRepository;
        this.withdrawCommandRepository = withdrawCommandRepository;
        this.cardQueryRepository = cardQueryRepository;
        this.saldoQueryRepository = saldoQueryRepository;
        this.saldoCommandRepository = saldoCommandRepository;
        this.validator = validator;
        this.redisService = redisService;
        this.tracer = openTelemetry.getTracer("withdraw-command-service", "1.0.0");
        Meter meter = openTelemetry.getMeter("withdraw-command-service");

        this.requestsTotal = meter.counterBuilder("requests_total")
                .setDescription("Total number of requests")
                .build();
        this.requestDurationSeconds = meter.histogramBuilder("request_duration_seconds")
                .setDescription("Request duration in seconds")
                .setUnit("s")
                .build();
    }

    private <T> void validateRequest(T req) {
        Set<ConstraintViolation<T>> violations = validator.validate(req);
        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (ConstraintViolation<T> violation : violations) {
                sb.append(violation.getPropertyPath()).append(": ").append(violation.getMessage()).append("; ");
            }
            logger.error("Validation failed: {}", sb);
            throw new ConstraintViolationException("Validation failed: " + sb, violations);
        }
    }

    private Uni<Void> evictCaches(String cardNumber, Long withdrawId) {
        String key1 = "saldo:card:" + cardNumber;
        String key2 = "withdraws:id:" + withdrawId;
        String key3 = "withdraws:list:card:" + cardNumber;

        return Uni.combine().all().unis(
                redisService.deleteReactive(key1),
                redisService.deleteReactive(key2),
                redisService.deleteReactive(key3)).discardItems();
    }

    @Override
    @WithTransaction
    public Uni<ApiResponse<WithdrawResponse>> create(CreateWithdrawRequest req) {
        long startTime = System.currentTimeMillis();
        Span span = tracer.spanBuilder("createWithdraw")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("service.name", "withdraw-command-service")
                .setAttribute("operation", "create")
                .setAttribute("cardNumber", req.getCardNumber())
                .startSpan();

        logger.info("🚀 Starting create withdraw request: {}", req);

        try {
            validateRequest(req);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.end();
            return Uni.createFrom().item(new ApiResponse<>("error", "Validation failed: " + e.getMessage(), null));
        }

        if (req.getWithdrawAmount() == null || req.getWithdrawAmount() <= 0) {
            span.setStatus(StatusCode.ERROR, "withdraw_amount must be > 0");
            span.end();
            return Uni.createFrom().item(new ApiResponse<>("error", "withdraw_amount must be > 0", null));
        }

        return cardQueryRepository.findCardByCardNumber(req.getCardNumber())
                .chain(card -> {
                    if (card == null) {
                        logger.error("❌ Card not found with number={}", req.getCardNumber());
                        throw new ResourceNotFoundException("Card not found");
                    }
                    return saldoQueryRepository.findByCardNumber(req.getCardNumber());
                })
                .chain(saldo -> {
                    if (saldo == null) {
                        logger.error("❌ Saldo not found for card number={}", req.getCardNumber());
                        throw new ResourceNotFoundException("Saldo not found");
                    }

                    if (saldo.getTotalBalance() < req.getWithdrawAmount()) {
                        logger.error("❌ Insufficient balance for card number={}. Balance={}, Requested={}",
                                req.getCardNumber(), saldo.getTotalBalance(), req.getWithdrawAmount());
                        throw new IllegalStateException("Insufficient balance");
                    }

                    int newBalance = saldo.getTotalBalance() - req.getWithdrawAmount().intValue();

                    Withdraw withdraw = new Withdraw();
                    withdraw.setCardNumber(req.getCardNumber());
                    withdraw.setWithdrawNo(UUID.randomUUID());
                    withdraw.setStatus(Status.PENDING);
                    withdraw.setWithdrawTime(
                            req.getWithdrawTime() != null ? java.sql.Timestamp.valueOf(req.getWithdrawTime())
                                    : java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                    withdraw.setWithdrawAmount(req.getWithdrawAmount().intValue());
                    withdraw.setCreatedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                    withdraw.setUpdatedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));

                    return saldoCommandRepository.updateBalanceByCardNumber(saldo.getCardNumber(), (long) newBalance)
                            .chain(v -> saldoCommandRepository.updateWithdrawByCardNumber(saldo.getCardNumber(),
                                    req.getWithdrawAmount()))
                            .chain(v -> withdrawCommandRepository.persist(withdraw))
                            .chain(savedWithdraw -> withdrawCommandRepository
                                    .updateStatus(savedWithdraw.getWithdrawId(), Status.SUCCESS.toString()))
                            .chain(updatedWithdraw -> {
                                logger.info("✅ Withdraw created successfully with ID={}",
                                        updatedWithdraw.getWithdrawId());
                                return evictCaches(req.getCardNumber(), updatedWithdraw.getWithdrawId())
                                        .map(v -> {
                                            span.setStatus(StatusCode.OK);
                                            requestsTotal.add(1, Attributes.of(
                                                    AttributeKey.stringKey("operation"), "create",
                                                    AttributeKey.stringKey("status"), "success"));

                                            return ApiResponse.success("Created withdraw successfully",
                                                    WithdrawResponse.from(updatedWithdraw));
                                        });
                            });
                })
                .onFailure().recoverWithItem(e -> {
                    logger.error("💥 Error creating withdraw", e);
                    span.recordException(e);
                    span.setStatus(StatusCode.ERROR, e.getMessage());

                    requestsTotal.add(1, Attributes.of(
                            AttributeKey.stringKey("operation"), "create",
                            AttributeKey.stringKey("status"), "failed",
                            AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                    return new ApiResponse<>("error", "Failed to create withdraw: " + e.getMessage(), null);
                })
                .eventually(() -> {
                    span.end();
                    double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                    requestDurationSeconds.record(duration, Attributes.of(
                            AttributeKey.stringKey("operation"), "create"));
                });
    }

    @Override
    @WithTransaction
    public Uni<ApiResponse<WithdrawResponse>> update(UpdateWithdrawRequest req) {
        long startTime = System.currentTimeMillis();
        Span span = tracer.spanBuilder("updateWithdraw")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("service.name", "withdraw-command-service")
                .setAttribute("operation", "update")
                .setAttribute("withdrawId", String.valueOf(req.getWithdrawId()))
                .startSpan();

        logger.info("🚀 Starting update withdraw request: {}", req);

        try {
            validateRequest(req);
        } catch (Exception e) {
            span.setStatus(StatusCode.ERROR, e.getMessage());
            span.end();
            return Uni.createFrom().item(new ApiResponse<>("error", "Validation failed: " + e.getMessage(), null));
        }

        if (req.getWithdrawId() == null) {
            span.setStatus(StatusCode.ERROR, "withdraw_id is required");
            span.end();
            return Uni.createFrom().item(new ApiResponse<>("error", "withdraw_id is required", null));
        }

        if (req.getWithdrawAmount() == null || req.getWithdrawAmount() <= 0) {
            span.setStatus(StatusCode.ERROR, "withdraw_amount must be > 0");
            span.end();
            return Uni.createFrom().item(new ApiResponse<>("error", "withdraw_amount must be > 0", null));
        }

        return cardQueryRepository.findCardByCardNumber(req.getCardNumber())
                .chain(card -> {
                    if (card == null) {
                        logger.error("❌ Card not found with number={}", req.getCardNumber());
                        throw new ResourceNotFoundException("Card not found");
                    }
                    return withdrawQueryRepository.findById(req.getWithdrawId());
                })
                .chain(withdraw -> {
                    if (withdraw == null) {
                        logger.error("❌ Withdraw not found with ID={}", req.getWithdrawId());
                        throw new ResourceNotFoundException("Withdraw not found");
                    }
                    return saldoQueryRepository.findByCardNumber(req.getCardNumber())
                            .chain(saldo -> {
                                if (saldo == null) {
                                    logger.error("❌ Saldo not found for card number={}", req.getCardNumber());
                                    throw new ResourceNotFoundException("Saldo not found");
                                }

                                long amountDifference = req.getWithdrawAmount() - withdraw.getWithdrawAmount();
                                if (saldo.getTotalBalance() < amountDifference) {
                                    logger.error("❌ Insufficient balance for update. Balance={}, Needed={}",
                                            saldo.getTotalBalance(), amountDifference);
                                    throw new IllegalStateException("Insufficient balance");
                                }

                                int newBalance = saldo.getTotalBalance() - (int) amountDifference;

                                withdraw.setCardNumber(req.getCardNumber());
                                withdraw.setWithdrawAmount(req.getWithdrawAmount().intValue());
                                withdraw.setWithdrawTime(req.getWithdrawTime() != null
                                        ? java.sql.Timestamp.valueOf(req.getWithdrawTime())
                                        : java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                                withdraw.setUpdatedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));

                                return saldoCommandRepository
                                        .updateBalanceByCardNumber(saldo.getCardNumber(), (long) newBalance)
                                        .chain(v -> saldoCommandRepository.updateWithdrawByCardNumber(
                                                saldo.getCardNumber(), req.getWithdrawAmount()))
                                        .chain(v -> withdrawCommandRepository.persist(withdraw))
                                        .chain(savedWithdraw -> withdrawCommandRepository
                                                .updateStatus(savedWithdraw.getWithdrawId(), Status.SUCCESS.toString()))
                                        .chain(updatedWithdraw -> {
                                            logger.info("✅ Withdraw updated successfully with ID={}",
                                                    updatedWithdraw.getWithdrawId());
                                            return evictCaches(req.getCardNumber(), updatedWithdraw.getWithdrawId())
                                                    .map(v -> {
                                                        span.setStatus(StatusCode.OK);
                                                        requestsTotal.add(1, Attributes.of(
                                                                AttributeKey.stringKey("operation"), "update",
                                                                AttributeKey.stringKey("status"), "success"));

                                                        return ApiResponse.success("Updated withdraw successfully",
                                                                WithdrawResponse.from(updatedWithdraw));
                                                    });
                                        });
                            });
                })
                .onFailure().recoverWithItem(e -> {
                    logger.error("💥 Error updating withdraw", e);
                    span.recordException(e);
                    span.setStatus(StatusCode.ERROR, e.getMessage());

                    requestsTotal.add(1, Attributes.of(
                            AttributeKey.stringKey("operation"), "update",
                            AttributeKey.stringKey("status"), "failed",
                            AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                    return new ApiResponse<>("error", "Failed to update withdraw: " + e.getMessage(), null);
                })
                .eventually(() -> {
                    span.end();
                    double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                    requestDurationSeconds.record(duration, Attributes.of(
                            AttributeKey.stringKey("operation"), "update"));
                });
    }

    @Override
    @WithTransaction
    public Uni<ApiResponse<WithdrawResponseDeleteAt>> trashed(Long withdrawId) {
        logger.info("🗑️ Trashing withdraw id={}", withdrawId);

        return withdrawCommandRepository.trashed(withdrawId)
                .chain(withdraw -> {
                    if (withdraw == null) {
                        throw new ResourceNotFoundException("Withdraw not found with ID " + withdrawId);
                    }
                    return evictCaches(withdraw.getCardNumber(), withdraw.getWithdrawId())
                            .map(v -> ApiResponse.success("Withdraw trashed successfully!",
                                    WithdrawResponseDeleteAt.from(withdraw)));
                })
                .onFailure().recoverWithItem(e -> {
                    logger.error("💥 Failed to trash withdraw id={}", withdrawId, e);
                    return new ApiResponse<>("error", "Failed to trash withdraw: " + e.getMessage(), null);
                });
    }

    @Override
    @WithTransaction
    public Uni<ApiResponse<WithdrawResponseDeleteAt>> restore(Long withdrawId) {
        logger.info("♻️ Restoring withdraw id={}", withdrawId);

        return withdrawCommandRepository.restore(withdrawId)
                .chain(withdraw -> {
                    if (withdraw == null) {
                        throw new ResourceNotFoundException("Withdraw not found with ID " + withdrawId);
                    }
                    return evictCaches(withdraw.getCardNumber(), withdraw.getWithdrawId())
                            .map(v -> ApiResponse.success("Withdraw restored successfully!",
                                    WithdrawResponseDeleteAt.from(withdraw)));
                })
                .onFailure().recoverWithItem(e -> {
                    logger.error("💥 Failed to restore withdraw id={}", withdrawId, e);
                    return new ApiResponse<>("error", "Failed to restore withdraw: " + e.getMessage(), null);
                });
    }

    @Override
    @WithTransaction
    public Uni<ApiResponse<Boolean>> deletePermanent(Long withdrawId) {
        logger.info("🧨 Permanently deleting withdraw id={}", withdrawId);

        return withdrawQueryRepository.findById(withdrawId)
                .chain(withdraw -> {
                    if (withdraw == null) {
                        return Uni.createFrom().item(ApiResponse.success("Withdraw permanently deleted!", true));
                    }
                    return withdrawCommandRepository.deletePermanent(withdrawId)
                            .chain(success -> evictCaches(withdraw.getCardNumber(), withdraw.getWithdrawId())
                                    .map(v -> ApiResponse.success("Withdraw permanently deleted!", success)));
                })
                .onFailure().recoverWithItem(e -> {
                    logger.error("💥 Failed to permanently delete withdraw id={}", withdrawId, e);
                    return new ApiResponse<>("error", "Failed to permanently delete withdraw: " + e.getMessage(),
                            false);
                });
    }

    @Override
    @WithTransaction
    public Uni<ApiResponse<Boolean>> restoreAll() {
        logger.info("🔄 Restoring ALL trashed withdraws");

        return withdrawCommandRepository.restoreAllDeleted()
                .map(success -> ApiResponse.success("All withdraws restored successfully!", success))
                .onFailure().recoverWithItem(e -> {
                    logger.error("💥 Failed to restore all withdraws", e);
                    return new ApiResponse<>("error", "Failed to restore all withdraws: " + e.getMessage(), false);
                });
    }

    @Override
    @WithTransaction
    public Uni<ApiResponse<Boolean>> deleteAll() {
        logger.info("💣 Permanently deleting ALL trashed withdraws");

        return withdrawCommandRepository.deleteAllDeleted()
                .map(success -> ApiResponse.success("All withdraws permanently deleted!", success))
                .onFailure().recoverWithItem(e -> {
                    logger.error("💥 Failed to delete all withdraws", e);
                    return new ApiResponse<>("error", "Failed to delete all withdraws: " + e.getMessage(), false);
                });
    }
}
