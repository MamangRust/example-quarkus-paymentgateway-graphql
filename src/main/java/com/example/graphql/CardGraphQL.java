package com.example.graphql;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

import com.example.domain.requests.card.CreateCardRequest;
import com.example.domain.requests.card.FindAllCards;
import com.example.domain.requests.card.MonthYearCardNumberCard;
import com.example.domain.requests.card.UpdateCardRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.card.CardResponse;
import com.example.domain.responses.card.CardResponseDeleteAt;
import com.example.domain.responses.card.dashboard.CardDashboard;
import com.example.domain.responses.card.dashboard.CardDashboardCard;
import com.example.domain.responses.card.stats.amount.CardResponseMonthAmount;
import com.example.domain.responses.card.stats.amount.CardResponseYearAmount;
import com.example.domain.responses.card.stats.balance.CardResponseMonthBalance;
import com.example.domain.responses.card.stats.balance.CardResponseYearBalance;
import com.example.service.card.CardCommandService;
import com.example.service.card.CardDashboardService;
import com.example.service.card.CardQueryService;
import com.example.service.card.stats.CardBalanceService;
import com.example.service.card.stats.CardTopupAmountService;
import com.example.service.card.stats.CardTransactionAmountService;
import com.example.service.card.stats.CardTransferAmountService;
import com.example.service.card.stats.CardWithdrawAmountService;
import com.example.service.card.statsbycard.CardBalanceByCardService;
import com.example.service.card.statsbycard.CardTopupAmountByCardService;
import com.example.service.card.statsbycard.CardTransactionAmountByCardService;
import com.example.service.card.statsbycard.CardTransferAmountByCardService;
import com.example.service.card.statsbycard.CardWithdrawAmountByCardService;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

@GraphQLApi
public class CardGraphQL {

    @Inject
    CardQueryService cardQueryService;

    @Inject
    CardCommandService cardCommandService;

    @Inject
    CardBalanceService cardBalanceService;

    @Inject
    CardTopupAmountService cardTopupAmountService;

    @Inject
    CardTransactionAmountService cardTransactionAmountService;

    @Inject
    CardTransferAmountService cardTransferAmountService;

    @Inject
    CardWithdrawAmountService cardWithdrawAmountService;

    @Inject
    CardDashboardService cardDashboardService;

    @Inject
    CardBalanceByCardService cardBalanceByCardService;

    @Inject
    CardTopupAmountByCardService cardTopupAmountByCardService;

    @Inject
    CardTransactionAmountByCardService cardTransactionAmountByCardService;

    @Inject
    CardTransferAmountByCardService cardTransferAmountByCardService;

    @Inject
    CardWithdrawAmountByCardService cardWithdrawAmountByCardService;

    @Query
    @Description("Find all cards paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<CardResponse>>> findCards(@Name("req") FindAllCards req) {
        return cardQueryService.findAll(req);
    }

    @Query
    @Description("Find active cards paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<CardResponseDeleteAt>>> findActiveCards(@Name("req") FindAllCards req) {
        return cardQueryService.findByActive(req);
    }

    @Query
    @Description("Find trashed cards paginated - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponsePagination<List<CardResponseDeleteAt>>> findTrashedCards(@Name("req") FindAllCards req) {
        return cardQueryService.findByTrashed(req);
    }

    @Query
    @Description("Find a card by ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<CardResponse>> findCard(@Name("id") Long id) {
        return cardQueryService.findById(id);
    }

    @Query
    @Description("Find a card by User ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<CardResponse>> findCardByUser(@Name("userId") Long userId) {
        return cardQueryService.findByUserId(userId);
    }

    @Query
    @Description("Find a card by its Card Number")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<CardResponse>> findCardByNumber(@Name("cardNumber") String cardNumber) {
        return cardQueryService.findByCardNumber(cardNumber);
    }

    @Mutation
    @Description("Create a new card - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<CardResponse>> createCard(@Name("request") CreateCardRequest request) {
        return cardCommandService.createCard(request);
    }

    @Mutation
    @Description("Update an existing card - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<CardResponse>> updateCard(@Name("request") UpdateCardRequest request) {
        return cardCommandService.updateCard(request);
    }

    @Mutation
    @Description("Trash a card by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<CardResponseDeleteAt>> trashCard(@Name("id") Long id) {
        return cardCommandService.trashCard(id);
    }

    @Mutation
    @Description("Restore a trashed card by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<CardResponseDeleteAt>> restoreCard(@Name("id") Long id) {
        return cardCommandService.restoreCard(id);
    }

    @Mutation
    @Description("Permanently delete a card by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> deleteCard(@Name("id") Long id) {
        return cardCommandService.deleteCard(id);
    }

    @Mutation
    @Description("Restore all trashed cards - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> restoreAllCards() {
        return cardCommandService.restoreAll();
    }

    @Mutation
    @Description("Permanently delete all trashed cards - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> deleteAllCards() {
        return cardCommandService.deleteAll();
    }

    @Query
    @Description("Find monthly balance stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<CardResponseMonthBalance>>> findMonthlyBalance(@Name("year") Long year) {
        return cardBalanceService.findMonthBalance(year);
    }

    @Query
    @Description("Find yearly balance stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<CardResponseYearBalance>>> findYearlyBalance(@Name("year") Long year) {
        return cardBalanceService.findYearBalance(year);
    }

    @Query
    @Description("Find monthly topup amount stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthlyTopupAmount(@Name("year") Long year) {
        return cardTopupAmountService.findMonthAmount(year);
    }

    @Query
    @Description("Find yearly topup amount stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<CardResponseYearAmount>>> findYearlyTopupAmount(@Name("year") Long year) {
        return cardTopupAmountService.findYearAmount(year);
    }

    @Query
    @Description("Find monthly transaction amount stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthlyTransactionAmount(@Name("year") Long year) {
        return cardTransactionAmountService.findMonthAmount(year);
    }

    @Query
    @Description("Find yearly transaction amount stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<CardResponseYearAmount>>> findYearlyTransactionAmount(@Name("year") Long year) {
        return cardTransactionAmountService.findYearAmount(year);
    }

    @Query
    @Description("Find monthly transfer amount (sender perspective) stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthlyTransferAmountSender(@Name("year") Long year) {
        return cardTransferAmountService.findMonthAmountSender(year);
    }

    @Query
    @Description("Find monthly transfer amount (receiver perspective) stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthlyTransferAmountReceiver(@Name("year") Long year) {
        return cardTransferAmountService.findMonthAmountReceiver(year);
    }

    @Query
    @Description("Find yearly transfer amount (sender perspective) stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<CardResponseYearAmount>>> findYearlyTransferAmountSender(@Name("year") Long year) {
        return cardTransferAmountService.findYearAmountSender(year);
    }

    @Query
    @Description("Find yearly transfer amount (receiver perspective) stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<CardResponseYearAmount>>> findYearlyTransferAmountReceiver(@Name("year") Long year) {
        return cardTransferAmountService.findYearAmountReceiver(year);
    }

    @Query
    @Description("Find monthly withdraw amount stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<CardResponseMonthAmount>>> findMonthlyWithdrawAmount(@Name("year") Long year) {
        return cardWithdrawAmountService.findMonthAmount(year);
    }

    @Query
    @Description("Find yearly withdraw amount stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<CardResponseYearAmount>>> findYearlyWithdrawAmount(@Name("year") Long year) {
        return cardWithdrawAmountService.findYearAmount(year);
    }

    @Query
    @Description("Get monthly balance stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<CardResponseMonthBalance>>> getMonthlyBalanceByCard(
            @Name("year") Long year,
            @Name("cardNumber") String cardNumber) {
        MonthYearCardNumberCard req = new MonthYearCardNumberCard(cardNumber, year);
        return cardBalanceByCardService.findMonthBalanceByCard(req);
    }

    @Query
    @Description("Get yearly balance stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<CardResponseYearBalance>>> getYearlyBalanceByCard(
            @Name("year") Long year,
            @Name("cardNumber") String cardNumber) {
        MonthYearCardNumberCard req = new MonthYearCardNumberCard(cardNumber, year);
        return cardBalanceByCardService.findYearBalanceByCard(req);
    }

    @Query
    @Description("Get monthly topup amount stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<CardResponseMonthAmount>>> getMonthlyTopupAmountByCard(
            @Name("year") Long year,
            @Name("cardNumber") String cardNumber) {
        MonthYearCardNumberCard req = new MonthYearCardNumberCard(cardNumber, year);
        return cardTopupAmountByCardService.findMonthAmountByCard(req);
    }

    @Query
    @Description("Get yearly topup amount stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<CardResponseYearAmount>>> getYearlyTopupAmountByCard(
            @Name("year") Long year,
            @Name("cardNumber") String cardNumber) {
        MonthYearCardNumberCard req = new MonthYearCardNumberCard(cardNumber, year);
        return cardTopupAmountByCardService.findYearAmountByCard(req);
    }

    @Query
    @Description("Get monthly transaction amount stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<CardResponseMonthAmount>>> getMonthlyTransactionAmountByCard(
            @Name("year") Long year,
            @Name("cardNumber") String cardNumber) {
        MonthYearCardNumberCard req = new MonthYearCardNumberCard(cardNumber, year);
        return cardTransactionAmountByCardService.findMonthAmountByCard(req);
    }

    @Query
    @Description("Get yearly transaction amount stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<CardResponseYearAmount>>> getYearlyTransactionAmountByCard(
            @Name("year") Long year,
            @Name("cardNumber") String cardNumber) {
        MonthYearCardNumberCard req = new MonthYearCardNumberCard(cardNumber, year);
        return cardTransactionAmountByCardService.findYearAmountByCard(req);
    }

    @Query
    @Description("Get monthly transfer amount stats by card (sender perspective)")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<CardResponseMonthAmount>>> getMonthlyTransferAmountByCardSender(
            @Name("year") Long year,
            @Name("cardNumber") String cardNumber) {
        MonthYearCardNumberCard req = new MonthYearCardNumberCard(cardNumber, year);
        return cardTransferAmountByCardService.findMonthAmountSender(req);
    }

    @Query
    @Description("Get monthly transfer amount stats by card (receiver perspective)")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<CardResponseMonthAmount>>> getMonthlyTransferAmountByCardReceiver(
            @Name("year") Long year,
            @Name("cardNumber") String cardNumber) {
        MonthYearCardNumberCard req = new MonthYearCardNumberCard(cardNumber, year);
        return cardTransferAmountByCardService.findMonthAmountReceiver(req);
    }

    @Query
    @Description("Get yearly transfer amount stats by card (sender perspective)")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<CardResponseYearAmount>>> getYearlyTransferAmountByCardSender(
            @Name("year") Long year,
            @Name("cardNumber") String cardNumber) {
        MonthYearCardNumberCard req = new MonthYearCardNumberCard(cardNumber, year);
        return cardTransferAmountByCardService.findYearAmountSender(req);
    }

    @Query
    @Description("Get yearly transfer amount stats by card (receiver perspective)")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<CardResponseYearAmount>>> getYearlyTransferAmountByCardReceiver(
            @Name("year") Long year,
            @Name("cardNumber") String cardNumber) {
        MonthYearCardNumberCard req = new MonthYearCardNumberCard(cardNumber, year);
        return cardTransferAmountByCardService.findYearAmountReceiver(req);
    }

    @Query
    @Description("Get monthly withdraw amount stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<CardResponseMonthAmount>>> getMonthlyWithdrawAmountByCard(
            @Name("year") Long year,
            @Name("cardNumber") String cardNumber) {
        MonthYearCardNumberCard req = new MonthYearCardNumberCard(cardNumber, year);
        return cardWithdrawAmountByCardService.findMonthAmountByCard(req);
    }

    @Query
    @Description("Get yearly withdraw amount stats by card")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<CardResponseYearAmount>>> getYearlyWithdrawAmountByCard(
            @Name("year") Long year,
            @Name("cardNumber") String cardNumber) {
        MonthYearCardNumberCard req = new MonthYearCardNumberCard(cardNumber, year);
        return cardWithdrawAmountByCardService.findYearAmountByCard(req);
    }

    @Query
    @Description("Get card dashboard stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<CardDashboard>> findCardDashboard() {
        return cardDashboardService.dashboard();
    }

    @Query
    @Description("Get card dashboard stats by card number")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<CardDashboardCard>> findCardDashboardByCardNumber(@Name("cardNumber") String cardNumber) {
        return cardDashboardService.dashboardByCard(cardNumber);
    }
}
