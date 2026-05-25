package com.example.exception.mapper;

import com.example.domain.responses.api.ApiResponse;
import com.example.exception.ForbiddenException;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ForbiddenExceptionMapper implements ExceptionMapper<ForbiddenException> {

    @Override
    public Response toResponse(ForbiddenException exception) {
        ApiResponse<Void> errorResponse = ApiResponse.error(exception.getMessage());
        return Response.status(Response.Status.FORBIDDEN)
                .entity(errorResponse)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
