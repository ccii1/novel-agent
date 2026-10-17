package dev.novel.service;

import dev.novel.repository.Store;
import dev.novel.support.Json;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class ChapterQualityService {
    private static final Pattern QUOTE = Pattern.compile("[\"“”「」『』]");
    private final Store store;
    private final Json json;
    public ChapterQualityService(Store store,Json json) { this.store=store;this.json=json; }
    public record Metrics(int characters,int totalLines,int dialogueLines,double dialogueRatio) {}
    public Metrics measure(String body) {
        var lines=body.lines().filter(line->!line.isBlank()).toList();
        int characters=(int)body.codePoints().filter(point->!Character.isWhitespace(point)).count();
        int dialogue=(int)lines.stream().filter(line->QUOTE.matcher(line).find()).count();
        return new Metrics(characters,lines.size(),dialogue,lines.isEmpty()?0:(double)dialogue/lines.size());
    }
    public void record(String novel,int chapter,int revision,String body) {
        var value=measure(body);
        store.update("MERGE INTO chapter_metrics(novel_id,chapter_no,revision,characters,total_lines,dialogue_lines,dialogue_ratio) KEY(novel_id,chapter_no,revision) VALUES(?,?,?,?,?,?,?)",
                novel,chapter,revision,value.characters(),value.totalLines(),value.dialogueLines(),value.dialogueRatio());
    }
    public List<Map<String,Object>> recent(String novel,int before,int limit) {
        return store.list("SELECT m.* FROM chapter_metrics m JOIN chapters c ON c.novel_id=m.novel_id AND c.number=m.chapter_no AND c.revision=m.revision WHERE c.novel_id=? AND c.number<? AND c.status='ACCEPTED' ORDER BY c.number DESC LIMIT ?",novel,before,limit);
    }
    public Map<String,Object> dashboard(String novel) {
        store.novel(novel);
        var rows=recent(novel,Integer.MAX_VALUE,20);
        var totals=store.one("SELECT COUNT(*) AS chapters,COALESCE(SUM(CHAR_LENGTH(body)),0) AS stored_characters FROM chapters WHERE novel_id=? AND status='ACCEPTED'",novel);
        return Map.of("totals",totals,"recent",rows,"dialogueRising",rising(rows),"measurement","含引号的非空行数 / 全部非空行数");
    }
    public boolean rising(List<Map<String,Object>> latestFirst) {
        return latestFirst.size()>=3 && ratio(latestFirst.get(0))>ratio(latestFirst.get(1)) && ratio(latestFirst.get(1))>ratio(latestFirst.get(2));
    }
    private double ratio(Map<String,Object> value) { return ((Number)value.get("dialogue_ratio")).doubleValue(); }
    public void enforceStyle(com.fasterxml.jackson.databind.JsonNode review,String body) {
        var metrics=measure(body);
        var issues=(com.fasterxml.jackson.databind.node.ArrayNode)review.get("issues");
        String quote=body.substring(0,Math.min(body.length(),40));
        if(metrics.characters()<2000 || metrics.characters()>2500)
            issues.add(json.read(json.write(Map.of("severity","major","quote",quote,"reason","本章非空白字符数为"+metrics.characters()+"，目标为2000至2500","suggestion","保持剧情目标，调整正文长度"))));
        if(metrics.dialogueRatio()>0.4)
            issues.add(json.read(json.write(Map.of("severity","major","quote",quote,"reason","含引号的非空行占比超过40%："+metrics.dialogueLines()+"/"+metrics.totalLines(),"suggestion","压缩无效对话，补充推进情节的动作和叙述"))));
        if(!issues.isEmpty()) ((com.fasterxml.jackson.databind.node.ObjectNode)review).put("approved",false);
    }
}
