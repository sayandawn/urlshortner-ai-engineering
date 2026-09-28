package com.demo.urlshortner.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;

import java.util.stream.Collectors;

/**
 * Maps all API errors to a consistent RFC 9457 problem detail body:
 * {@code {"type", "title", "status", "detail", "instance"}}.
 *
 * <ul>
 *   <li>400 – invalid URL, malformed request, invalid expiration</li>
 *   <li>404 – unknown short code</li>
 *   <li>410 – short code exists but has expired</li>
 *   <li>429 – service-level rate limit exceeded (with {@code Retry-After})</li>
 *   <li>500 – short-code generation exhausted, or any unexpected failure</li>
 * </ul>
 * 409 is not used: clients cannot choose short codes, so there is no client-visible conflict;
 * internal short-code collisions are retried by {@code ShortenerService}.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String EXPIRES_AT_FIELD = "expiresAt";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", detail);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        if (ex.getCause() instanceof JacksonException jackson && isField(jackson, EXPIRES_AT_FIELD)) {
            return problem(HttpStatus.BAD_REQUEST, "Invalid request",
                    EXPIRES_AT_FIELD + ": must be an ISO-8601 timestamp with time zone, e.g. 2026-10-01T12:00:00Z");
        }
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", "Request body is missing or malformed");
    }

    @ExceptionHandler(InvalidUrlException.class)
    public ProblemDetail handleInvalidUrl(InvalidUrlException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid URL", ex.getMessage());
    }

    @ExceptionHandler(ShortCodeNotFoundException.class)
    public ProblemDetail handleNotFound(ShortCodeNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Short code not found", ex.getMessage());
    }

    @ExceptionHandler(ShortCodeExpiredException.class)
    public ProblemDetail handleExpired(ShortCodeExpiredException ex) {
        return problem(HttpStatus.GONE, "Short code expired", ex.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Not found", "No resource found at /" + ex.getResourcePath());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimited(RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(problem(HttpStatus.TOO_MANY_REQUESTS, "Too many requests", ex.getMessage()));
    }

    @ExceptionHandler(ShortCodeGenerationException.class)
    public ProblemDetail handleGenerationFailure(ShortCodeGenerationException ex) {
        log.error("Short code generation exhausted", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Short code generation failed", ex.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            // Spring MVC errors (405, 415, ...) keep their own status in the same problem format.
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .headers(errorResponse.getHeaders())
                    .body(errorResponse.getBody());
        }
        log.error("Unexpected error", ex);
        return ResponseEntity.internalServerError()
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", "An unexpected error occurred"));
    }

    private static boolean isField(JacksonException ex, String field) {
        return ex.getPath().stream().anyMatch(reference -> field.equals(reference.getPropertyName()));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
