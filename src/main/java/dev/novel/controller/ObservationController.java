package dev.novel.controller;

import dev.novel.service.ObservationService;
import dev.novel.service.TaskProgressService;
import dev.novel.dto.ObservationFeedback;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/observability")
public class ObservationController {
    private final ObservationService observations;
    private final TaskProgressService progress;
    public ObservationController(ObservationService observations,TaskProgressService progress) { this.observations=observations;this.progress=progress; }
    @GetMapping public Object dashboard(@RequestParam(required=false) String novelId,
            @RequestParam(required=false) Integer chapter,@RequestParam(required=false) String kind,
            @RequestParam(required=false) String status,@RequestParam(defaultValue="0") int page) {
        return observations.dashboard(novelId,chapter,kind,status,page);
    }
    @GetMapping("/tasks/{id}") public Object detail(@PathVariable String id) { return observations.detail(id); }
    @GetMapping("/tasks/{id}/progress") public Object progress(@PathVariable String id) { return progress.progress(id); }
    @PostMapping("/tasks/{id}/feedback") public Object feedback(@PathVariable String id,@Valid @RequestBody ObservationFeedback request) {
        observations.feedback(id,request.rating(),request.note());return Map.of("ok",true);
    }
}
