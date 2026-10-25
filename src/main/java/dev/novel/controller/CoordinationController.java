package dev.novel.controller;

import dev.novel.service.CoordinationService;
import dev.novel.dto.ApiRequests.CoordinationRequest;
import dev.novel.dto.response.CommonResponses.CreatedResponse;
import dev.novel.dto.response.TaskResponses.TaskResponse;
import dev.novel.support.ResponseMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/novels/{id}/coordination")
public class CoordinationController {
    private final CoordinationService coordination;
    private final ResponseMapper responses;

    public CoordinationController(CoordinationService coordination, ResponseMapper responses) {
        this.coordination = coordination;
        this.responses = responses;
    }

    @GetMapping
    public List<TaskResponse> list(@PathVariable String id) {
        return responses.list(coordination.list(id), TaskResponse.class);
    }

    @PostMapping
    public CreatedResponse request(@PathVariable String id, @Valid @RequestBody CoordinationRequest request) {
        return new CreatedResponse(coordination.request(id, request.instructions()));
    }

    @PostMapping("/{taskId}/approve")
    public CreatedResponse approve(@PathVariable String id, @PathVariable String taskId) {
        return new CreatedResponse(coordination.approve(id, taskId));
    }
}
