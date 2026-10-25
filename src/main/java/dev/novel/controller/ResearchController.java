package dev.novel.controller;

import dev.novel.dto.ApiRequests.ResearchRequest;
import dev.novel.dto.ApiRequests.ResearchMessage;
import dev.novel.service.ResearchService;
import dev.novel.service.WorkspaceService;
import dev.novel.dto.response.CommonResponses.CreatedResponse;
import dev.novel.dto.response.ResearchResponses.*;
import dev.novel.dto.response.TaskResponses.TaskResponse;
import dev.novel.support.ResponseMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/research")
public class ResearchController {
    private final ResearchService research;
    private final WorkspaceService workspace;
    private final ResponseMapper responses;

    public ResearchController(ResearchService research, WorkspaceService workspace, ResponseMapper responses) {
        this.research = research;
        this.workspace = workspace;
        this.responses = responses;
    }

    @GetMapping
    public List<ResearchReportResponse> reports() {
        return responses.list(workspace.researchReports(), ResearchReportResponse.class);
    }

    @GetMapping("/tasks")
    public List<TaskResponse> tasks() {
        return responses.list(workspace.researchTasks(), TaskResponse.class);
    }

    @PostMapping
    public CreatedResponse create(@Valid @RequestBody ResearchRequest request) {
        return new CreatedResponse(research.create(request.urls(), request.brief(), request.provider(), request.model(), request.reasoningEffort()));
    }

    @GetMapping("/{id}")
    public ResearchDetailResponse detail(@PathVariable String id) {
        return responses.to(research.detail(id), ResearchDetailResponse.class);
    }

    @PostMapping("/{id}/messages")
    public CreatedResponse message(@PathVariable String id, @Valid @RequestBody ResearchMessage request) {
        return new CreatedResponse(research.chat(id, request.message(), request.provider(), request.model(), request.reasoningEffort()));
    }
}
