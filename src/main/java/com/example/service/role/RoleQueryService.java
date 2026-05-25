package com.example.service.role;

import java.util.List;

import com.example.domain.requests.role.FindAllRoles;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.role.RoleResponse;
import com.example.domain.responses.role.RoleResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface RoleQueryService {
    Uni<ApiResponsePagination<List<RoleResponse>>> findAllPaginated(FindAllRoles request);

    Uni<ApiResponsePagination<List<RoleResponseDeleteAt>>> findActivePaginated(FindAllRoles request);

    Uni<ApiResponsePagination<List<RoleResponseDeleteAt>>> findTrashedPaginated(FindAllRoles request);

    Uni<ApiResponse<RoleResponse>> findById(Long id);
}
