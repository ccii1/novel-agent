package dev.novel.service;

import dev.novel.repository.Store;
import dev.novel.support.Json;
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
    private final ChapterMemoryTopics topics;
    public TruthDocumentService(Store store,Json json,MemoryService memory,ChapterQualityService quality,ChapterMemoryTopics topics) {
        this.store=store;this.json=json;this.memory=memory;this.quality=quality;
        this.topics=topics;
    }
    public Map<String,Object> prompt(Map<String,Object> novel,int chapter) {
        return prompt(novel,chapter,List.of("MEMORY.md","story_bible.md","outline.md","current_state.md","pending_hooks.md","handoff_current.md",
                "chapter_summaries.md","character_matrix.md","character_knowledge.md","progress_tracker.md","dialogue-monitor.md","pattern-detection.md","book_rules.md","style_guide.md"));
    }
    public Map<String,Object> prompt(Map<String,Object> novel,int chapter,List<String> requestedFiles) {
        String id=Store.string(novel,"id");long version=Store.number(novel,"memory_version");
        var files=memory.files();files.ensure(id,version);
        var result=new LinkedHashMap<String,Object>();
        var plan=Store.string(novel,"plan").isBlank()?json.read("{}"):json.read(Store.string(novel,"plan"));
        var metrics=new HashMap<Integer,List<Map<String,Object>>>();
        java.util.function.IntFunction<List<Map<String,Object>>> recent=limit->metrics.computeIfAbsent(limit,key->quality.recent(id,chapter,key));
        result.put("MEMORY.md",files.readFull(id,version,"MEMORY.md"));
        for(String name:requestedFiles) {
            Object value=switch(name) {
                case "MEMORY.md" -> result.get("MEMORY.md");
                case "current_state.md","handoff_current.md" -> files.readFull(id,version,name);
                case "story_bible.md" -> Map.of("title",novel.get("title"),"premise",novel.get("premise"),"bible",novel.get("bible"));
                case "outline.md" -> plan;
                case "pending_hooks.md" -> Map.of("directory","hooks/","instruction","读取本章指定、到期与相关的伏笔文件；目录支持分页，未读取不代表不存在。");
                case "character_matrix.md","character_knowledge.md" -> Map.of("directory","characters/","instruction","人物关系与知情范围保存在人物主题文件，读取出场人物并核对获知时间。");
                case "chapter_summaries.md" -> Map.of("directory","chapters/","instruction","根据章节编号读取历史记录，需要证据时使用 read_source。");
                case "progress_tracker.md" -> Map.of("stage",novel.get("stage_no"),"nextChapter",chapter,"recentMetrics",recent.apply(5));
                case "dialogue-monitor.md" -> Map.of("recentMetrics",recent.apply(5),"ratioDefinition","含引号的非空行数 / 全部非空行数");
                case "pattern-detection.md" -> Map.of("reviewDue",chapter%5==0,"fullReviewDue",chapter%10==0,"recentMetrics",recent.apply(chapter%10==0?9:5));
                case "book_rules.md" -> Map.of("approval","重大剧情变更等待人工批准");
                case "style_guide.md" -> Map.of("targetCharacters","2000至2500","lengthPolicy","参考篇幅；先保存完整草稿，字数单独记录整理建议，依据具体内容问题修改。","dialogueLimit",0.4);
                default -> throw new IllegalArgumentException("未知 Agent 上下文文件："+name);
            };
            result.put(name,value);
        }
        result.put("memoryVersion",version);
        result.put("memoryTools","requiredTopics 与 _loadedMemory 标明已经提供的原文及范围，完整加载的文件无需再次读取。unresolvedTopics 提供本章候选路径，优先 read_memory_batch 批量获取；路径未知时再使用 search_memory 或 list_memory_files。read_memory 按 nextOffset 继续读取；read_source 核对正文。必需资料缺失时明确报告，不能仅依据目录判断事实。");
        var terms=new LinkedHashSet<String>();
        plan.path("chapters").forEach(item->{if(item.path("number").asInt()==chapter) {
            item.path("characters").forEach(term->terms.add(term.asText()));item.path("foreshadowIds").forEach(term->terms.add(term.asText()));
        }});
        var manifest=files.manifest(id,version);
        boolean includeTopics=requestedFiles.contains("current_state.md") || requestedFiles.contains("character_matrix.md");
        if(includeTopics) result.putAll(topics.resolve(files,id,version,terms,manifest));
        var selected=json.tree(result.getOrDefault("requiredTopics",Map.of()));
        var hashes=new LinkedHashMap<String,String>();
        var loaded=new ArrayList<Map<String,Object>>();
        for(var row:manifest) {
            String path=Store.string(row,"path");
            if(!result.containsKey(path) && !selected.has(path)) continue;
            String text=result.containsKey(path)?Objects.toString(result.get(path)):selected.path(path).asText();
            hashes.put(path,Store.string(row,"fingerprint"));
            loaded.add(Map.of("path",path,"hash",row.get("fingerprint"),"offset",0,"nextOffset",text.codePointCount(0,text.length()),"complete",true));
        }
        result.put("fileHashes",hashes);
        result.put("_loadedMemory",loaded);
        return result;
    }
    public Map<String,String> files(String id) {
        return store.transaction(()-> {
            var novel=store.lockNovel(id);var result=new LinkedHashMap<>(memory.files().export(id,Store.number(novel,"memory_version")));
            result.put("story_bible.md","# 核心设定\n\n"+Store.string(novel,"title")+"\n\n"+Store.string(novel,"premise")+"\n\n"+Store.string(novel,"bible")+"\n");
            result.put("outline.md","# 批准的大纲\n\n```json\n"+Store.string(novel,"plan")+"\n```\n");
            result.put("progress_tracker.md","# 创作进度\n\n```json\n"+json.write(quality.dashboard(id))+"\n```\n");
            result.put("pending_hooks.md","# 伏笔目录\n\n参见 hooks/INDEX.md 以及对应伏笔主题文件。\n");
            result.put("character_matrix.md","# 人物关系\n\n参见 characters/INDEX.md 及各人物主题文件中的人物关系。\n");
            result.put("character_knowledge.md","# 人物知情范围\n\n参见各人物主题文件中的知情范围，核对获知时间与来源。\n");
            result.put("dialogue-monitor.md","# 对话监控\n\n```json\n"+json.write(quality.recent(id,Store.integer(novel,"chapter_no"),5))+"\n```\n");
            result.put("pattern-detection.md","# 叙事模式\n\n```json\n"+json.write(quality.recent(id,Store.integer(novel,"chapter_no"),10))+"\n```\n");
            return result;
        });
    }
    public byte[] archive(String id) {
        var documents=files(id);
        try(var buffer=new ByteArrayOutputStream();var zip=new ZipOutputStream(buffer,StandardCharsets.UTF_8)) {
            for(var document:documents.entrySet()) {
                zip.putNextEntry(new ZipEntry(document.getKey()));zip.write(document.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
            zip.finish();return buffer.toByteArray();
        } catch(IOException failure) { throw new IllegalStateException("记忆文件导出失败",failure); }
    }
}
