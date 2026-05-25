package com.example.graphql;

import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.ForbiddenException;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.logging.Logger;

@ApplicationScoped
public class GraphQLExceptionHandler {

    private static final Logger LOG = Logger.getLogger(GraphQLExceptionHandler.class.getName());

    public void logException(Throwable t) {
        if (t instanceof UnauthorizedException) {
            LOG.warning("Unauthorized access attempt: " + t.getMessage());
        } else if (t instanceof ForbiddenException) {
            LOG.warning("Forbidden access attempt: " + t.getMessage());
        } else {
            LOG.severe("GraphQL error: " + t.getMessage());
        }
    }
}
