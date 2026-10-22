package dev.novel.controller;

import dev.novel.dto.ApiRequests.Reconcile;
import dev.novel.dto.response.CommonResponses.ActionResponse;
import dev.novel.dto.response.SystemResponses.BudgetResponse;
import dev.novel.dto.response.TaskResponses.ModelCallResponse;
import dev.novel.service.BudgetService;
import dev.novel.service.WorkspaceService;
import dev.novel.support.ResponseMapper;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class BudgetController {
    private final BudgetService budget;
    private final WorkspaceService workspace;
    private final ResponseMapper responses;

    public BudgetController(BudgetService budget, WorkspaceService workspace, ResponseMapper responses) {
        this.budget = budget;
        this.workspace = workspace;
        this.responses = responses;
    }

    @GetMapping("/budget")
    public BudgetResponse overview() {
        return responses.to(budget.overview(), BudgetResponse.class);
    }

    @GetMapping("/calls/{id}")
    public ModelCallResponse call(@PathVariable String id) {
        return responses.to(workspace.call(id), ModelCallResponse.class);
    }

    @PostMapping("/calls/{id}/reconcile")
    public ActionResponse reconcile(@PathVariable String id, @Valid @RequestBody Reconcile request) {
        budget.reconcile(id, request.actual());
        return ActionResponse.SUCCESS;
    }
}
