package dev.novel.controller;

import dev.novel.service.ObservationService;
import dev.novel.service.TaskProgressService;
import dev.novel.dto.ObservationFeedback;
import dev.novel.dto.response.CommonResponses.ActionResponse;
import dev.novel.dto.response.TaskResponses.*;
import dev.novel.support.ResponseMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/observability")
public class ObservationController {
    private final ObservationService observations;
    private final TaskProgressService progress;
    private final ResponseMapper responses;

    public ObservationController(ObservationService observations, TaskProgressService progress, ResponseMapper responses) {
        this.observations = observations;
        this.progress = progress;
        this.responses = responses;
    }

    @GetMapping
    public ObservationDashboardResponse dashboard(@RequestParam(required = false) String novelId,
                                                  @RequestParam(required = false) Integer chapter, @RequestParam(required = false) String kind,
                                                  @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page) {
        return responses.to(observations.dashboard(novelId, chapter, kind, status, page), ObservationDashboardResponse.class);
    }

    @GetMapping("/tasks/{id}")
    public TaskDetailResponse detail(@PathVariable String id) {
        return responses.to(observations.detail(id), TaskDetailResponse.class);
    }

    @GetMapping("/tasks/{id}/progress")
    public TaskProgressResponse progress(@PathVariable String id) {
        return responses.to(progress.progress(id), TaskProgressResponse.class);
    }

    @PostMapping("/tasks/{id}/feedback")
    public ActionResponse feedback(@PathVariable String id, @Valid @RequestBody ObservationFeedback request) {
        observations.feedback(id, request.rating(), request.note());
        return ActionResponse.SUCCESS;
    }
}
