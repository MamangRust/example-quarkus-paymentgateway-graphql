package com.example.graphql;

import java.util.List;

import com.example.domain.requests.merchant.CreateMerchantRequest;
import com.example.domain.requests.merchant.FindAllMerchants;
import com.example.domain.requests.merchant.UpdateMerchantRequest;
import com.example.domain.requests.merchant.statsbyapikey.MonthYearAmountApiKey;
import com.example.domain.requests.merchant.statsbyapikey.MonthYearPaymentMethodApiKey;
import com.example.domain.requests.merchant.statsbyapikey.MonthYearTotalAmountApiKey;
import com.example.domain.requests.merchant.statsbyid.MonthYearAmountMerchant;
import com.example.domain.requests.merchant.statsbyid.MonthYearPaymentMethodMerchant;
import com.example.domain.requests.merchant.statsbyid.MonthYearTotalAmountMerchant;
import com.example.domain.requests.merchant.transactions.FindAllMerchantTransactionsByApiKey;
import com.example.domain.requests.merchant.transactions.FindAllMerchantTransactionsById;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.merchant.MerchantResponse;
import com.example.domain.responses.merchant.MerchantResponseDeleteAt;
import com.example.domain.responses.merchant.MerchantTransactionResponse;
import com.example.domain.responses.merchant.stats.amount.MerchantResponseMonthlyAmount;
import com.example.domain.responses.merchant.stats.amount.MerchantResponseYearlyAmount;
import com.example.domain.responses.merchant.stats.method.MerchantResponseMonthlyPaymentMethod;
import com.example.domain.responses.merchant.stats.method.MerchantResponseYearlyPaymentMethod;
import com.example.domain.responses.merchant.stats.total_amount.MerchantResponseMonthlyTotalAmount;
import com.example.domain.responses.merchant.stats.total_amount.MerchantResponseYearlyTotalAmount;
import com.example.service.merchant.MerchantCommandService;
import com.example.service.merchant.MerchantQueryService;
import com.example.service.merchant.MerchantTransactionService;
import com.example.service.merchant.stats.amount.MerchantAmountByApiKeyService;
import com.example.service.merchant.stats.amount.MerchantAmountByIdService;
import com.example.service.merchant.stats.amount.MerchantAmountService;
import com.example.service.merchant.stats.method.MerchantMethodByApiKeyService;
import com.example.service.merchant.stats.method.MerchantMethodByIdService;
import com.example.service.merchant.stats.method.MerchantMethodService;
import com.example.service.merchant.stats.totalamount.MerchantTotalAmountByApiKeyService;
import com.example.service.merchant.stats.totalamount.MerchantTotalAmountByIdService;
import com.example.service.merchant.stats.totalamount.MerchantTotalAmountService;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;

@GraphQLApi
public class MerchantGraphQL {

    @Inject
    MerchantQueryService merchantQueryService;

    @Inject
    MerchantCommandService merchantCommandService;

    @Inject
    MerchantTransactionService merchantTransactionService;

    @Inject
    MerchantAmountService merchantAmountService;

    @Inject
    MerchantMethodService merchantMethodService;

    @Inject
    MerchantTotalAmountService merchantTotalAmountService;

    @Inject
    MerchantAmountByIdService merchantAmountByIdService;

    @Inject
    MerchantMethodByIdService merchantMethodByIdService;

    @Inject
    MerchantTotalAmountByIdService merchantTotalAmountByIdService;

    @Inject
    MerchantAmountByApiKeyService merchantAmountByApiKeyService;

    @Inject
    MerchantMethodByApiKeyService merchantMethodByApiKeyService;

    @Inject
    MerchantTotalAmountByApiKeyService merchantTotalAmountByApiKeyService;

    @Query
    @Description("Find all merchants paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<MerchantResponse>>> findAllMerchants(@Name("req") FindAllMerchants req) {
        return merchantQueryService.findAll(req);
    }

    @Query
    @Description("Find active merchants paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<MerchantResponseDeleteAt>>> findActiveMerchants(
            @Name("req") FindAllMerchants req) {
        return merchantQueryService.findByActive(req);
    }

    @Query
    @Description("Find trashed merchants paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<MerchantResponseDeleteAt>>> findTrashedMerchants(
            @Name("req") FindAllMerchants req) {
        return merchantQueryService.findByTrashed(req);
    }

    @Query
    @Description("Find merchant by ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<MerchantResponse>> findMerchantById(@Name("id") Long id) {
        return merchantQueryService.findById(id);
    }

    @Query
    @Description("Find merchant by API key")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<MerchantResponse>> findMerchantByApiKey(@Name("apiKey") String apiKey) {
        return merchantQueryService.findByApiKey(apiKey);
    }

    @Query
    @Description("Find merchants by User ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF", "ROLE_USER" })
    public Uni<ApiResponse<List<MerchantResponse>>> findMerchantByUserId(@Name("userId") Long userId) {
        return merchantQueryService.findByUserId(userId);
    }

    @Mutation
    @Description("Create a new merchant - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<MerchantResponse>> createMerchant(@Name("req") CreateMerchantRequest req) {
        return merchantCommandService.createMerchant(req);
    }

    @Mutation
    @Description("Update an existing merchant - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<MerchantResponse>> updateMerchant(@Name("req") UpdateMerchantRequest req) {
        return merchantCommandService.updateMerchant(req);
    }

    @Mutation
    @Description("Trash a merchant by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<MerchantResponseDeleteAt>> trashMerchant(@Name("id") Long id) {
        return merchantCommandService.trashMerchant(id);
    }

    @Mutation
    @Description("Restore a trashed merchant by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<MerchantResponseDeleteAt>> restoreMerchant(@Name("id") Long id) {
        return merchantCommandService.restoreMerchant(id);
    }

    @Mutation
    @Description("Permanently delete a merchant by ID - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> deleteMerchant(@Name("id") Long id) {
        return merchantCommandService.deleteMerchant(id);
    }

    @Mutation
    @Description("Restore all trashed merchants - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> restoreAllMerchants() {
        return merchantCommandService.restoreAll();
    }

    @Mutation
    @Description("Permanently delete all trashed merchants - ADMIN only")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Boolean>> deleteAllMerchants() {
        return merchantCommandService.deleteAll();
    }

    @Query
    @Description("Find all merchant transactions paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<MerchantTransactionResponse>>> findAllTransactions(
            @Name("req") FindAllMerchants req) {
        return merchantTransactionService.findAll(req);
    }

    @Query
    @Description("Find merchant transactions by merchant ID paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<MerchantTransactionResponse>>> findTransactionsById(
            @Name("req") FindAllMerchantTransactionsById req) {
        return merchantTransactionService.findById(req);
    }

    @Query
    @Description("Find merchant transactions by API key paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponsePagination<List<MerchantTransactionResponse>>> findTransactionsByApiKey(
            @Name("req") FindAllMerchantTransactionsByApiKey req) {
        return merchantTransactionService.findByApiKey(req);
    }

    @Query
    @Description("Get monthly transaction amount stats for all merchants")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseMonthlyAmount>>> getMonthlyAmount(@Name("year") Long year) {
        return merchantAmountService.findMonthAmount(year);
    }

    @Query
    @Description("Get yearly transaction amount stats for all merchants")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseYearlyAmount>>> getYearlyAmount(@Name("year") Long year) {
        return merchantAmountService.findYearAmount(year);
    }

    @Query
    @Description("Get monthly transaction amount stats by merchant ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseMonthlyAmount>>> getMonthlyAmountById(
            @Name("req") MonthYearAmountMerchant req) {
        return merchantAmountByIdService.findMonthAmountById(req);
    }

    @Query
    @Description("Get yearly transaction amount stats by merchant ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseYearlyAmount>>> getYearlyAmountById(
            @Name("req") MonthYearAmountMerchant req) {
        return merchantAmountByIdService.findYearAmountById(req);
    }

    @Query
    @Description("Get monthly transaction amount stats by API key")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseMonthlyAmount>>> getMonthlyAmountByApiKey(
            @Name("req") MonthYearAmountApiKey req) {
        return merchantAmountByApiKeyService.findMonthAmountByApiKey(req);
    }

    @Query
    @Description("Get yearly transaction amount stats by API key")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseYearlyAmount>>> getYearlyAmountByApiKey(
            @Name("req") MonthYearAmountApiKey req) {
        return merchantAmountByApiKeyService.findYearAmountByApiKey(req);
    }

    @Query
    @Description("Get monthly payment method usage stats for all merchants")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseMonthlyPaymentMethod>>> getMonthlyMethod(@Name("year") Long year) {
        return merchantMethodService.findMonthMethod(year);
    }

    @Query
    @Description("Get yearly payment method usage stats for all merchants")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseYearlyPaymentMethod>>> getYearlyMethod(@Name("year") Long year) {
        return merchantMethodService.findYearMethod(year);
    }

    @Query
    @Description("Get monthly payment method usage stats by merchant ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseMonthlyPaymentMethod>>> getMonthlyMethodById(
            @Name("req") MonthYearPaymentMethodMerchant req) {
        return merchantMethodByIdService.findMonthMethodById(req);
    }

    @Query
    @Description("Get yearly payment method usage stats by merchant ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseYearlyPaymentMethod>>> getYearlyMethodById(
            @Name("req") MonthYearPaymentMethodMerchant req) {
        return merchantMethodByIdService.findYearMethodById(req);
    }

    @Query
    @Description("Get monthly payment method usage stats by API key")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseMonthlyPaymentMethod>>> getMonthlyMethodByApiKey(
            @Name("req") MonthYearPaymentMethodApiKey req) {
        return merchantMethodByApiKeyService.findMonthMethod(req);
    }

    @Query
    @Description("Get yearly payment method usage stats by API key")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseYearlyPaymentMethod>>> getYearlyMethodByApiKey(
            @Name("req") MonthYearPaymentMethodApiKey req) {
        return merchantMethodByApiKeyService.findYearMethod(req);
    }

    @Query
    @Description("Get monthly total transaction amount stats for all merchants")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseMonthlyTotalAmount>>> getMonthlyTotalAmount(@Name("year") Long year) {
        return merchantTotalAmountService.findMonthTotalAmount(year);
    }

    @Query
    @Description("Get yearly total transaction amount stats for all merchants")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseYearlyTotalAmount>>> getYearlyTotalAmount(@Name("year") Long year) {
        return merchantTotalAmountService.findYearTotalAmount(year);
    }

    @Query
    @Description("Get monthly total transaction amount stats by merchant ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseMonthlyTotalAmount>>> getMonthlyTotalAmountById(
            @Name("req") MonthYearTotalAmountMerchant req) {
        return merchantTotalAmountByIdService.findMonthTotalAmountById(req);
    }

    @Query
    @Description("Get yearly total transaction amount stats by merchant ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseYearlyTotalAmount>>> getYearlyTotalAmountById(
            @Name("req") MonthYearTotalAmountMerchant req) {
        return merchantTotalAmountByIdService.findYearTotalAmountById(req);
    }

    @Query
    @Description("Get monthly total transaction amount stats by API key")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseMonthlyTotalAmount>>> getMonthlyTotalAmountByApiKey(
            @Name("req") MonthYearTotalAmountApiKey req) {
        return merchantTotalAmountByApiKeyService.findMonthTotalAmountByApiKey(req);
    }

    @Query
    @Description("Get yearly total transaction amount stats by API key")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_STAFF" })
    public Uni<ApiResponse<List<MerchantResponseYearlyTotalAmount>>> getYearlyTotalAmountByApiKey(
            @Name("req") MonthYearTotalAmountApiKey req) {
        return merchantTotalAmountByApiKeyService.findYearTotalAmountByApiKey(req);
    }
}
