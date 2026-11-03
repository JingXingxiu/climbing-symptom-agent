package org.javaup.base.api.problem;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.ConstraintViolationException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

/** Maps base-service API failures to {@code application/problem+json}. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public final class ApiProblemAdvice {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException exception) {
        return response(exception.status(), exception.code(), exception.getMessage(), List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleInvalidBody(MethodArgumentNotValidException exception) {
        List<FieldViolation> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldViolation)
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One or more fields are invalid.", fieldErrors);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleInvalidParameter(ConstraintViolationException exception) {
        List<FieldViolation> fieldErrors = exception.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()))
                .toList();
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "One or more parameters are invalid.", fieldErrors);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleHandlerMethodValidation(HandlerMethodValidationException exception) {
        return response(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "One or more parameters are invalid.",
                List.of()
        );
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return response(
                HttpStatus.BAD_REQUEST,
                "INVALID_PARAMETER_TYPE",
                "Parameter '%s' has an invalid value.".formatted(exception.getName()),
                List.of(new FieldViolation(exception.getName(), "has an invalid value"))
        );
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return response(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Request body is malformed.", List.of());
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ProblemDetail> handleMissingRequestPart(ServletRequestBindingException exception) {
        return response(HttpStatus.BAD_REQUEST, "MISSING_REQUEST_VALUE", exception.getMessage(), List.of());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ProblemDetail> handleMissingMultipartPart(MissingServletRequestPartException exception) {
        return response(
                HttpStatus.BAD_REQUEST,
                "MISSING_REQUEST_VALUE",
                "Required multipart part '%s' is missing.".formatted(exception.getRequestPartName()),
                List.of(new FieldViolation(exception.getRequestPartName(), "is required"))
        );
    }

    private FieldViolation toFieldViolation(FieldError error) {
        return new FieldViolation(error.getField(), error.getDefaultMessage());
    }

    private ResponseEntity<ProblemDetail> response(
            HttpStatusCode status,
            String code,
            String detail,
            List<FieldViolation> fieldErrors
    ) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status instanceof HttpStatus httpStatus ? httpStatus.getReasonPhrase() : "Request failed");
        problem.setType(URI.create("/problems/" + code.toLowerCase()));
        problem.setProperty("code", code);
        problem.setProperty("traceId", UUID.randomUUID().toString());
        if (!fieldErrors.isEmpty()) {
            problem.setProperty("fieldErrors", fieldErrors);
        }
        return ResponseEntity.status(status.value())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private record FieldViolation(String field, String message) {
    }
}
