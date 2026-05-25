package com.example.graphql;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

import com.example.domain.requests.transaction.CreateTransactionRequest;
import com.example.domain.requests.transaction.FindAllTransactionCardNumber;
import com.example.domain.requests.transaction.FindAllTransactions;
import com.example.domain.requests.transaction.UpdateTransactionRequest;
import com.example.domain.requests.transaction.stats.MonthStatusTransaction;
import com.example.domain.requests.transaction.stats.MonthYearPaymentMethod;
import com.example.domain.requests.transaction.statsbycard.MonthStatusTransactionCardNumber;
import com.example.domain.requests.transaction.statsbycard.YearStatusTransactionCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.transaction.TransactionResponse;
import com.example.domain.responses.transaction.TransactionResponseDeleteAt;
import com.example.domain.responses.transaction.stats.amount.TransactionMonthAmountResponse;
import com.example.domain.responses.transaction.stats.amount.TransactionYearlyAmountResponse;
import com.example.domain.responses.transaction.stats.method.TransactionMonthMethodResponse;
import com.example.domain.responses.transaction.stats.method.TransactionYearMethodResponse;
import com.example.domain.responses.transaction.stats.status.TransactionResponseMonthStatusFailed;
import com.example.domain.responses.transaction.stats.status.TransactionResponseMonthStatusSuccess;
import com.example.domain.responses.transaction.stats.status.TransactionResponseYearStatusFailed;
import com.example.domain.responses.transaction.stats.status.TransactionResponseYearStatusSuccess;
import com.example.service.transaction.TransactionCommandService;
import com.example.service.transaction.TransactionQueryService;
import com.example.service.transaction.stats.amount.TransactionAmountByCardService;
import com.example.service.transaction.stats.amount.TransactionAmountService;
import com.example.service.transaction.stats.method.TransactionMethodByCardService;
import com.example.service.transaction.stats.method.TransactionMethodService;
import com.example.service.transaction.stats.status.TransactionStatusByCardService;
import com.example.service.transaction.stats.status.TransactionStatusService;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.HeaderParam;

@GraphQLApi
public class TransactionGraphQL {

    @Inject
    TransactionQueryService transactionQueryService;

    @Inject
    TransactionCommandService transactionCommandService;

    @Inject
    TransactionAmountService transactionAmountService;

    @Inject
    TransactionMethodService transactionMethodService;

    @Inject
    TransactionStatusService transactionStatusService;

    @Inject
    TransactionAmountByCardService transactionAmountByCardService;

    @Inject
    TransactionMethodByCardService transactionMethodByCardService;

    @Inject
    TransactionStatusByCardService transactionStatusByCardService;

    @Query
    @Description("Find all transactions paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<TransactionResponse>>> findAllTransactions(
            @Name("req") FindAllTransactions req) {
        return transactionQueryService.findAll(req);
    }

    @Query
    @Description("Find all transactions by card number paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<TransactionResponse>>> findAllTransactionsByCardNumber(
            @Name("req") FindAllTransactionCardNumber req) {
        return transactionQueryService.findAllByCardNumber(req);
    }

    @Query
    @Description("Find active transactions paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<TransactionResponseDeleteAt>>> findActiveTransactions(
            @Name("req") FindAllTransactions req) {
        return transactionQueryService.findByActive(req);
    }

    @Query
    @Description("Find trashed transactions paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<TransactionResponseDeleteAt>>> findTrashedTransactions(
            @Name("req") FindAllTransactions req) {
        return transactionQueryService.findByTrashed(req);
    }

    @Query
    @Description("Find transaction by ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<TransactionResponse>> findTransactionById(@Name("id") Long id) {
        return transactionQueryService.findById(id);
    }

    @Query
    @Description("Find transactions by merchant ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<TransactionResponse>>> findTransactionsByMerchantId(
            @Name("merchantId") Long merchantId) {
        return transactionQueryService.findByMerchantId(merchantId);
    }

    @Mutation
    @Description("Create a new transaction")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<TransactionResponse>> createTransaction(
            @HeaderParam("X-api-Key") String apiKey,
            @Name("req") CreateTransactionRequest req) {
        return transactionCommandService.create(apiKey, req);
    }

    @Mutation
    @Description("Update an existing transaction")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<TransactionResponse>> updateTransaction(
            @HeaderParam("X-api-Key") String apiKey,
            @Name("req") UpdateTransactionRequest req) {
        return transactionCommandService.update(apiKey, req);
    }

    @Mutation
    @Description("Trash a transaction by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<TransactionResponseDeleteAt>> trashTransaction(@Name("id") Long id) {
        return transactionCommandService.trashed(id);
    }

    @Mutation
    @Description("Restore a trashed transaction by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<TransactionResponseDeleteAt>> restoreTransaction(@Name("id") Long id) {
        return transactionCommandService.restore(id);
    }

    @Mutation
    @Description("Permanently delete a transaction by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> deleteTransactionPermanent(@Name("id") Long id) {
        return transactionCommandService.deletePermanent(id);
    }

    @Mutation
    @Description("Restore all trashed transactions - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> restoreAllTransactions() {
        return transactionCommandService.restoreAll();
    }

    @Mutation
    @Description("Permanently delete all trashed transactions - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> deleteAllTransactions() {
        return transactionCommandService.deleteAll();
    }

    @Query
    @Description("Get monthly transaction amounts stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionMonthAmountResponse>>> findMonthlyTransactionAmounts(
            @Name("year") Long year) {
        return transactionAmountService.findMonthlyAmounts(year);
    }

    @Query
    @Description("Get yearly transaction amounts stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionYearlyAmountResponse>>> findYearlyTransactionAmounts(
            @Name("year") Long year) {
        return transactionAmountService.findYearlyAmounts(year);
    }

    @Query
    @Description("Get monthly transaction amounts stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionMonthAmountResponse>>> findMonthlyTransactionAmountsByCard(
            @Name("req") MonthYearPaymentMethod req) {
        return transactionAmountByCardService.findMonthlyAmounts(req);
    }

    @Query
    @Description("Get yearly transaction amounts stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionYearlyAmountResponse>>> findYearlyTransactionAmountsByCard(
            @Name("req") MonthYearPaymentMethod req) {
        return transactionAmountByCardService.findYearlyAmounts(req);
    }

    @Query
    @Description("Get monthly transaction payment methods usage stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionMonthMethodResponse>>> findMonthlyTransactionMethods(
            @Name("year") Long year) {
        return transactionMethodService.findMonthlyMethod(year);
    }

    @Query
    @Description("Get yearly transaction payment methods usage stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionYearMethodResponse>>> findYearlyTransactionMethods(@Name("year") Long year) {
        return transactionMethodService.findYearlyMethod(year);
    }

    @Query
    @Description("Get monthly transaction payment methods stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionMonthMethodResponse>>> findMonthlyTransactionMethodsByCard(
            @Name("req") MonthYearPaymentMethod req) {
        return transactionMethodByCardService.findMonthlyMethod(req);
    }

    @Query
    @Description("Get yearly transaction payment methods stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionYearMethodResponse>>> findYearlyTransactionMethodsByCard(
            @Name("req") MonthYearPaymentMethod req) {
        return transactionMethodByCardService.findYearlyMethod(req);
    }

    @Query
    @Description("Get monthly transaction success status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionResponseMonthStatusSuccess>>> findMonthlyTransactionStatusSuccess(
            @Name("req") MonthStatusTransaction req) {
        return transactionStatusService.findMonthStatusSuccess(req);
    }

    @Query
    @Description("Get yearly transaction success status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionResponseYearStatusSuccess>>> findYearlyTransactionStatusSuccess(
            @Name("year") Long year) {
        return transactionStatusService.findYearlyStatusSuccess(year);
    }

    @Query
    @Description("Get monthly transaction failed status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionResponseMonthStatusFailed>>> findMonthlyTransactionStatusFailed(
            @Name("req") MonthStatusTransaction req) {
        return transactionStatusService.findMonthStatusFailed(req);
    }

    @Query
    @Description("Get yearly transaction failed status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionResponseYearStatusFailed>>> findYearlyTransactionStatusFailed(
            @Name("year") Long year) {
        return transactionStatusService.findYearlyStatusFailed(year);
    }

    @Query
    @Description("Get monthly transaction success status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionResponseMonthStatusSuccess>>> findMonthlyTransactionStatusSuccessByCard(
            @Name("req") MonthStatusTransactionCardNumber req) {
        return transactionStatusByCardService.findMonthStatusSuccess(req);
    }

    @Query
    @Description("Get yearly transaction success status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionResponseYearStatusSuccess>>> findYearlyTransactionStatusSuccessByCard(
            @Name("req") YearStatusTransactionCardNumber req) {
        return transactionStatusByCardService.findYearlyStatusSuccess(req);
    }

    @Query
    @Description("Get monthly transaction failed status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionResponseMonthStatusFailed>>> findMonthlyTransactionStatusFailedByCard(
            @Name("req") MonthStatusTransactionCardNumber req) {
        return transactionStatusByCardService.findMonthStatusFailed(req);
    }

    @Query
    @Description("Get yearly transaction failed status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransactionResponseYearStatusFailed>>> findYearlyTransactionStatusFailedByCard(
            @Name("req") YearStatusTransactionCardNumber req) {
        return transactionStatusByCardService.findYearlyStatusFailed(req);
    }
}
