package com.example.orders.adapter.rest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;
import java.util.Set;

@RestControllerAdvice
public class RestExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(InvalidCalculationRequestException.class)
    ProblemDetail invalidRequest(InvalidCalculationRequestException exception) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                "Request body must be valid JSON with the required field types");
        if (exception.getCause() instanceof JacksonException jackson) {
            StringBuilder path = new StringBuilder();
            for (var reference : jackson.getPath()) {
                String field = reference.getPropertyName();
                if (field != null && Set.of("currency", "items", "price", "quantity", "discountPercent").contains(field)) {
                    if (!path.isEmpty()) {
                        path.append('.');
                    }
                    path.append(field);
                } else if (reference.getIndex() >= 0) {
                    path.append('[').append(reference.getIndex()).append(']');
                }
            }
            if (!path.isEmpty()) {
                problem.setDetail(path + " must have the required JSON type");
            }
        }
        return handleExceptionInternal(exception, problem, headers, status, request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpectedFailure(Exception exception) {
        logger.error("Unexpected order calculation failure", exception);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred");
    }
}
