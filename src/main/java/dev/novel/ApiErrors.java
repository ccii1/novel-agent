package dev.novel;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> missing(NoSuchElementException e) { return ResponseEntity.status(404).body(Map.of("error",e.getMessage())); }
    @ExceptionHandler({IllegalArgumentException.class,MethodArgumentNotValidException.class})
    public ResponseEntity<?> badRequest(Exception e) { return ResponseEntity.badRequest().body(Map.of("error",Objects.toString(e.getMessage(),"请求无效"))); }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> conflict(IllegalStateException e) { return ResponseEntity.status(409).body(Map.of("error",Objects.toString(e.getMessage(),"状态冲突"))); }
}
