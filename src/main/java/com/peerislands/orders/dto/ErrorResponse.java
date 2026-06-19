package com.peerislands.orders.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One error shape for the whole API. {@code fieldErrors} is only populated for
 * validation failures.
 */
public record ErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldError> fieldErrors
) {
    public record FieldError(String field, String message) {
    }

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(OffsetDateTime.now(), status, error, message, path, null);
    }

    public static ErrorResponse of(int status, String error, String message, String path, List<FieldError> fieldErrors) {
        return new ErrorResponse(OffsetDateTime.now(), status, error, message, path, fieldErrors);
    }
}
