package com.example.service.user;

import java.util.List;

import com.example.domain.requests.user.FindAllUsers;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.api.ApiResponsePagination;
import com.example.domain.responses.user.UserResponse;
import com.example.domain.responses.user.UserResponseDeleteAt;

import io.smallrye.mutiny.Uni;

public interface UserQueryService {
    Uni<ApiResponsePagination<List<UserResponse>>> findAllPaginated(FindAllUsers request);

    Uni<ApiResponsePagination<List<UserResponseDeleteAt>>> findActivePaginated(FindAllUsers request);

    Uni<ApiResponsePagination<List<UserResponseDeleteAt>>> findTrashedPaginated(FindAllUsers request);

    Uni<ApiResponse<UserResponse>> findById(Long id);
}
