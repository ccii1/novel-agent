package dev.novel.controller;

import dev.novel.dto.ApiRequests.Reconcile;
import dev.novel.engine.TaskEngine;
import dev.novel.integration.model.ModelCatalog;
import dev.novel.integration.model.ModelClient;
import dev.novel.integration.model.AgentScopeExecutor;
import dev.novel.integration.model.ModelRateLimiter;
import dev.novel.service.BudgetService;
import dev.novel.service.SkillService;
import dev.novel.service.WorkspaceService;
import dev.novel.service.AgentService;
import dev.novel.service.DatabaseStorage;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SystemController {
    private final TaskEngine engine;
    private final ModelClient model;
    private final ModelCatalog catalog;
    private final SkillService skills;
    private final BudgetService budget;
    private final WorkspaceService workspace;
    private final ModelRateLimiter limiter;
    private final AgentService agents;
    private final DatabaseStorage storage;
    public SystemController(TaskEngine engine,ModelClient model,ModelCatalog catalog,SkillService skills,BudgetService budget,WorkspaceService workspace,ModelRateLimiter limiter,AgentService agents,DatabaseStorage storage) {
        this.engine=engine;this.model=model;this.catalog=catalog;this.skills=skills;this.budget=budget;this.workspace=workspace;
        this.limiter=limiter;
        this.agents=agents;
        this.storage=storage;
    }
    @GetMapping("/status") public Object status() { return Map.of("engine",engine.status(),"modelConfigured",model.configured(),"timezone","Asia/Shanghai","skill",skills.status(),"agentFramework",AgentScopeExecutor.FRAMEWORK,"persistence","mybatis","rateLimiter",limiter.mode(),"researchModelSelection",true,"database",storage.kind()); }
    @GetMapping("/skills") public Object skills() { return skills.status(); }
    @GetMapping("/agents") public Object agents() { return agents.catalogue(); }
    @GetMapping("/agents/metrics") public Object agentMetrics(@RequestParam(required=false) String novelId) { return agents.metrics(novelId); }
    @GetMapping("/providers") public Object providers() { return catalog.publicProfiles(); }
    @GetMapping("/providers/{id}/models") public Object models(@PathVariable String id) throws Exception { return catalog.models(id); }
    @GetMapping("/budget") public Object budget() { return budget.overview(); }
    @GetMapping("/calls/{id}") public Object call(@PathVariable String id) { return workspace.call(id); }
    @PostMapping("/calls/{id}/reconcile") public Object reconcile(@PathVariable String id,@Valid @RequestBody Reconcile request) {
        budget.reconcile(id,request.actual());return Map.of("ok",true);
    }
}
