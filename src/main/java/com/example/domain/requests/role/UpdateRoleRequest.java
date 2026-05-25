package com.example.domain.requests.role;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UpdateRoleRequest {
    @Min(value = 1, message = "ID role minimal 1")
    private Integer roleId;

    @NotBlank(message = "Nama role wajib diisi")
    private String name;
}
