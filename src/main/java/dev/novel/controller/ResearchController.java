package dev.novel.controller;

import dev.novel.dto.ApiRequests.ResearchRequest;
import dev.novel.dto.ApiRequests.ResearchMessage;
import dev.novel.service.ResearchService;
import dev.novel.service.WorkspaceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/research")
public class ResearchController {
    private final ResearchService research;
    private final WorkspaceService workspace;
    public ResearchController(ResearchService research,WorkspaceService workspace) {this.research=research;this.workspace=workspace;}
    @GetMapping public Object reports() { return workspace.researchReports(); }
    @GetMapping("/tasks") public Object tasks() { return workspace.researchTasks(); }
    @PostMapping public Object create(@Valid @RequestBody ResearchRequest request) {
        return Map.of("id",research.create(request.urls(),request.brief()));
    }
    @GetMapping("/{id}") public Object detail(@PathVariable String id) { return research.detail(id); }
    @PostMapping("/{id}/messages") public Object message(@PathVariable String id,@Valid @RequestBody ResearchMessage request) {
        return Map.of("id",research.chat(id,request.message()));
    }
}
