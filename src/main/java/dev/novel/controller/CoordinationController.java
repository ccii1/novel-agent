package dev.novel.controller;

import dev.novel.service.CoordinationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/novels/{id}/coordination")
public class CoordinationController {
    private final CoordinationService coordination;
    public CoordinationController(CoordinationService coordination) { this.coordination=coordination; }
    public record Request(@NotBlank @Size(max=12000) String instructions) {}
    @GetMapping public Object list(@PathVariable String id) { return coordination.list(id); }
    @PostMapping public Object request(@PathVariable String id,@Valid @RequestBody Request request) { return Map.of("id",coordination.request(id,request.instructions())); }
    @PostMapping("/{taskId}/approve") public Object approve(@PathVariable String id,@PathVariable String taskId) { return Map.of("id",coordination.approve(id,taskId)); }
}
