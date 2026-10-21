package dev.novel.controller;

import dev.novel.service.PlanChangeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/novels/{id}/plan-changes")
public class PlanChangeController {
    private final PlanChangeService changes;
    public PlanChangeController(PlanChangeService changes) { this.changes=changes; }
    public record Request(@NotBlank @Size(max=200) String path,@NotBlank @Size(max=12000) String instructions) {}
    public record Approval(boolean acknowledgeCore,boolean acknowledgeCompleted) {}
    @GetMapping public Object list(@PathVariable String id) { return changes.detail(id); }
    @PostMapping public Object request(@PathVariable String id,@Valid @RequestBody Request request) {
        return Map.of("id",changes.request(id,request.path(),request.instructions()));
    }
    @PostMapping("/{changeId}/approve") public Object approve(@PathVariable String id,@PathVariable String changeId,@RequestBody Approval approval) {
        changes.approve(id,changeId,approval.acknowledgeCore(),approval.acknowledgeCompleted());return Map.of("ok",true);
    }
}
