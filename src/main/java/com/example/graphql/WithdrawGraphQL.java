package com.example.graphql;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

import com.example.domain.requests.withdraws.CreateWithdrawRequest;
import com.example.domain.requests.withdraws.FindAllWithdrawCardNumber;
import com.example.domain.requests.withdraws.FindAllWithdraws;
import com.example.domain.requests.withdraws.MonthStatusWithdraw;
import com.example.domain.requests.withdraws.UpdateWithdrawRequest;
import com.example.domain.requests.withdraws.statsbycard.MonthStatusWithdrawCardNumber;
import com.example.domain.requests.withdraws.statsbycard.YearMonthCardNumber;
import com.example.domain.requests.withdraws.statsbycard.YearStatusWithdrawCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.withdraw.WithdrawResponse;
import com.example.domain.responses.withdraw.WithdrawResponseDeleteAt;
import com.example.domain.responses.withdraw.stats.amount.WithdrawMonthlyAmountResponse;
import com.example.domain.responses.withdraw.stats.amount.WithdrawYearlyAmountResponse;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseMonthStatusFailed;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseMonthStatusSuccess;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseYearStatusFailed;
import com.example.domain.responses.withdraw.stats.status.WithdrawResponseYearStatusSuccess;
import com.example.service.withdraw.WithdrawCommandService;
import com.example.service.withdraw.WithdrawQueryService;
import com.example.service.withdraw.stats.amount.WithdrawAmountByCardService;
import com.example.service.withdraw.stats.amount.WithdrawAmountService;
import com.example.service.withdraw.stats.status.WithdrawStatusByCardService;
import com.example.service.withdraw.stats.status.WithdrawStatusService;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

@GraphQLApi
public class WithdrawGraphQL {

    @Inject
    WithdrawQueryService withdrawQueryService;

    @Inject
    WithdrawCommandService withdrawCommandService;

    @Inject
    WithdrawAmountService withdrawAmountService;

    @Inject
    WithdrawStatusService withdrawStatusService;

    @Inject
    WithdrawAmountByCardService withdrawAmountByCardService;

    @Inject
    WithdrawStatusByCardService withdrawStatusByCardService;

    @Query
    @Description("Find all withdraws paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<WithdrawResponse>>> findAllWithdraws(@Name("req") FindAllWithdraws req) {
        return withdrawQueryService.findAll(req);
    }

    @Query
    @Description("Find all withdraws by card number paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<WithdrawResponse>>> findAllWithdrawsByCardNumber(
            @Name("req") FindAllWithdrawCardNumber req) {
        return withdrawQueryService.findAllByCardNumber(req);
    }

    @Query
    @Description("Find withdraw by ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<WithdrawResponse>> findWithdrawById(@Name("id") Long id) {
        return withdrawQueryService.findById(id);
    }

    @Query
    @Description("Find withdraws by card number")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<WithdrawResponse>>> findWithdrawsByCard(@Name("cardNumber") String cardNumber) {
        return withdrawQueryService.findByCard(cardNumber);
    }

    @Query
    @Description("Find active withdraws paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<WithdrawResponseDeleteAt>>> findActiveWithdraws(
            @Name("req") FindAllWithdraws req) {
        return withdrawQueryService.findByActive(req);
    }

    @Query
    @Description("Find trashed withdraws paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<WithdrawResponseDeleteAt>>> findTrashedWithdraws(
            @Name("req") FindAllWithdraws req) {
        return withdrawQueryService.findByTrashed(req);
    }

    @Mutation
    @Description("Create a new withdraw - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<WithdrawResponse>> createWithdraw(@Name("req") CreateWithdrawRequest req) {
        return withdrawCommandService.create(req);
    }

    @Mutation
    @Description("Update an existing withdraw - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<WithdrawResponse>> updateWithdraw(@Name("req") UpdateWithdrawRequest req) {
        return withdrawCommandService.update(req);
    }

    @Mutation
    @Description("Trash a withdraw by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<WithdrawResponseDeleteAt>> trashWithdraw(@Name("id") Long id) {
        return withdrawCommandService.trashed(id);
    }

    @Mutation
    @Description("Restore a trashed withdraw by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<WithdrawResponseDeleteAt>> restoreWithdraw(@Name("id") Long id) {
        return withdrawCommandService.restore(id);
    }

    @Mutation
    @Description("Permanently delete a withdraw by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> deleteWithdrawPermanent(@Name("id") Long id) {
        return withdrawCommandService.deletePermanent(id);
    }

    @Mutation
    @Description("Restore all trashed withdraws - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> restoreAllWithdraws() {
        return withdrawCommandService.restoreAll();
    }

    @Mutation
    @Description("Permanently delete all trashed withdraws - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> deleteAllWithdraws() {
        return withdrawCommandService.deleteAll();
    }

    @Query
    @Description("Get monthly withdraw amounts stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<WithdrawMonthlyAmountResponse>>> getMonthlyWithdrawAmounts(@Name("year") Long year) {
        return withdrawAmountService.findMonthlyWithdraws(year);
    }

    @Query
    @Description("Get yearly withdraw amounts stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<WithdrawYearlyAmountResponse>>> getYearlyWithdrawAmounts(@Name("year") Long year) {
        return withdrawAmountService.findYearlyWithdraws(year);
    }

    @Query
    @Description("Get monthly withdraw amounts stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<WithdrawMonthlyAmountResponse>>> getMonthlyWithdrawAmountsByCard(
            @Name("req") YearMonthCardNumber req) {
        return withdrawAmountByCardService.findMonthlyByCardNumber(req);
    }

    @Query
    @Description("Get yearly withdraw amounts stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<WithdrawYearlyAmountResponse>>> getYearlyWithdrawAmountsByCard(
            @Name("req") YearMonthCardNumber req) {
        return withdrawAmountByCardService.findYearlyByCardNumber(req);
    }

    @Query
    @Description("Get monthly withdraw success status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<WithdrawResponseMonthStatusSuccess>>> getMonthlyWithdrawStatusSuccess(
            @Name("req") MonthStatusWithdraw req) {
        return withdrawStatusService.findMonthStatusSuccess(req);
    }

    @Query
    @Description("Get yearly withdraw success status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<WithdrawResponseYearStatusSuccess>>> getYearlyWithdrawStatusSuccess(
            @Name("year") Long year) {
        return withdrawStatusService.findYearlyStatusSuccess(year);
    }

    @Query
    @Description("Get monthly withdraw failed status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<WithdrawResponseMonthStatusFailed>>> getMonthlyWithdrawStatusFailed(
            @Name("req") MonthStatusWithdraw req) {
        return withdrawStatusService.findMonthStatusFailed(req);
    }

    @Query
    @Description("Get yearly withdraw failed status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<WithdrawResponseYearStatusFailed>>> getYearlyWithdrawStatusFailed(
            @Name("year") Long year) {
        return withdrawStatusService.findYearlyStatusFailed(year);
    }

    @Query
    @Description("Get monthly withdraw success status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<WithdrawResponseMonthStatusSuccess>>> getMonthlyWithdrawStatusSuccessByCard(
            @Name("req") MonthStatusWithdrawCardNumber req) {
        return withdrawStatusByCardService.findMonthStatusSuccessByCard(req);
    }

    @Query
    @Description("Get yearly withdraw success status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<WithdrawResponseYearStatusSuccess>>> getYearlyWithdrawStatusSuccessByCard(
            @Name("req") YearStatusWithdrawCardNumber req) {
        return withdrawStatusByCardService.findYearlyStatusSuccessByCard(req);
    }

    @Query
    @Description("Get monthly withdraw failed status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<WithdrawResponseMonthStatusFailed>>> getMonthlyWithdrawStatusFailedByCard(
            @Name("req") MonthStatusWithdrawCardNumber req) {
        return withdrawStatusByCardService.findMonthStatusFailedByCard(req);
    }

    @Query
    @Description("Get yearly withdraw failed status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<WithdrawResponseYearStatusFailed>>> getYearlyWithdrawStatusFailedByCard(
            @Name("req") YearStatusWithdrawCardNumber req) {
        return withdrawStatusByCardService.findYearlyStatusFailedByCard(req);
    }
}
