package dev.novel.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

// 只移除完整的检索记录，固定设定和已批准的章节目标保持原文。
@Component
public class ContextBudget {
    private static final List<String> RETRIEVALS=List.of(
            "/current_state.md/relatedFacts", "/pending_hooks.md", "/handoff_current.md/recentSummaries",
            "/chapter_summaries.md", "/character_matrix.md/edges", "/character_matrix.md/nodes",
            "/character_knowledge.md", "/progress_tracker.md/recentMetrics",
            "/dialogue-monitor.md/recentMetrics", "/pattern-detection.md/recentMetrics",
            "/memory/WORLD", "/memory/CHARACTER", "/memory/FORESHADOW", "/memory/EVENT", "/recentSummaries");
    private final Json json;
    public ContextBudget(Json json) { this.json=json; }

    public JsonNode pack(Map<String,Object> documents,int limit,boolean fullPlan,int chapter) {
        ObjectNode result=(ObjectNode)json.tree(documents).deepCopy();
        if(!fullPlan && result.path("outline.md").path("chapters").isArray()) {
            ArrayNode chapters=(ArrayNode)result.path("outline.md").path("chapters");
            for(int index=chapters.size()-1;index>=0;index--) {
                int number=chapters.get(index).path("number").asInt();
                if(number!=chapter && number!=chapter+1) chapters.remove(index);
            }
        }
        var counts=new LinkedHashMap<String,Integer>();
        for(String pointer:RETRIEVALS) if(result.at(pointer).isArray()) counts.put(pointer,result.at(pointer).size());
        ObjectNode metadata=result.putObject("_contextBudget");
        metadata.put("limitBytes",limit);
        metadata.put("note","检索记录可能因预算省略，省略不表示事实不存在；资料不足时按关键词分页读取记忆。");
        ObjectNode omitted=metadata.putObject("omittedRecords");
        counts.forEach((pointer,count)->omitted.put(pointer,0));
        int bytes=bytes(result);
        while(true) {
            if(bytes<=limit) {
                counts.forEach((pointer,count)->omitted.put(pointer,count-result.at(pointer).size()));
                bytes=bytes(result);
                if(bytes<=limit) return result;
            }
            ArrayNode selected=null;
            int largest=0;
            for(String pointer:counts.keySet()) {
                ArrayNode records=(ArrayNode)result.at(pointer);
                if(records.isEmpty()) continue;
                int tailBytes=bytes(records.get(records.size()-1));
                if(tailBytes>largest) { selected=records;largest=tailBytes; }
            }
            if(selected==null) throw new IllegalStateException("固定设定与章节目标需要"+bytes+"字节，超过本角色可用预算"+limit+"字节；尚未请求模型");
            bytes-=largest+(selected.size()>1?1:0);
            selected.remove(selected.size()-1);
        }
    }

    public int bytes(Object value) { return json.write(value).getBytes(StandardCharsets.UTF_8).length; }
}
