package dev.novel.controller;

import dev.novel.service.ObservationService;
import dev.novel.dto.ObservationFeedback;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/observability")
public class ObservationController {
    private final ObservationService observations;
    public ObservationController(ObservationService observations) { this.observations=observations; }
    @GetMapping public Object dashboard(@RequestParam(required=false) String novelId,
            @RequestParam(required=false) Integer chapter,@RequestParam(required=false) String kind,
            @RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page) {
        return observations.dashboard(novelId,chapter,kind,status,page);
    }
    @GetMapping("/tasks/{id}") public Object detail(@PathVariable String id) { return observations.detail(id); }
    @PostMapping("/tasks/{id}/feedback") public Object feedback(@PathVariable String id,@Valid @RequestBody ObservationFeedback request) {
        observations.feedback(id,request.rating(),request.note());return Map.of("ok",true);
    }
}
