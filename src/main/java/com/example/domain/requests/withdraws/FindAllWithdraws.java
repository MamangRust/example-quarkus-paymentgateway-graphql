package com.example.domain.requests.withdraws;

import jakarta.validation.constraints.Min;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.QueryParam;
import lombok.Data;

@Data
public class FindAllWithdraws {
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
