package dev.novel.service;

import dev.novel.repository.Store;
import dev.novel.support.Json;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class TruthDocumentService {
    private final Store store;
    private final Json json;
    private final MemoryService memory;
    private final ChapterQualityService quality;
    public TruthDocumentService(Store store,Json json,MemoryService memory,ChapterQualityService quality) {
        this.store=store;this.json=json;this.memory=memory;this.quality=quality;
    }
    public Map<String,Object> prompt(Map<String,Object> novel,int chapter) {
        return prompt(novel,chapter,List.of("story_bible.md","outline.md","current_state.md","pending_hooks.md","handoff_current.md",
                "chapter_summaries.md","character_matrix.md","character_knowledge.md","progress_tracker.md","dialogue-monitor.md",
                "pattern-detection.md","book_rules.md","style_guide.md"));
    }
    public Map<String,Object> prompt(Map<String,Object> novel,int chapter,List<String> requestedFiles) {
        String id=(String)novel.get("id");
        var context=memory.contextData(novel,chapter);
        boolean metrics=requestedFiles.stream().anyMatch(Set.of("progress_tracker.md","dialogue-monitor.md","pattern-detection.md")::contains);
        List<Map<String,Object>> recent=metrics?quality.recent(id,chapter,chapter%10==0?9:4):List.of();
        Map<String,Object> files=new LinkedHashMap<>();
        files.put("story_bible.md",Map.of("title",novel.get("title"),"premise",novel.get("premise"),"bible",novel.get("bible")));
        files.put("outline.md",context.get("approvedPlan"));
        files.put("current_state.md",Map.of("memoryVersion",context.get("memoryVersion"),"relatedFacts",context.get("facts"),"previousEnding",context.get("previousEnding")));
        files.put("pending_hooks.md",hooks(json.tree(context.get("facts"))));
        files.put("handoff_current.md",Map.of("completedChapter",Math.max(0,chapter-1),"nextChapter",chapter,
                "memoryVersion",novel.get("memory_version"),"recentSummaries",context.get("recentSummaries")));
        files.put("chapter_summaries.md",context.get("recentSummaries"));
        var relationships=json.tree(context.get("relationships"));
        files.put("character_matrix.md",matrix(relationships));
        files.put("character_knowledge.md",knowledge(relationships));
        files.put("progress_tracker.md",Map.of("stage",novel.get("stage_no"),"nextChapter",chapter,"recentMetrics",recent));
        files.put("dialogue-monitor.md",Map.of("recentMetrics",recent,"rising",quality.rising(recent),"ratioDefinition","含引号的非空行数 / 全部非空行数"));
        files.put("pattern-detection.md",Map.of("reviewDue",chapter%5==0,"recentMetrics",recent,"fullReviewDue",chapter%10==0));
        files.put("book_rules.md",Map.of("approval","重大剧情变更等待人工批准"));
        files.put("style_guide.md",Map.of("requirements",novel.get("bible"),"targetCharacters","2000至2500","dialogueLimit",0.4));
        Map<String,Object> selected=new LinkedHashMap<>();
        for(String name:requestedFiles) {
            if(!files.containsKey(name)) throw new IllegalArgumentException("未知 Agent 上下文文件："+name);
            selected.put(name,files.get(name));
        }
        return selected;
    }
    private List<JsonNode> hooks(JsonNode facts) {
        List<JsonNode> hooks=new ArrayList<>();
        facts.forEach(item->{if("FORESHADOW".equals(item.path("kind").asText()) && item.path("data").path("active").asBoolean()) hooks.add(item);});
        return hooks;
    }
    private Map<String,Object> matrix(JsonNode graph) {
        var relations=new ArrayList<JsonNode>();
        graph.path("edges").forEach(edge -> { if(!"KNOWS".equals(edge.path("predicate").asText())) relations.add(edge); });
        return Map.of("version",graph.path("version"),"nodes",graph.path("nodes"),"edges",relations);
    }
    private List<JsonNode> knowledge(JsonNode graph) {
        var known=new ArrayList<JsonNode>();
        graph.path("edges").forEach(edge -> { if("KNOWS".equals(edge.path("predicate").asText())) known.add(edge); });
        return known;
    }
    public Map<String,String> files(String id) {
        return store.transaction(()-> {
            var novel=store.lockNovel(id);
            Map<String,Object> documents=prompt(novel,Store.integer(novel,"chapter_no"));
            var complete=memory.snapshot(id,Store.number(novel,"memory_version"));
            documents.put("current_state.md",Map.of("memoryVersion",novel.get("memory_version"),"facts",complete));
            documents.put("pending_hooks.md",complete.stream().filter(row->"FORESHADOW".equals(row.get("kind")))
                    .filter(row->json.read((String)row.get("content")).path("active").asBoolean(false))
                    .filter(row->!json.read((String)row.get("content")).path("deleted").asBoolean(false)).toList());
            documents.put("chapter_summaries.md",store.list("chapters.allSummaries",id));
            var graph=json.tree(memory.graph(id,Store.number(novel,"memory_version"),List.of(),2));
            documents.put("character_matrix.md",matrix(graph));
            documents.put("character_knowledge.md",knowledge(graph));
            documents.put("progress_tracker.md",quality.dashboard(id));
            Map<String,String> result=new LinkedHashMap<>();
            documents.forEach((name,data)->result.put(name,"# "+name+"\n\n```json\n"+json.write(data)+"\n```\n"));
            return result;
        });
    }
    public byte[] archive(String id) {
        var documents=files(id);
        try(var buffer=new ByteArrayOutputStream();var zip=new ZipOutputStream(buffer,StandardCharsets.UTF_8)) {
            for(var document:documents.entrySet()) {
                zip.putNextEntry(new ZipEntry(document.getKey()));
                zip.write(document.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
            zip.finish();return buffer.toByteArray();
        } catch(IOException e) { throw new IllegalStateException("Truth Files 导出失败",e); }
    }
}
