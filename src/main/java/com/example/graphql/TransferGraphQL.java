package com.example.graphql;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

import com.example.domain.requests.transfers.CreateTransferRequest;
import com.example.domain.requests.transfers.FindAllTransfers;
import com.example.domain.requests.transfers.UpdateTransferRequest;
import com.example.domain.requests.transfers.stats.MonthStatusTransfer;
import com.example.domain.requests.transfers.statsbycard.MonthStatusTransferCardNumber;
import com.example.domain.requests.transfers.statsbycard.MonthYearCardNumber;
import com.example.domain.requests.transfers.statsbycard.YearStatusTransferCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.transfer.TransferResponse;
import com.example.domain.responses.transfer.TransferResponseDeleteAt;
import com.example.domain.responses.transfer.stats.amount.TransferMonthAmountResponse;
import com.example.domain.responses.transfer.stats.amount.TransferYearAmountResponse;
import com.example.domain.responses.transfer.stats.status.TransferResponseMonthStatusFailed;
import com.example.domain.responses.transfer.stats.status.TransferResponseMonthStatusSuccess;
import com.example.domain.responses.transfer.stats.status.TransferResponseYearStatusFailed;
import com.example.domain.responses.transfer.stats.status.TransferResponseYearStatusSuccess;
import com.example.service.transfer.TransferCommandService;
import com.example.service.transfer.TransferQueryService;
import com.example.service.transfer.stats.amount.TransferAmountByCardService;
import com.example.service.transfer.stats.amount.TransferAmountService;
import com.example.service.transfer.stats.status.TransferStatusByCardService;
import com.example.service.transfer.stats.status.TransferStatusService;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

@GraphQLApi
public class TransferGraphQL {

    @Inject
    TransferQueryService transferQueryService;

    @Inject
    TransferCommandService transferCommandService;

    @Inject
    TransferAmountService transferAmountService;

    @Inject
    TransferStatusService transferStatusService;

    @Inject
    TransferAmountByCardService transferAmountByCardService;

    @Inject
    TransferStatusByCardService transferStatusByCardService;

    @Query
    @Description("Find all transfers paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<TransferResponse>>> findAllTransfers(@Name("req") FindAllTransfers req) {
        return transferQueryService.findAll(req);
    }

    @Query
    @Description("Find transfer by ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<TransferResponse>> findTransferById(@Name("id") Long id) {
        return transferQueryService.findById(id);
    }

    @Query
    @Description("Find active transfers paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<TransferResponseDeleteAt>>> findActiveTransfers(
            @Name("req") FindAllTransfers req) {
        return transferQueryService.findByActive(req);
    }

    @Query
    @Description("Find trashed transfers paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<TransferResponseDeleteAt>>> findTrashedTransfers(
            @Name("req") FindAllTransfers req) {
        return transferQueryService.findByTrashed(req);
    }

    @Query
    @Description("Find transfers by sender card number")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<TransferResponse>>> findTransfersByFrom(@Name("transferFrom") String transferFrom) {
        return transferQueryService.findByTransferFrom(transferFrom);
    }

    @Query
    @Description("Find transfers by receiver card number")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<TransferResponse>>> findTransfersByTo(@Name("transferTo") String transferTo) {
        return transferQueryService.findByTransferTo(transferTo);
    }

    @Mutation
    @Description("Create a new transfer")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<TransferResponse>> createTransfer(@Name("req") CreateTransferRequest req) {
        return transferCommandService.create(req);
    }

    @Mutation
    @Description("Update an existing transfer")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<TransferResponse>> updateTransfer(@Name("req") UpdateTransferRequest req) {
        return transferCommandService.update(req);
    }

    @Mutation
    @Description("Trash a transfer by ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<TransferResponseDeleteAt>> trashTransfer(@Name("id") Long id) {
        return transferCommandService.trashed(id);
    }

    @Mutation
    @Description("Restore a trashed transfer by ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<TransferResponseDeleteAt>> restoreTransfer(@Name("id") Long id) {
        return transferCommandService.restore(id);
    }

    @Mutation
    @Description("Permanently delete a transfer by ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<Boolean>> deleteTransferPermanent(@Name("id") Long id) {
        return transferCommandService.deletePermanent(id);
    }

    @Mutation
    @Description("Restore all trashed transfers - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> restoreAllTransfers() {
        return transferCommandService.restoreAll();
    }

    @Mutation
    @Description("Permanently delete all trashed transfers - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> deleteAllTransfers() {
        return transferCommandService.deleteAll();
    }

    @Query
    @Description("Get monthly transfer amounts stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransferMonthAmountResponse>>> getMonthlyTransferAmounts(@Name("year") Long year) {
        return transferAmountService.findMonthlyAmounts(year);
    }

    @Query
    @Description("Get yearly transfer amounts stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransferYearAmountResponse>>> getYearlyTransferAmounts(@Name("year") Long year) {
        return transferAmountService.findYearlyAmounts(year);
    }

    @Query
    @Description("Get monthly transfer amounts stats by card (sender perspective)")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransferMonthAmountResponse>>> getMonthlyTransferAmountsBySender(
            @Name("req") MonthYearCardNumber req) {
        return transferAmountByCardService.findMonthlyAmountsBySender(req);
    }

    @Query
    @Description("Get monthly transfer amounts stats by card (receiver perspective)")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransferMonthAmountResponse>>> getMonthlyTransferAmountsByReceiver(
            @Name("req") MonthYearCardNumber req) {
        return transferAmountByCardService.findMonthlyAmountsByReceiver(req);
    }

    @Query
    @Description("Get yearly transfer amounts stats by card (sender perspective)")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransferYearAmountResponse>>> getYearlyTransferAmountsBySender(
            @Name("req") MonthYearCardNumber req) {
        return transferAmountByCardService.findYearlyAmountsBySender(req);
    }

    @Query
    @Description("Get yearly transfer amounts stats by card (receiver perspective)")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransferYearAmountResponse>>> getYearlyTransferAmountsByReceiver(
            @Name("req") MonthYearCardNumber req) {
        return transferAmountByCardService.findYearlyAmountsByReceiver(req);
    }

    @Query
    @Description("Get monthly transfer success status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransferResponseMonthStatusSuccess>>> getMonthlyTransferStatusSuccess(
            @Name("req") MonthStatusTransfer req) {
        return transferStatusService.findMonthStatusSuccess(req);
    }

    @Query
    @Description("Get monthly transfer failed status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransferResponseMonthStatusFailed>>> getMonthlyTransferStatusFailed(
            @Name("req") MonthStatusTransfer req) {
        return transferStatusService.findMonthStatusFailed(req);
    }

    @Query
    @Description("Get yearly transfer success status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransferResponseYearStatusSuccess>>> getYearlyTransferStatusSuccess(
            @Name("year") Long year) {
        return transferStatusService.findYearlyStatusSuccess(year);
    }

    @Query
    @Description("Get yearly transfer failed status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransferResponseYearStatusFailed>>> getYearlyTransferStatusFailed(
            @Name("year") Long year) {
        return transferStatusService.findYearlyStatusFailed(year);
    }

    @Query
    @Description("Get monthly transfer success status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransferResponseMonthStatusSuccess>>> getMonthlyTransferStatusSuccessByCard(
            @Name("req") MonthStatusTransferCardNumber req) {
        return transferStatusByCardService.findMonthStatusSuccessByCard(req);
    }

    @Query
    @Description("Get yearly transfer success status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransferResponseYearStatusSuccess>>> getYearlyTransferStatusSuccessByCard(
            @Name("req") YearStatusTransferCardNumber req) {
        return transferStatusByCardService.findYearlyStatusSuccessByCard(req);
    }

    @Query
    @Description("Get monthly transfer failed status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransferResponseMonthStatusFailed>>> getMonthlyTransferStatusFailedByCard(
            @Name("req") MonthStatusTransferCardNumber req) {
        return transferStatusByCardService.findMonthStatusFailedByCard(req);
    }

    @Query
    @Description("Get yearly transfer failed status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TransferResponseYearStatusFailed>>> getYearlyTransferStatusFailedByCard(
            @Name("req") YearStatusTransferCardNumber req) {
        return transferStatusByCardService.findYearlyStatusFailedByCard(req);
    }
}
