package dev.novel;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final Store store;
    private final Json json;
    private final ProjectService projects;
    private final MemoryService memory;
    private final BudgetService budget;
    private final TaskEngine engine;
    private final ModelClient model;
    private final SkillService skills;
    private final ChapterQualityService quality;
    private final TruthDocumentService truth;
    private final ModelCatalog catalog;
    public ApiController(Store store,Json json,ProjectService projects,MemoryService memory,BudgetService budget,TaskEngine engine,ModelClient model,
            SkillService skills,ChapterQualityService quality,TruthDocumentService truth,ModelCatalog catalog) {
        this.store=store;this.json=json;this.projects=projects;this.memory=memory;this.budget=budget;this.engine=engine;this.model=model;
        this.skills=skills;this.quality=quality;this.truth=truth;
        this.catalog=catalog;
    }
    public record CreateNovel(@NotBlank @Size(max=200) String title,@NotBlank @Size(max=12000) String premise,
            @NotNull @Size(max=12000) String bible,@Min(1) @Max(20) int stageSize,
            @NotNull @DecimalMin("0.01") @DecimalMax("300") BigDecimal novelBudget,
            @NotNull @DecimalMin("0.01") @DecimalMax("100") BigDecimal chapterBudget,
            @Size(max=80) String provider,@Size(max=200) String model) {}
    public record ModelChoice(@NotBlank @Size(max=80) String provider,@Size(max=200) String model) {}
    @GetMapping("/providers") public Object providers() { return catalog.publicProfiles(); }
    @GetMapping("/providers/{id}/models") public Object models(@PathVariable String id) throws Exception { return catalog.models(id); }
    @PostMapping("/novels/{id}/model") public Object selectModel(@PathVariable String id,@Valid @RequestBody ModelChoice request) {
        projects.selectModel(id,request.provider(),request.model());return Map.of("ok",true);
    }
    public record ResearchRequest(@NotEmpty @Size(max=5) List<@NotBlank @Size(max=1500) String> urls) {}
    public record Revision(@NotBlank @Size(max=12000) String instructions,JsonNode plan) {}
    public record Edit(@Min(1) int chapter,@NotBlank @Size(max=12000) String instructions) {}
    public record EditApproval(boolean acknowledgePublished) {}
    public record Reconcile(@NotNull @DecimalMin("0") @DecimalMax("10000") BigDecimal actual) {}
    public record Published(boolean published) {}
    @GetMapping("/status") public Object status() { return Map.of("engine",engine.status(),"modelConfigured",model.configured(),"timezone","Asia/Shanghai","skill",skills.status()); }
    @GetMapping("/skills") public Object skills() { return skills.status(); }
    @GetMapping("/calls/{id}") public Object call(@PathVariable String id) { return store.one("SELECT * FROM model_calls WHERE id=?",id); }
    @GetMapping("/novels/{id}/quality") public Object quality(@PathVariable String id) { return quality.dashboard(id); }
    @GetMapping("/novels/{id}/truth-files") public Object truth(@PathVariable String id) { return truth.files(id); }
    @GetMapping("/novels/{id}/truth-files/export") public ResponseEntity<byte[]> exportTruth(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("truth-files-"+id+".zip").build().toString()).body(truth.archive(id));
    }
    @GetMapping("/budget") public Object budget() { return budget.overview(); }
    @GetMapping("/novels") public Object novels() { return store.list("SELECT * FROM novels ORDER BY created_at DESC"); }
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
    @GetMapping("/research") public Object research() { return store.list("SELECT * FROM research ORDER BY created_at DESC LIMIT 30"); }
    @GetMapping("/research/tasks") public Object researchTasks() { return store.list("SELECT * FROM tasks WHERE kind='RESEARCH' ORDER BY created_at DESC LIMIT 30"); }
    @PostMapping("/research") public Object research(@Valid @RequestBody ResearchRequest request) {
        return store.transaction(()->Map.of("id",store.enqueue(null,"RESEARCH",0,0,0,"research:"+UUID.randomUUID(),request)));
    }
    @GetMapping("/novels/{id}/chapters/{number}") public Object chapter(@PathVariable String id,@PathVariable int number) { return store.one("SELECT * FROM chapters WHERE novel_id=? AND number=?",id,number); }
    @PostMapping("/novels/{id}/chapters/{number}/published") public Object published(@PathVariable String id,@PathVariable int number,@RequestBody Published request) {
        int changed=store.db.update("UPDATE chapters SET published=? WHERE novel_id=? AND number=? AND status='ACCEPTED'",request.published(),id,number);
        if(changed!=1) throw new IllegalStateException("只能标记已通过检查的章节"); return Map.of("ok",true);
    }
    @GetMapping("/novels/{id}/graph") public Object graph(@PathVariable String id,@RequestParam(defaultValue="") List<String> characters,@RequestParam(defaultValue="2") int depth) {
        return memory.graph(id,Store.number(store.novel(id),"memory_version"),characters.stream().filter(s->!s.isBlank()).toList(),depth);
    }
    @GetMapping("/novels/{id}/memories") public Object memories(@PathVariable String id) { return memory.snapshot(id,Store.number(store.novel(id),"memory_version")); }
    @PostMapping("/novels/{id}/edits") public Object edit(@PathVariable String id,@Valid @RequestBody Edit request) { return projects.requestEdit(id,request.chapter(),request.instructions()); }
    @PostMapping("/edits/{id}/approve") public Object approveEdit(@PathVariable String id,@RequestBody EditApproval request) { projects.approveEdit(id,request.acknowledgePublished());return Map.of("ok",true); }
    @PostMapping("/calls/{id}/reconcile") public Object reconcile(@PathVariable String id,@Valid @RequestBody Reconcile request) { budget.reconcile(id,request.actual());return Map.of("ok",true); }
    @GetMapping(value="/novels/{id}/export",produces="text/plain;charset=UTF-8") public ResponseEntity<String> export(@PathVariable String id) {
        var novel=store.novel(id);StringBuilder text=new StringBuilder(Store.string(novel,"title")).append("\n\n");
        store.list("SELECT number,title,body FROM chapters WHERE novel_id=? AND status='ACCEPTED' ORDER BY number",id).forEach(chapter->text.append("第").append(chapter.get("number")).append("章 ").append(chapter.get("title")).append("\n\n").append(chapter.get("body")).append("\n\n"));
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(Store.string(novel,"title")+".txt",StandardCharsets.UTF_8).build().toString()).body(text.toString());
    }
}
