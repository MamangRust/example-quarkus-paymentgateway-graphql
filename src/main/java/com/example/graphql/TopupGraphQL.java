package com.example.graphql;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

import com.example.domain.requests.topup.CreateTopupRequest;
import com.example.domain.requests.topup.FindAllTopups;
import com.example.domain.requests.topup.FindAllTopupsByCardNumber;
import com.example.domain.requests.topup.UpdateTopupRequest;
import com.example.domain.requests.topup.stats.MonthTopupStatus;
import com.example.domain.requests.topup.stats.YearMonthMethod;
import com.example.domain.requests.topup.statsbycard.MonthTopupStatusCardNumber;
import com.example.domain.requests.topup.statsbycard.YearTopupStatusCardNumber;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.topup.TopupResponse;
import com.example.domain.responses.topup.TopupResponseDeleteAt;
import com.example.domain.responses.topup.stats.amount.TopupMonthAmountResponse;
import com.example.domain.responses.topup.stats.amount.TopupYearlyAmountResponse;
import com.example.domain.responses.topup.stats.method.TopupMonthMethodResponse;
import com.example.domain.responses.topup.stats.method.TopupYearlyMethodResponse;
import com.example.domain.responses.topup.stats.status.TopupResponseMonthStatusFailed;
import com.example.domain.responses.topup.stats.status.TopupResponseMonthStatusSuccess;
import com.example.domain.responses.topup.stats.status.TopupResponseYearStatusFailed;
import com.example.domain.responses.topup.stats.status.TopupResponseYearStatusSuccess;
import com.example.service.topup.TopupCommandService;
import com.example.service.topup.TopupQueryService;
import com.example.service.topup.stats.amount.TopupAmountByCardService;
import com.example.service.topup.stats.amount.TopupAmountService;
import com.example.service.topup.stats.method.TopupMethodByCardService;
import com.example.service.topup.stats.method.TopupMethodService;
import com.example.service.topup.stats.status.TopupStatusByCardService;
import com.example.service.topup.stats.status.TopupStatusService;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

@GraphQLApi
public class TopupGraphQL {

    @Inject
    TopupQueryService topupQueryService;

    @Inject
    TopupCommandService topupCommandService;

    @Inject
    TopupAmountService topupAmountService;

    @Inject
    TopupMethodService topupMethodService;

    @Inject
    TopupStatusService topupStatusService;

    @Inject
    TopupAmountByCardService topupAmountByCardService;

    @Inject
    TopupMethodByCardService topupMethodByCardService;

    @Inject
    TopupStatusByCardService topupStatusByCardService;

    @Query
    @Description("Find all topups paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<TopupResponse>>> findAllTopups(@Name("req") FindAllTopups req) {
        return topupQueryService.findAll(req);
    }

    @Query
    @Description("Find all topups by card number paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<TopupResponse>>> findAllTopupsByCardNumber(
            @Name("req") FindAllTopupsByCardNumber req) {
        return topupQueryService.findAllByCardNumber(req);
    }

    @Query
    @Description("Find active topups paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<TopupResponseDeleteAt>>> findActiveTopups(@Name("req") FindAllTopups req) {
        return topupQueryService.findActive(req);
    }

    @Query
    @Description("Find trashed topups paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<TopupResponseDeleteAt>>> findTrashedTopups(@Name("req") FindAllTopups req) {
        return topupQueryService.findTrashed(req);
    }

    @Query
    @Description("Find topup by ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<TopupResponse>> findTopupById(@Name("id") Long id) {
        return topupQueryService.findById(id);
    }

    @Query
    @Description("Find topups by card number")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<TopupResponse>>> findTopupsByCard(@Name("cardNumber") String cardNumber) {
        return topupQueryService.findByCard(cardNumber);
    }

    @Mutation
    @Description("Create a new topup")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<TopupResponse>> createTopup(@Name("req") CreateTopupRequest req) {
        return topupCommandService.create(req);
    }

    @Mutation
    @Description("Update an existing topup")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<TopupResponse>> updateTopup(@Name("req") UpdateTopupRequest req) {
        return topupCommandService.update(req);
    }

    @Mutation
    @Description("Trash a topup by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<TopupResponseDeleteAt>> trashTopup(@Name("id") Long id) {
        return topupCommandService.trashed(id);
    }

    @Mutation
    @Description("Restore a trashed topup by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<TopupResponseDeleteAt>> restoreTopup(@Name("id") Long id) {
        return topupCommandService.restore(id);
    }

    @Mutation
    @Description("Permanently delete a topup by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> deleteTopupPermanent(@Name("id") Long id) {
        return topupCommandService.deletePermanent(id);
    }

    @Mutation
    @Description("Restore all trashed topups - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> restoreAllTopups() {
        return topupCommandService.restoreAll();
    }

    @Mutation
    @Description("Permanently delete all trashed topups - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> deleteAllTopups() {
        return topupCommandService.deleteAll();
    }

    @Query
    @Description("Get monthly topup amounts stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupMonthAmountResponse>>> getMonthlyTopupAmounts(@Name("year") Long year) {
        return topupAmountService.findMonthlyAmounts(year);
    }

    @Query
    @Description("Get yearly topup amounts stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupYearlyAmountResponse>>> getYearlyTopupAmounts(@Name("year") Long year) {
        return topupAmountService.findYearlyAmounts(year);
    }

    @Query
    @Description("Get monthly topup amounts stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupMonthAmountResponse>>> getMonthlyTopupAmountsByCard(
            @Name("req") YearMonthMethod req) {
        return topupAmountByCardService.findMonthlyAmounts(req);
    }

    @Query
    @Description("Get yearly topup amounts stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupYearlyAmountResponse>>> getYearlyTopupAmountsByCard(
            @Name("req") YearMonthMethod req) {
        return topupAmountByCardService.findYearlyAmounts(req);
    }

    @Query
    @Description("Get monthly topup methods usage stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupMonthMethodResponse>>> getMonthlyTopupMethods(@Name("year") Long year) {
        return topupMethodService.findMonthlyMethods(year);
    }

    @Query
    @Description("Get yearly topup methods usage stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupYearlyMethodResponse>>> getYearlyTopupMethods(@Name("year") Long year) {
        return topupMethodService.findYearlyMethods(year);
    }

    @Query
    @Description("Get monthly topup methods stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupMonthMethodResponse>>> getMonthlyTopupMethodsByCard(
            @Name("req") YearMonthMethod req) {
        return topupMethodByCardService.findMonthlyMethods(req);
    }

    @Query
    @Description("Get yearly topup methods stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupYearlyMethodResponse>>> getYearlyTopupMethodsByCard(
            @Name("req") YearMonthMethod req) {
        return topupMethodByCardService.findYearlyMethods(req);
    }

    @Query
    @Description("Get monthly topup success status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupResponseMonthStatusSuccess>>> getMonthlyTopupStatusSuccess(
            @Name("req") MonthTopupStatus req) {
        return topupStatusService.findMonthStatusSuccess(req);
    }

    @Query
    @Description("Get yearly topup success status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupResponseYearStatusSuccess>>> getYearlyTopupStatusSuccess(@Name("year") Long year) {
        return topupStatusService.findYearlyStatusSuccess(year);
    }

    @Query
    @Description("Get monthly topup failed status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupResponseMonthStatusFailed>>> getMonthlyTopupStatusFailed(
            @Name("req") MonthTopupStatus req) {
        return topupStatusService.findMonthStatusFailed(req);
    }

    @Query
    @Description("Get yearly topup failed status stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupResponseYearStatusFailed>>> getYearlyTopupStatusFailed(@Name("year") Long year) {
        return topupStatusService.findYearlyStatusFailed(year);
    }

    @Query
    @Description("Get monthly topup success status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupResponseMonthStatusSuccess>>> getMonthlyTopupStatusSuccessByCard(
            @Name("req") MonthTopupStatusCardNumber req) {
        return topupStatusByCardService.findMonthStatusSuccess(req);
    }

    @Query
    @Description("Get yearly topup success status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupResponseYearStatusSuccess>>> getYearlyTopupStatusSuccessByCard(
            @Name("req") YearTopupStatusCardNumber req) {
        return topupStatusByCardService.findYearlyStatusSuccess(req);
    }

    @Query
    @Description("Get monthly topup failed status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupResponseMonthStatusFailed>>> getMonthlyTopupStatusFailedByCard(
            @Name("req") MonthTopupStatusCardNumber req) {
        return topupStatusByCardService.findMonthStatusFailed(req);
    }

    @Query
    @Description("Get yearly topup failed status stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<TopupResponseYearStatusFailed>>> getYearlyTopupStatusFailedByCard(
            @Name("req") YearTopupStatusCardNumber req) {
        return topupStatusByCardService.findYearlyStatusFailed(req);
    }
}
