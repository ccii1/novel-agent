package dev.novel.controller;

import dev.novel.dto.response.SystemResponses.*;
import dev.novel.support.ResponseMapper;
import dev.novel.engine.TaskEngine;
import dev.novel.integration.model.ModelClient;
import dev.novel.integration.model.AgentScopeExecutor;
import dev.novel.integration.model.ModelRateLimiter;
import dev.novel.service.SkillService;
import dev.novel.integration.storage.DatabaseStorage;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SystemController {
    private final TaskEngine engine;
    private final ModelClient model;
    private final SkillService skills;
    private final ModelRateLimiter limiter;
    private final DatabaseStorage storage;
    private final ResponseMapper responses;

    public SystemController(TaskEngine engine, ModelClient model, SkillService skills,
                            ModelRateLimiter limiter, DatabaseStorage storage, ResponseMapper responses) {
        this.engine = engine;
        this.model = model;
        this.skills = skills;
        this.limiter = limiter;
        this.storage = storage;
        this.responses = responses;
    }

    @GetMapping("/status")
    public SystemStatusResponse status() {
        return new SystemStatusResponse(responses.to(engine.status(), EngineStatusResponse.class),
                model.configured(), "Asia/Shanghai", skills(), AgentScopeExecutor.FRAMEWORK,
                "mybatis", limiter.mode(), true, storage.kind());
    }

    @GetMapping("/skills")
    public SkillStatusResponse skills() {
        return responses.to(skills.status(), SkillStatusResponse.class);
    }

}
