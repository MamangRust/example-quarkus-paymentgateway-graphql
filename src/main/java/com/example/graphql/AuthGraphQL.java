package com.example.graphql;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

import com.example.domain.requests.auth.LoginRequest;
import com.example.domain.requests.auth.RefreshTokenRequest;
import com.example.domain.requests.auth.RegisterRequest;
import com.example.domain.responses.TokenResponse;
import com.example.domain.responses.api.ApiResponse;
import com.example.domain.responses.user.UserResponse;
import com.example.service.AuthService;

import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;

@GraphQLApi
public class AuthGraphQL {

    @Inject
    AuthService authService;

    @Mutation
    @Description("Login with username/email and password")
    @PermitAll
    public Uni<ApiResponse<TokenResponse>> login(@Name("request") LoginRequest request) {
        return authService.authenticate(request);
    }

    @Mutation
    @Description("Register a new user account")
    @PermitAll
    public Uni<ApiResponse<UserResponse>> register(@Name("request") RegisterRequest request) {
        return authService.registerUser(request);
    }

    @Mutation
    @Description("Refresh access token using a valid refresh token")
    @PermitAll
    public Uni<ApiResponse<TokenResponse>> refreshToken(@Name("request") RefreshTokenRequest request) {
        return authService.refreshToken(request.getRefreshToken());
    }

    @Query
    @Description("Get current authenticated user")
    public Uni<ApiResponse<UserResponse>> me() {
        return authService.getCurrentUser();
    }
}
