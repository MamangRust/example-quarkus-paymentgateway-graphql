package com.example.service.impl.transfer;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.config.RedisService;
import com.example.domain.requests.transfers.CreateTransferRequest;
import com.example.domain.requests.transfers.UpdateTransferRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.transfer.TransferResponse;
import com.example.domain.responses.transfer.TransferResponseDeleteAt;
import com.example.entity.transfer.Transfer;
import com.example.enums.Status;
import com.example.exception.ResourceNotFoundException;
import com.example.repository.card.CardQueryRepository;
import com.example.repository.saldo.SaldoCommandRepository;
import com.example.repository.saldo.SaldoQueryRepository;
import com.example.repository.transfer.TransferCommandRepository;
import com.example.repository.transfer.TransferQueryRepository;
import com.example.service.transfer.TransferCommandService;

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
import jakarta.validation.Validator;

@ApplicationScoped
public class TransferCommandServiceImpl implements TransferCommandService {
    private static final Logger logger = LoggerFactory.getLogger(TransferCommandServiceImpl.class);

    private final TransferCommandRepository transferCommandRepository;
    private final CardQueryRepository cardQueryRepository;
    private final SaldoQueryRepository saldoQueryRepository;
    private final SaldoCommandRepository saldoCommandRepository;
    private final TransferQueryRepository transferQueryRepository;
    private final Validator validator;
    private final RedisService redisService;

    private final Tracer tracer;
    private final LongCounter requestsTotal;
    private final DoubleHistogram requestDurationSeconds;

    @Inject
    public TransferCommandServiceImpl(TransferCommandRepository transferCommandRepository,
            CardQueryRepository cardQueryRepository,
            SaldoQueryRepository saldoQueryRepository,
            SaldoCommandRepository saldoCommandRepository,
            TransferQueryRepository transferQueryRepository,
            Validator validator,
            RedisService redisService,
            OpenTelemetry openTelemetry) {
        this.transferCommandRepository = transferCommandRepository;
        this.cardQueryRepository = cardQueryRepository;
        this.saldoQueryRepository = saldoQueryRepository;
        this.saldoCommandRepository = saldoCommandRepository;
        this.transferQueryRepository = transferQueryRepository;
        this.validator = validator;
        this.redisService = redisService;
        this.tracer = openTelemetry.getTracer("transfer-command-service", "1.0.0");
        Meter meter = openTelemetry.getMeter("transfer-command-service");

        this.requestsTotal = meter.counterBuilder("requests_total")
                .setDescription("Total number of requests")
                .build();
        this.requestDurationSeconds = meter.histogramBuilder("request_duration_seconds")
                .setDescription("Request duration in seconds")
                .setUnit("s")
                .build();
    }

    private <T> boolean validateRequest(T req) {
        Set<ConstraintViolation<T>> violations = validator.validate(req);
        if (!violations.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (ConstraintViolation<T> violation : violations) {
                sb.append(violation.getPropertyPath())
                        .append(": ")
                        .append(violation.getMessage())
                        .append("; ");
            }
            logger.error("Validation failed: {}", sb);
            return false;
        }
        return true;
    }

    private Uni<Void> evictCaches(String senderCard, String receiverCard, Long transferId) {
        String key1 = "saldo:card:" + senderCard;
        String key2 = "saldo:card:" + receiverCard;
        String key3 = "transfers:from:" + senderCard;
        String key4 = "transfers:to:" + receiverCard;
        String key5 = "transfers:id:" + transferId;

        return Uni.combine().all().unis(
                redisService.deleteReactive(key1),
                redisService.deleteReactive(key2),
                redisService.deleteReactive(key3),
                redisService.deleteReactive(key4),
                redisService.deleteReactive(key5)).discardItems();
    }

    @Override
    @WithTransaction
    public Uni<ApiResponse<TransferResponse>> create(CreateTransferRequest req) {
        long startTime = System.currentTimeMillis();
        Span span = tracer.spanBuilder("createTransfer")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("service.name", "transfer-command-service")
                .setAttribute("operation", "create_transfer")
                .setAttribute("transferFrom", req.getTransferFrom())
                .setAttribute("transferTo", req.getTransferTo())
                .startSpan();

        logger.info("🚀 Starting create transfer: {}", req);

        if (!validateRequest(req)) {
            span.setStatus(StatusCode.ERROR, "Validation failed");
            span.end();
            return Uni.createFrom().item(new ApiResponse<>("error", "Validation failed", null));
        }

        return cardQueryRepository.findCardByCardNumber(req.getTransferFrom())
                .chain(senderCard -> {
                    if (senderCard == null) {
                        logger.error("❌ sender card {} not found", req.getTransferFrom());
                        throw new ResourceNotFoundException("Sender card not found");
                    }
                    return cardQueryRepository.findCardByCardNumber(req.getTransferTo());
                })
                .chain(receiverCard -> {
                    if (receiverCard == null) {
                        logger.error("❌ receiver card {} not found", req.getTransferTo());
                        throw new ResourceNotFoundException("Receiver card not found");
                    }
                    return saldoQueryRepository.findByCardNumber(req.getTransferFrom());
                })
                .chain(senderSaldo -> {
                    if (senderSaldo == null) {
                        logger.error("❌ failed to fetch sender saldo");
                        throw new ResourceNotFoundException("Sender saldo not found");
                    }
                    return saldoQueryRepository.findByCardNumber(req.getTransferTo())
                            .map(receiverSaldo -> {
                                if (receiverSaldo == null) {
                                    logger.error("❌ failed to fetch receiver saldo");
                                    throw new ResourceNotFoundException("Receiver saldo not found");
                                }

                                if (senderSaldo.getTotalBalance() < req.getTransferAmount()) {
                                    logger.error("❌ insufficient balance, requested={}, available={}",
                                            req.getTransferAmount(), senderSaldo.getTotalBalance());
                                    throw new IllegalStateException("Insufficient balance");
                                }

                                return new SaldoPair(senderSaldo, receiverSaldo);
                            });
                })
                .chain(pair -> {
                    Transfer transferEntity = new Transfer();
                    transferEntity.setTransferNo(UUID.randomUUID());
                    transferEntity.setTransferFrom(req.getTransferFrom());
                    transferEntity.setTransferAmount(req.getTransferAmount().intValue());
                    transferEntity.setTransferTo(req.getTransferTo());
                    transferEntity.setTransferTime(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                    transferEntity.setStatus(Status.PENDING);
                    transferEntity.setCreatedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));
                    transferEntity.setUpdatedAt(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now()));

                    return transferCommandRepository.persist(transferEntity)
                            .chain(savedTransfer -> {
                                int newSenderBalance = pair.sender.getTotalBalance()
                                        - req.getTransferAmount().intValue();
                                int newReceiverBalance = pair.receiver.getTotalBalance()
                                        + req.getTransferAmount().intValue();

                                return saldoCommandRepository
                                        .updateBalanceByCardNumber(pair.sender.getCardNumber(), (long) newSenderBalance)
                                        .chain(v -> saldoCommandRepository.updateBalanceByCardNumber(
                                                pair.receiver.getCardNumber(), (long) newReceiverBalance))
                                        .chain(v -> transferCommandRepository
                                                .updateTransferStatus(savedTransfer.getTransferId(), "SUCCESS"))
                                        .chain(updatedTransfer -> {
                                            logger.info("✅ Transfer created successfully with ID={}",
                                                    updatedTransfer.getTransferId());
                                            return evictCaches(req.getTransferFrom(), req.getTransferTo(),
                                                    updatedTransfer.getTransferId())
                                                    .map(x -> {
                                                        span.setStatus(StatusCode.OK);
                                                        requestsTotal.add(1, Attributes.of(
                                                                AttributeKey.stringKey("operation"), "create_transfer",
                                                                AttributeKey.stringKey("status"), "success"));

                                                        return ApiResponse.success("Transfer created successfully",
                                                                TransferResponse.from(updatedTransfer));
                                                    });
                                        });
                            });
                })
                .onFailure().recoverWithItem(e -> {
                    logger.error("❌ Failed to create transfer", e);
                    span.recordException(e);
                    span.setStatus(StatusCode.ERROR, e.getMessage());

                    requestsTotal.add(1, Attributes.of(
                            AttributeKey.stringKey("operation"), "create_transfer",
                            AttributeKey.stringKey("status"), "failed",
                            AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                    return new ApiResponse<>("error", "Failed to create transfer: " + e.getMessage(), null);
                })
                .eventually(() -> {
                    span.end();
                    double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                    requestDurationSeconds.record(duration, Attributes.of(
                            AttributeKey.stringKey("operation"), "create_transfer"));
                });
    }

    private static class SaldoPair {
        final com.example.entity.saldo.Saldo sender;
        final com.example.entity.saldo.Saldo receiver;

        SaldoPair(com.example.entity.saldo.Saldo sender, com.example.entity.saldo.Saldo receiver) {
            this.sender = sender;
            this.receiver = receiver;
        }
    }

    @Override
    @WithTransaction
    public Uni<ApiResponse<TransferResponse>> update(UpdateTransferRequest req) {
        long startTime = System.currentTimeMillis();
        Span span = tracer.spanBuilder("updateTransfer")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute("service.name", "transfer-command-service")
                .setAttribute("operation", "update_transfer")
                .setAttribute("transferId", String.valueOf(req.getTransferId()))
                .startSpan();

        logger.info("🔄 Starting update transfer process: {}", req);

        if (!validateRequest(req)) {
            span.setStatus(StatusCode.ERROR, "Validation failed");
            span.end();
            return Uni.createFrom().item(new ApiResponse<>("error", "Validation failed", null));
        }

        if (req.getTransferId() == null || req.getTransferId() <= 0) {
            span.setStatus(StatusCode.ERROR, "transferId is required");
            span.end();
            return Uni.createFrom().item(new ApiResponse<>("error", "transferId is required", null));
        }

        return transferQueryRepository.findTransferById(req.getTransferId())
                .chain(transfer -> {
                    if (transfer == null) {
                        logger.error("❌ Transfer {} not found", req.getTransferId());
                        throw new ResourceNotFoundException("Transfer " + req.getTransferId() + " not found");
                    }

                    long amountDifference = req.getTransferAmount() - transfer.getTransferAmount();

                    return saldoQueryRepository.findByCardNumber(transfer.getTransferFrom())
                            .chain(senderSaldo -> {
                                if (senderSaldo == null) {
                                    return transferCommandRepository.updateTransferStatus(req.getTransferId(), "FAILED")
                                            .chain(v -> {
                                                throw new ResourceNotFoundException(
                                                        "Sender card " + transfer.getTransferFrom() + " not found");
                                            });
                                }

                                long newSenderBalance = senderSaldo.getTotalBalance() - amountDifference;
                                if (newSenderBalance < 0) {
                                    logger.error("❌ Insufficient balance for sender {}", transfer.getTransferFrom());
                                    return transferCommandRepository.updateTransferStatus(req.getTransferId(), "FAILED")
                                            .chain(v -> {
                                                throw new IllegalStateException("Insufficient balance");
                                            });
                                }

                                return saldoQueryRepository.findByCardNumber(transfer.getTransferTo())
                                        .chain(receiverSaldo -> {
                                            if (receiverSaldo == null) {
                                                return transferCommandRepository
                                                        .updateTransferStatus(req.getTransferId(), "FAILED")
                                                        .chain(v -> {
                                                            throw new ResourceNotFoundException("Receiver card "
                                                                    + transfer.getTransferTo() + " not found");
                                                        });
                                            }

                                            long newReceiverBalance = receiverSaldo.getTotalBalance()
                                                    + amountDifference;

                                            return saldoCommandRepository
                                                    .updateBalanceByCardNumber(senderSaldo.getCardNumber(),
                                                            newSenderBalance)
                                                    .chain(v -> saldoCommandRepository.updateBalanceByCardNumber(
                                                            receiverSaldo.getCardNumber(), newReceiverBalance))
                                                    .chain(v -> {
                                                        transfer.setTransferAmount(req.getTransferAmount().intValue());
                                                        transfer.setTransferFrom(req.getTransferFrom());
                                                        transfer.setTransferTo(req.getTransferTo());
                                                        transfer.setUpdatedAt(java.sql.Timestamp
                                                                .valueOf(java.time.LocalDateTime.now()));
                                                        return transferCommandRepository.persist(transfer);
                                                    })
                                                    .chain(updatedTransfer -> transferCommandRepository
                                                            .updateTransferStatus(req.getTransferId(), "SUCCESS"))
                                                    .chain(finalTransfer -> {
                                                        logger.info("✅ Successfully updated transfer {}",
                                                                req.getTransferId());
                                                        return evictCaches(transfer.getTransferFrom(),
                                                                transfer.getTransferTo(), finalTransfer.getTransferId())
                                                                .map(x -> {
                                                                    span.setStatus(StatusCode.OK);
                                                                    requestsTotal.add(1, Attributes.of(
                                                                            AttributeKey.stringKey("operation"),
                                                                            "update_transfer",
                                                                            AttributeKey.stringKey("status"),
                                                                            "success"));

                                                                    return ApiResponse.success(
                                                                            "Transfer updated successfully",
                                                                            TransferResponse.from(finalTransfer));
                                                                });
                                                    });
                                        });
                            });
                })
                .onFailure().recoverWithItem(e -> {
                    logger.error("❌ Failed to update transfer {}", req.getTransferId(), e);
                    span.recordException(e);
                    span.setStatus(StatusCode.ERROR, e.getMessage());

                    requestsTotal.add(1, Attributes.of(
                            AttributeKey.stringKey("operation"), "update_transfer",
                            AttributeKey.stringKey("status"), "failed",
                            AttributeKey.stringKey("error_type"), e.getClass().getSimpleName()));

                    return new ApiResponse<>("error", "Failed to update transfer: " + e.getMessage(), null);
                })
                .eventually(() -> {
                    span.end();
                    double duration = (System.currentTimeMillis() - startTime) / 1000.0;
                    requestDurationSeconds.record(duration, Attributes.of(
                            AttributeKey.stringKey("operation"), "update_transfer"));
                });
    }

    @Override
    @WithTransaction
    public Uni<ApiResponse<TransferResponseDeleteAt>> trashed(Long transferId) {
        logger.info("🗑️ Trashing transfer id={}", transferId);

        return transferCommandRepository.trashed(transferId)
                .chain(transfer -> {
                    if (transfer == null) {
                        throw new ResourceNotFoundException("Transfer not found with ID " + transferId);
                    }
                    return evictCaches(transfer.getTransferFrom(), transfer.getTransferTo(), transfer.getTransferId())
                            .map(x -> ApiResponse.success("🗑️ Transfer trashed successfully!",
                                    TransferResponseDeleteAt.from(transfer)));
                })
                .onFailure().recoverWithItem(e -> {
                    logger.error("💥 Failed to trash transfer id={}", transferId, e);
                    return new ApiResponse<>("error", "Failed to trash transfer: " + e.getMessage(), null);
                });
    }

    @Override
    @WithTransaction
    public Uni<ApiResponse<TransferResponseDeleteAt>> restore(Long transferId) {
        logger.info("♻️ Restoring transfer id={}", transferId);

        return transferCommandRepository.restore(transferId)
                .chain(transfer -> {
                    if (transfer == null) {
                        throw new ResourceNotFoundException("Transfer not found with ID " + transferId);
                    }
                    return evictCaches(transfer.getTransferFrom(), transfer.getTransferTo(), transfer.getTransferId())
                            .map(x -> ApiResponse.success("♻️ Transfer restored successfully!",
                                    TransferResponseDeleteAt.from(transfer)));
                })
                .onFailure().recoverWithItem(e -> {
                    logger.error("💥 Failed to restore transfer id={}", transferId, e);
                    return new ApiResponse<>("error", "Failed to restore transfer: " + e.getMessage(), null);
                });
    }

    @Override
    @WithTransaction
    public Uni<ApiResponse<Boolean>> deletePermanent(Long transferId) {
        logger.info("🧨 Permanently deleting transfer id={}", transferId);

        return transferQueryRepository.findTransferById(transferId)
                .chain(transfer -> {
                    if (transfer == null) {
                        return Uni.createFrom().item(ApiResponse.success("🧨 Transfer permanently deleted!", true));
                    }
                    return transferCommandRepository.deletePermanent(transferId)
                            .chain(success -> evictCaches(transfer.getTransferFrom(), transfer.getTransferTo(),
                                    transfer.getTransferId())
                                    .map(x -> ApiResponse.success("🧨 Transfer permanently deleted!", success)));
                })
                .onFailure().recoverWithItem(e -> {
                    logger.error("💥 Failed to permanently delete transfer id={}", transferId, e);
                    return new ApiResponse<>("error", "💥 Failed to permanently delete transfer: " + e.getMessage(),
                            false);
                });
    }

    @Override
    @WithTransaction
    public Uni<ApiResponse<Boolean>> restoreAll() {
        logger.info("🔄 Restoring ALL trashed transfers");

        return transferCommandRepository.restoreAllDeleted()
                .map(success -> ApiResponse.success("🔄 All transfers restored successfully!", success))
                .onFailure().recoverWithItem(e -> {
                    logger.error("💥 Failed to restore all transfers", e);
                    return new ApiResponse<>("error", "💥 Failed to restore all transfers: " + e.getMessage(), false);
                });
    }

    @Override
    @WithTransaction
    public Uni<ApiResponse<Boolean>> deleteAll() {
        logger.info("💣 Permanently deleting ALL trashed transfers");

        return transferCommandRepository.deleteAllDeleted()
                .map(success -> ApiResponse.success("💣 All transfers permanently deleted!", success))
                .onFailure().recoverWithItem(e -> {
                    logger.error("💥 Failed to delete all transfers", e);
                    return new ApiResponse<>("error", "💥 Failed to delete all transfers: " + e.getMessage(), false);
                });
    }
}
