package dev.novel.controller;

import com.fasterxml.jackson.databind.JsonNode;
import dev.novel.dto.ApiRequests.*;
import dev.novel.dto.response.CommonResponses.*;
import dev.novel.dto.response.MemoryResponses.*;
import dev.novel.dto.response.NovelResponses.*;
import dev.novel.dto.response.QualityResponses.QualityDashboardResponse;
import dev.novel.service.*;
import dev.novel.support.ResponseMapper;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static dev.novel.dto.response.CommonResponses.ActionResponse.SUCCESS;

@RestController
@RequestMapping("/api")
public class NovelController {
    private final WorkspaceService workspace;
    private final ProjectService projects;
    private final ChapterQualityService quality;
    private final TruthDocumentService truth;
    private final ResponseMapper responses;

    public NovelController(WorkspaceService workspace, ProjectService projects,
                           ChapterQualityService quality, TruthDocumentService truth, ResponseMapper responses) {
        this.workspace = workspace;
        this.projects = projects;
        this.quality = quality;
        this.truth = truth;
        this.responses = responses;
    }

    @PostMapping("/novels/{id}/model")
    public ActionResponse selectModel(@PathVariable String id, @Valid @RequestBody ModelChoice request) {
        projects.selectModel(id, request.provider(), request.model(), request.reasoningEffort());
        return SUCCESS;
    }

    @GetMapping("/novels/{id}/quality")
    public QualityDashboardResponse quality(@PathVariable String id) {
        return responses.to(quality.dashboard(id), QualityDashboardResponse.class);
    }

    @GetMapping("/novels/{id}/truth-files")
    public Map<String, String> truth(@PathVariable String id) {
        return truth.files(id);
    }

    @GetMapping("/novels/{id}/truth-files/export")
    public ResponseEntity<byte[]> exportTruth(@PathVariable String id) {
        String disposition = ContentDisposition.attachment().filename("truth-files-" + id + ".zip").build().toString();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition).body(truth.archive(id));
    }

    @GetMapping("/novels")
    public List<NovelResponse> novels() {
        return responses.list(workspace.novels(), NovelResponse.class);
    }

    @GetMapping("/novels/summaries")
    public List<NovelSummaryResponse> summaries() {
        return responses.list(workspace.novelSummaries(), NovelSummaryResponse.class);
    }

    @GetMapping("/novels/{id}/snapshot")
    public NovelSnapshotResponse snapshot(@PathVariable String id) {
        return responses.to(workspace.snapshot(id), NovelSnapshotResponse.class);
    }

    @PostMapping("/novels")
    public CreatedResponse create(@Valid @RequestBody CreateNovel request) {
        String id = projects.create(request.title(), request.premise(), request.bible(),
                request.stageSize(), request.novelBudget(), request.chapterBudget(), request.provider(), request.model(), request.reasoningEffort());
        return new CreatedResponse(id);
    }

    @GetMapping("/novels/{id}")
    public NovelDetailResponse detail(@PathVariable String id) {
        return responses.to(projects.detail(id), NovelDetailResponse.class);
    }

    @GetMapping("/novels/{id}/memory-review")
    public MemoryReviewResponse memoryReview(@PathVariable String id) {
        return responses.to(projects.memoryReview(id), MemoryReviewResponse.class);
    }

    @PostMapping("/novels/{id}/memory-repair")
    public CreatedResponse repairMemory(@PathVariable String id, @Valid @RequestBody MemoryRepair request) {
        return new CreatedResponse(projects.repairMemory(id, request.taskId(), request.instructions(), request.delta()));
    }

    @PostMapping("/novels/{id}/approve-plan")
    public ActionResponse approve(@PathVariable String id, @RequestBody(required = false) JsonNode plan,
                                  @RequestParam(defaultValue = "false") boolean useCurrentPlan) {
        projects.approvePlan(id, useCurrentPlan ? null : plan);
        return SUCCESS;
    }

    @PostMapping("/novels/{id}/continue")
    public ActionResponse next(@PathVariable String id) {
        projects.continueStage(id);
        return SUCCESS;
    }

    @PostMapping("/novels/{id}/pause")
    public ActionResponse pause(@PathVariable String id) {
        projects.pause(id);
        return SUCCESS;
    }

    @PostMapping("/novels/{id}/resume")
    public ActionResponse resume(@PathVariable String id) {
        projects.resume(id);
        return SUCCESS;
    }

    @PostMapping("/novels/{id}/revise")
    public ActionResponse revise(@PathVariable String id, @Valid @RequestBody Revision request) {
        projects.revise(id, request.instructions(), request.plan(), request.useCurrentPlan());
        return SUCCESS;
    }

    @PostMapping("/tasks/{id}/retry")
    public CreatedResponse retry(@PathVariable String id, @Valid @RequestBody(required = false) RetryTask request) {
        return new CreatedResponse(projects.retry(id,
                request == null ? null : request.provider(), request == null ? null : request.model(), request == null ? null : request.reasoningEffort()));
    }

    @GetMapping("/novels/{id}/chapters/{number}")
    public ChapterResponse chapter(@PathVariable String id, @PathVariable int number) {
        return responses.to(workspace.chapter(id, number), ChapterResponse.class);
    }

    @PostMapping("/novels/{id}/chapters/{number}/published")
    public ActionResponse published(@PathVariable String id, @PathVariable int number, @RequestBody Published request) {
        workspace.published(id, number, request.published());
        return SUCCESS;
    }

    @GetMapping("/novels/{id}/memories")
    public List<MemoryResponse> memories(@PathVariable String id) {
        return responses.list(workspace.memories(id), MemoryResponse.class);
    }

    @PostMapping("/novels/{id}/edits")
    public EditCreatedResponse edit(@PathVariable String id, @Valid @RequestBody Edit request) {
        return responses.to(projects.requestEdit(id, request.chapter(), request.instructions()), EditCreatedResponse.class);
    }

    @PostMapping("/edits/{id}/approve")
    public ActionResponse approveEdit(@PathVariable String id, @RequestBody EditApproval request) {
        projects.approveEdit(id, request.acknowledgePublished());
        return SUCCESS;
    }

    @GetMapping(value = "/novels/{id}/export", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> export(@PathVariable String id) {
        var manuscript = workspace.manuscript(id);
        String disposition = ContentDisposition.attachment()
                .filename(manuscript.title() + ".txt", StandardCharsets.UTF_8).build().toString();
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, disposition).body(manuscript.body());
    }
}
