package com.example.graphql;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

import com.example.domain.requests.saldo.CreateSaldoRequest;
import com.example.domain.requests.saldo.FindAllSaldos;
import com.example.domain.requests.saldo.MonthTotalSaldoBalance;
import com.example.domain.requests.saldo.UpdateSaldoRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.saldo.SaldoResponse;
import com.example.domain.responses.saldo.SaldoResponseDeleteAt;
import com.example.domain.responses.saldo.stats.balance.SaldoMonthBalanceResponse;
import com.example.domain.responses.saldo.stats.balance.SaldoYearBalanceResponse;
import com.example.domain.responses.saldo.stats.total_balance.SaldoMonthTotalBalanceResponse;
import com.example.domain.responses.saldo.stats.total_balance.SaldoYearTotalBalanceResponse;
import com.example.service.saldo.SaldoCommandService;
import com.example.service.saldo.SaldoQueryService;
import com.example.service.saldo.stats.SaldoBalanceService;
import com.example.service.saldo.stats.SaldoTotalAmountService;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

@GraphQLApi
public class SaldoGraphQL {

    @Inject
    SaldoQueryService saldoQueryService;

    @Inject
    SaldoCommandService saldoCommandService;

    @Inject
    SaldoBalanceService saldoBalanceService;

    @Inject
    SaldoTotalAmountService saldoTotalAmountService;

    @Query
    @Description("Find all saldos paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<SaldoResponse>>> findAllSaldos(@Name("req") FindAllSaldos req) {
        return saldoQueryService.findAll(req);
    }

    @Query
    @Description("Find active saldos paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<SaldoResponseDeleteAt>>> findActiveSaldos(@Name("req") FindAllSaldos req) {
        return saldoQueryService.findActive(req);
    }

    @Query
    @Description("Find trashed saldos paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<SaldoResponseDeleteAt>>> findTrashedSaldos(@Name("req") FindAllSaldos req) {
        return saldoQueryService.findTrashed(req);
    }

    @Query
    @Description("Find saldo by ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<SaldoResponse>> findSaldoById(@Name("id") Long id) {
        return saldoQueryService.findById(id);
    }

    @Query
    @Description("Find saldo by card number")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<SaldoResponse>> findSaldoByCard(@Name("cardNumber") String cardNumber) {
        return saldoQueryService.findByCard(cardNumber);
    }

    @Mutation
    @Description("Create a new saldo - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<SaldoResponse>> createSaldo(@Name("req") CreateSaldoRequest req) {
        return saldoCommandService.create(req);
    }

    @Mutation
    @Description("Update an existing saldo - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<SaldoResponse>> updateSaldo(@Name("req") UpdateSaldoRequest req) {
        return saldoCommandService.update(req);
    }

    @Mutation
    @Description("Trash a saldo by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<SaldoResponseDeleteAt>> trashSaldo(@Name("id") Long id) {
        return saldoCommandService.trash(id);
    }

    @Mutation
    @Description("Restore a trashed saldo by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<SaldoResponseDeleteAt>> restoreSaldo(@Name("id") Long id) {
        return saldoCommandService.restore(id);
    }

    @Mutation
    @Description("Permanently delete a saldo by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> deleteSaldo(@Name("id") Long id) {
        return saldoCommandService.delete(id);
    }

    @Mutation
    @Description("Restore all trashed saldos - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> restoreAllSaldos() {
        return saldoCommandService.restoreAll();
    }

    @Mutation
    @Description("Permanently delete all trashed saldos - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> deleteAllSaldos() {
        return saldoCommandService.deleteAll();
    }

    @Query
    @Description("Get monthly saldo balance stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<SaldoMonthBalanceResponse>>> getMonthBalance(@Name("year") Long year) {
        return saldoBalanceService.getMonthBalance(year);
    }

    @Query
    @Description("Get yearly saldo balance stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<SaldoYearBalanceResponse>>> getYearBalance(@Name("year") Long year) {
        return saldoBalanceService.getYearBalance(year);
    }

    @Query
    @Description("Get monthly total saldo balance stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<SaldoMonthTotalBalanceResponse>>> getMonthTotalBalance(
            @Name("req") MonthTotalSaldoBalance req) {
        return saldoTotalAmountService.findMonthTotalBalance(req);
    }

    @Query
    @Description("Get yearly total saldo balance stats")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<SaldoYearTotalBalanceResponse>>> getYearTotalBalance(@Name("year") Long year) {
        return saldoTotalAmountService.findYearTotalBalance(year);
    }
}
