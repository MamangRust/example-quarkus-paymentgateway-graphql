package com.example.graphql;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

import com.example.domain.requests.role.CreateRoleRequest;
import com.example.domain.requests.role.FindAllRoles;
import com.example.domain.requests.role.UpdateRoleRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.role.RoleResponse;
import com.example.domain.responses.role.RoleResponseDeleteAt;
import com.example.service.role.RoleCommandService;
import com.example.service.role.RoleQueryService;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;

@GraphQLApi
public class RoleGraphQL {

    @Inject
    RoleQueryService roleQueryService;

    @Inject
    RoleCommandService roleCommandService;

    @Query
    @Description("Find all roles paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_MODERATOR" })
    public Uni<ApiResponsePagination<List<RoleResponse>>> findAllRolesPaginated(@Name("request") FindAllRoles request) {
        return roleQueryService.findAllPaginated(request);
    }

    @Query
    @Description("Find active roles paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_MODERATOR" })
    public Uni<ApiResponsePagination<List<RoleResponseDeleteAt>>> findActiveRolesPaginated(
            @Name("request") FindAllRoles request) {
        return roleQueryService.findActivePaginated(request);
    }

    @Query
    @Description("Find trashed roles paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_MODERATOR" })
    public Uni<ApiResponsePagination<List<RoleResponseDeleteAt>>> findTrashedRolesPaginated(
            @Name("request") FindAllRoles request) {
        return roleQueryService.findTrashedPaginated(request);
    }

    @Query
    @Description("Find role by ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_MODERATOR" })
    public Uni<ApiResponse<RoleResponse>> findRoleById(@Name("id") Long id) {
        return roleQueryService.findById(id);
    }

    @Mutation
    @Description("Create a new role")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<RoleResponse>> createRole(@Name("request") CreateRoleRequest request) {
        return roleCommandService.create(request);
    }

    @Mutation
    @Description("Update an existing role")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<RoleResponse>> updateRole(@Name("id") Long id, @Name("request") UpdateRoleRequest request) {
        request.setRoleId(id.intValue());
        return roleCommandService.update(request);
    }

    @Mutation
    @Description("Trash a role by ID")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<RoleResponseDeleteAt>> trashRole(@Name("id") Long id) {
        return roleCommandService.trash(id);
    }

    @Mutation
    @Description("Restore a trashed role by ID")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<RoleResponseDeleteAt>> restoreRole(@Name("id") Long id) {
        return roleCommandService.restore(id);
    }

    @Mutation
    @Description("Permanently delete a role by ID")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Void>> deleteRolePermanent(@Name("id") Long id) {
        return roleCommandService.deletePermanent(id);
    }

    @Mutation
    @Description("Restore all trashed roles")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Void>> restoreAllTrashedRoles() {
        return roleCommandService.restoreAllTrashedRoles();
    }

    @Mutation
    @Description("Permanently delete all trashed roles")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Void>> deleteAllTrashedRoles() {
        return roleCommandService.deleteAllTrashedRoles();
    }
}
