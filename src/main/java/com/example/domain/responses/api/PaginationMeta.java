package com.example.domain.responses.api;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public record PaginationMeta(
                int currentPage,
                int pageSize,
                int totalPages,
                int totalRecords) {
}
