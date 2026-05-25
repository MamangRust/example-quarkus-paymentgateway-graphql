package com.example.exception.mapper;

import com.example.domain.responses.api.ApiResponse;
import com.example.exception.ResourceAlreadyExistsException;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class ResourceAlreadyExistsExceptionMapper implements ExceptionMapper<ResourceAlreadyExistsException> {

    @Override
    public Response toResponse(ResourceAlreadyExistsException exception) {
        ApiResponse<Void> errorResponse = ApiResponse.error(exception.getMessage());
        return Response.status(Response.Status.CONFLICT)
                .entity(errorResponse)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
