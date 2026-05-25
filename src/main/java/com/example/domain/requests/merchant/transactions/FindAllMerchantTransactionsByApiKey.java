package com.example.domain.requests.merchant.transactions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import lombok.Data;

@Data
public class FindAllMerchantTransactionsByApiKey {
    @QueryParam("apiKey")
    @NotBlank(message = "api_key wajib diisi")
    private String apiKey;

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
