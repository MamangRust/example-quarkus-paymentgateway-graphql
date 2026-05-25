package com.example.graphql;

import java.util.List;

import com.example.domain.requests.auth.RegisterRequest;
import com.example.domain.requests.user.FindAllUsers;
import com.example.domain.requests.user.UpdateUserRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.user.UserResponse;
import com.example.domain.responses.user.UserResponseDeleteAt;
import com.example.service.user.UserCommandService;
import com.example.service.user.UserQueryService;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.*;

@GraphQLApi
public class UserGraphQL {

    @Inject
    UserQueryService userQueryService;

    @Inject
    UserCommandService userCommandService;

    @Query
    @Description("Find all users paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_MODERATOR" })
    public Uni<ApiResponsePagination<List<UserResponse>>> findAllUsersPaginated(@Name("request") FindAllUsers request) {
        return userQueryService.findAllPaginated(request);
    }

    @Query
    @Description("Find active users paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_MODERATOR" })
    public Uni<ApiResponsePagination<List<UserResponseDeleteAt>>> findActiveUsersPaginated(
            @Name("request") FindAllUsers request) {
        return userQueryService.findActivePaginated(request);
    }

    @Query
    @Description("Find trashed users paginated")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_MODERATOR" })
    public Uni<ApiResponsePagination<List<UserResponseDeleteAt>>> findTrashedUsersPaginated(
            @Name("request") FindAllUsers request) {
        return userQueryService.findTrashedPaginated(request);
    }

    @Query
    @Description("Find user by ID")
    @RolesAllowed({ "ROLE_ADMIN", "ROLE_MODERATOR" })
    public Uni<ApiResponse<UserResponse>> findUserById(@Name("id") Long id) {
        return userQueryService.findById(id);
    }

    @Mutation
    @Description("Create a new user")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<UserResponse>> createUser(@Name("request") RegisterRequest request) {
        return userCommandService.createUser(request);
    }

    @Mutation
    @Description("Update an existing user")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<UserResponse>> updateUser(@Name("id") Long id, @Name("request") UpdateUserRequest request) {
        request.setId(id.intValue());
        return userCommandService.updateUser(request);
    }

    @Mutation
    @Description("Trash a user by ID")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<UserResponseDeleteAt>> trashUser(@Name("id") Long id) {
        return userCommandService.trashed(id);
    }

    @Mutation
    @Description("Restore a trashed user by ID")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<UserResponseDeleteAt>> restoreUser(@Name("id") Long id) {
        return userCommandService.restore(id);
    }

    @Mutation
    @Description("Permanently delete a user by ID")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Void>> deleteUserPermanent(@Name("id") Long id) {
        return userCommandService.deletePermanent(id);
    }

    @Mutation
    @Description("Restore all trashed users")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Void>> restoreAllTrashedUsers() {
        return userCommandService.restoreAllTrashedUsers();
    }

    @Mutation
    @Description("Permanently delete all trashed users")
    @RolesAllowed("ROLE_ADMIN")
    public Uni<ApiResponse<Void>> deleteAllTrashedUsers() {
        return userCommandService.deleteAllTrashedUsers();
    }
}
