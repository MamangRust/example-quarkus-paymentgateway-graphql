package com.example.exception.mapper;

import com.example.domain.responses.api.ApiResponse;
import com.example.exception.InvalidRequestException;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

@Provider
public class InvalidRequestExceptionMapper implements ExceptionMapper<InvalidRequestException> {

    @Override
    public Response toResponse(InvalidRequestException exception) {
        ApiResponse<Void> errorResponse = ApiResponse.error(exception.getMessage());
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(errorResponse)
                .type(MediaType.APPLICATION_JSON)
                .build();
    }
}
