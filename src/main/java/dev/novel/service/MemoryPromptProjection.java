package dev.novel.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.novel.support.Json;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.util.*;

// 提示只携带事实内容和来源位置；证据原文继续保存在正式记忆中。
@Component
public class MemoryPromptProjection {
    private final Json json;
    public MemoryPromptProjection(Json json) { this.json=json; }

    public Map<String,Object> fact(Map<String,Object> row) {
        ObjectNode data=(ObjectNode)json.tree(row.get("data")).deepCopy();
        if(data.has("evidence")) {
            data.remove("evidence");
            data.put("evidenceAvailable",true);
        }
        return Map.of("key",row.get("key"),"kind",row.get("kind"),"chapter",row.get("chapter"),"data",data);
    }

    public Map<String,Object> graph(Map<String,Object> graph) {
        var result=new LinkedHashMap<String,Object>(graph);
        var edges=new ArrayList<Map<String,Object>>();
        for(var edge:json.tree(graph.get("edges"))) {
            var projected=new LinkedHashMap<String,Object>();
            edge.fields().forEachRemaining(field -> {
                if(!field.getKey().equals("evidence")) projected.put(field.getKey(),field.getValue());
            });
            edges.add(projected);
        }
        result.put("edges",edges);
        return result;
    }

    public int bytes(Object value) { return json.write(value).getBytes(StandardCharsets.UTF_8).length; }
}
