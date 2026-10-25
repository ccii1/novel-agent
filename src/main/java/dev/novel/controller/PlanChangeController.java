package dev.novel.controller;

import dev.novel.service.PlanChangeService;
import dev.novel.dto.ApiRequests.PlanChangeRequest;
import dev.novel.dto.ApiRequests.PlanChangeApproval;
import dev.novel.dto.response.CommonResponses.*;
import dev.novel.dto.response.PlanResponses.PlanChangesResponse;
import dev.novel.support.ResponseMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/novels/{id}/plan-changes")
public class PlanChangeController {
    private final PlanChangeService changes;
    private final ResponseMapper responses;

    public PlanChangeController(PlanChangeService changes, ResponseMapper responses) {
        this.changes = changes;
        this.responses = responses;
    }

    @GetMapping
    public PlanChangesResponse list(@PathVariable String id) {
        return responses.to(changes.detail(id), PlanChangesResponse.class);
    }

    @PostMapping
    public CreatedResponse request(@PathVariable String id, @Valid @RequestBody PlanChangeRequest request) {
        return new CreatedResponse(changes.request(id, request.path(), request.instructions()));
    }

    @PostMapping("/{changeId}/approve")
    public ActionResponse approve(@PathVariable String id, @PathVariable String changeId,
                                  @RequestBody PlanChangeApproval approval) {
        changes.approve(id, changeId, approval.acknowledgeCore(), approval.acknowledgeCompleted());
        return ActionResponse.SUCCESS;
    }
}
