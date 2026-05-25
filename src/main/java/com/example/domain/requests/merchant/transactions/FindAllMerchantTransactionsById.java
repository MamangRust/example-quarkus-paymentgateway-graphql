package com.example.domain.requests.merchant.transactions;

import jakarta.validation.constraints.Min;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import lombok.Data;

@Data
public class FindAllMerchantTransactionsById {
    @QueryParam("merchantId")
    @Min(value = 1, message = "merchant_id minimal 1")
    private Long merchantId;

    @QueryParam("page")
    @DefaultValue("1")
    @Min(value = 1)
    private Integer page = 1;

    @QueryParam("pageSize")
    @DefaultValue("10")
    @Min(value = 1)
    private Integer pageSize = 10;

    @QueryParam("search")
    @DefaultValue("")
    private String search = "";
}
