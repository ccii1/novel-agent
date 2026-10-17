package dev.novel.controller;

import dev.novel.service.*;
import dev.novel.dto.ApiRequests.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api")
public class NovelController {
    private final WorkspaceService workspace;
    private final ProjectService projects;
    private final ChapterQualityService quality;
    private final TruthDocumentService truth;
    public NovelController(WorkspaceService workspace,ProjectService projects,ChapterQualityService quality,TruthDocumentService truth) {
        this.workspace=workspace;this.projects=projects;this.quality=quality;this.truth=truth;
    }
    @PostMapping("/novels/{id}/model") public Object selectModel(@PathVariable String id,@Valid @RequestBody ModelChoice request) {
        projects.selectModel(id,request.provider(),request.model());return Map.of("ok",true);
    }
    @GetMapping("/novels/{id}/quality") public Object quality(@PathVariable String id) { return quality.dashboard(id); }
    @GetMapping("/novels/{id}/truth-files") public Object truth(@PathVariable String id) { return truth.files(id); }
    @GetMapping("/novels/{id}/truth-files/export") public ResponseEntity<byte[]> exportTruth(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("truth-files-"+id+".zip").build().toString()).body(truth.archive(id));
    }
    @GetMapping("/novels") public Object novels() { return workspace.novels(); }
    @PostMapping("/novels") public Object create(@Valid @RequestBody CreateNovel request) {
        return Map.of("id",projects.create(request.title(),request.premise(),request.bible(),request.stageSize(),request.novelBudget(),request.chapterBudget(),request.provider(),request.model()));
    }
    @GetMapping("/novels/{id}") public Object detail(@PathVariable String id) { return projects.detail(id); }
    @PostMapping("/novels/{id}/approve-plan") public Object approve(@PathVariable String id,@RequestBody(required=false) JsonNode plan) { projects.approvePlan(id,plan);return Map.of("ok",true); }
    @PostMapping("/novels/{id}/continue") public Object next(@PathVariable String id) { projects.continueStage(id);return Map.of("ok",true); }
    @PostMapping("/novels/{id}/pause") public Object pause(@PathVariable String id) { projects.pause(id);return Map.of("ok",true); }
    @PostMapping("/novels/{id}/resume") public Object resume(@PathVariable String id) { projects.resume(id);return Map.of("ok",true); }
    @PostMapping("/novels/{id}/revise") public Object revise(@PathVariable String id,@Valid @RequestBody Revision request) { projects.revise(id,request.instructions(),request.plan());return Map.of("ok",true); }
    @PostMapping("/tasks/{id}/retry") public Object retry(@PathVariable String id) { return Map.of("id",projects.retry(id)); }
    @GetMapping("/novels/{id}/chapters/{number}") public Object chapter(@PathVariable String id,@PathVariable int number) { return workspace.chapter(id,number); }
    @PostMapping("/novels/{id}/chapters/{number}/published") public Object published(@PathVariable String id,@PathVariable int number,@RequestBody Published request) {
        workspace.published(id,number,request.published());return Map.of("ok",true);
    }
    @GetMapping("/novels/{id}/graph") public Object graph(@PathVariable String id,@RequestParam(defaultValue="") List<String> characters,@RequestParam(defaultValue="2") int depth) {
        return workspace.graph(id,characters,depth);
    }
    @GetMapping("/novels/{id}/memories") public Object memories(@PathVariable String id) { return workspace.memories(id); }
    @PostMapping("/novels/{id}/edits") public Object edit(@PathVariable String id,@Valid @RequestBody Edit request) { return projects.requestEdit(id,request.chapter(),request.instructions()); }
    @PostMapping("/edits/{id}/approve") public Object approveEdit(@PathVariable String id,@RequestBody EditApproval request) { projects.approveEdit(id,request.acknowledgePublished());return Map.of("ok",true); }
    @GetMapping(value="/novels/{id}/export",produces="text/plain;charset=UTF-8") public ResponseEntity<String> export(@PathVariable String id) {
        var manuscript=workspace.manuscript(id);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(manuscript.title()+".txt",StandardCharsets.UTF_8).build().toString()).body(manuscript.body());
    }
}
