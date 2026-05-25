package com.example.service.role;

import com.example.domain.requests.role.CreateRoleRequest;
import com.example.domain.requests.role.UpdateRoleRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.role.RoleResponse;
import com.example.domain.responses.role.RoleResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface RoleCommandService {
    Uni<ApiResponse<RoleResponse>> create(CreateRoleRequest request);

    Uni<ApiResponse<RoleResponse>> update(UpdateRoleRequest request);

    Uni<ApiResponse<RoleResponseDeleteAt>> trash(Long id);

    Uni<ApiResponse<RoleResponseDeleteAt>> restore(Long id);

    Uni<ApiResponse<Void>> deletePermanent(Long id);

    Uni<ApiResponse<Void>> restoreAllTrashedRoles();

    Uni<ApiResponse<Void>> deleteAllTrashedRoles();
}
