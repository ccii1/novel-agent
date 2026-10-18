package dev.novel.controller;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import dev.novel.integration.model.ModelServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger log=LoggerFactory.getLogger(ApiErrors.class);
    @ExceptionHandler(ModelServiceException.class)
    public ResponseEntity<?> modelService(ModelServiceException error) {
        var status=switch(error.reason()) {
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case INTERRUPTED -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_GATEWAY;
        };
        log.warn("event=MODEL_LIST_FAILED provider={} reason={} message={}",error.provider(),error.reason(),error.getMessage());
        return ResponseEntity.status(status).body(Map.of("error",error.getMessage(),"provider",error.provider(),"errorCode","MODEL_SERVICE_"+error.reason().name()));
    }
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> missing(NoSuchElementException e) { return ResponseEntity.status(404).body(Map.of("error",e.getMessage())); }
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class})
    public ResponseEntity<?> badRequest(Exception e) { return ResponseEntity.badRequest().body(Map.of("error",Objects.toString(e.getMessage(),"请求无效"))); }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("error",Objects.toString(e.getMessage(),"状态冲突"))); }
}
