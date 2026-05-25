package com.example.domain.requests.topup;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import lombok.Data;

@Data
public class FindAllTopupsByCardNumber {

    @QueryParam("cardNumber")
    @NotBlank(message = "Card number wajib diisi")
    private String cardNumber;

    @QueryParam("page")
    @DefaultValue("1")
    @Min(value = 1, message = "Page minimal 1")
    private Integer page = 1;

    @QueryParam("pageSize")
    @DefaultValue("10")
    @Min(value = 1, message = "Page size minimal 1")
    private Integer pageSize = 10;

    @QueryParam("search")
    @DefaultValue("")
    private String search = "";
}
