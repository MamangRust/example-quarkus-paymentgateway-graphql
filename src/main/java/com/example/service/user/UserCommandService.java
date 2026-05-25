package com.example.service.user;

import com.example.domain.requests.auth.RegisterRequest;
import com.example.domain.requests.user.UpdateUserRequest;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.user.UserResponse;
import com.example.domain.responses.user.UserResponseDeleteAt;

import io.smallrye.mutiny.Uni;
import jakarta.validation.Valid;

public interface UserCommandService {
    Uni<ApiResponse<UserResponse>> createUser(RegisterRequest request);

    Uni<ApiResponse<UserResponse>> updateUser(@Valid UpdateUserRequest request);

    Uni<ApiResponse<UserResponseDeleteAt>> trashed(Long id);

    Uni<ApiResponse<UserResponseDeleteAt>> restore(Long id);

    Uni<ApiResponse<Void>> deletePermanent(Long id);

    Uni<ApiResponse<Void>> restoreAllTrashedUsers();

    Uni<ApiResponse<Void>> deleteAllTrashedUsers();
}
