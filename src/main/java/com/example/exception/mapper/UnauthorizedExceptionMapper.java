package com.example.exception.mapper;

import com.example.domain.responses.api.ApiResponse;
import com.example.exception.UnauthorizedException;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class UnauthorizedExceptionMapper implements ExceptionMapper<UnauthorizedException> {

    @Override
    public Response toResponse(UnauthorizedException exception) {
        ApiResponse<Void> errorResponse = ApiResponse.error(exception.getMessage());
        return Response.status(Response.Status.UNAUTHORIZED)
                .entity(errorResponse)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
