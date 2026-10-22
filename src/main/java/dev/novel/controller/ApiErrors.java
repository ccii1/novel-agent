package dev.novel.controller;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import dev.novel.integration.model.ModelServiceException;
import dev.novel.dto.response.CommonResponses.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ModelServiceException.class)
    public ResponseEntity<ErrorResponse> modelService(ModelServiceException error) {
        var status=switch(error.reason()) {
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case INTERRUPTED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        log.warn("event=MODEL_LIST_FAILED provider={} reason={} message={}",error.provider(),error.reason(),error.getMessage());
        return ResponseEntity.status(status).body(new ErrorResponse(
                error.getMessage(), error.provider(), "MODEL_SERVICE_" + error.reason().name()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> missing(NoSuchElementException error) {
        return ResponseEntity.status(404).body(new ErrorResponse(error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(new ErrorResponse(Objects.toString(error.getMessage(), "请求无效")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> invalidFields(MethodArgumentNotValidException error) {
        String message = error.getBindingResult().getFieldErrors().stream()
                .map(field -> field.getField() + ": " + field.getDefaultMessage())
                .distinct().collect(java.util.stream.Collectors.joining("；"));
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> conflict(IllegalStateException error) {
        return ResponseEntity.status(409).body(new ErrorResponse(Objects.toString(error.getMessage(), "状态冲突")));
    }
}
