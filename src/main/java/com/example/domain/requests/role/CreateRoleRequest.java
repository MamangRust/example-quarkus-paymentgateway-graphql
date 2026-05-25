package com.example.domain.requests.role;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CreateRoleRequest {
    @NotBlank(message = "Name Role wajib di isi")
    private String name;
}
