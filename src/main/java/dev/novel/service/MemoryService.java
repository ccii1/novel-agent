package dev.novel.service;

import dev.novel.repository.Store;
import dev.novel.support.Json;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.tinkerpop.gremlin.structure.*;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class MemoryService {
    private final Store store;
    private final Json json;
    public MemoryService(Store store, Json json) { this.store=store; this.json=json; }
    public List<Map<String,Object>> snapshot(String novel, long version) {
        return store.list("memories.snapshot", novel,version,version);
    }
    public Map<String,Object> graph(String novel, long version, List<String> characters, int depth) {
        if (depth<1 || depth>3) throw new IllegalArgumentException("graph 查询深度必须为1至3");
        TinkerGraph graph = TinkerGraph.open();
        Map<String,Vertex> vertices = new LinkedHashMap<>();
        for (var row : snapshot(novel,version)) {
            if (!"RELATION".equals(row.get("kind"))) continue;
            var data = json.read((String)row.get("content"));
            if (data.path("deleted").asBoolean(false)) continue;
            String from=json.text(data,"subject"), to=json.text(data,"object"), predicate=json.text(data,"predicate");
            Vertex source=vertices.computeIfAbsent(from,n->graph.addVertex(T.id,n,"name",n));
            Vertex target=vertices.computeIfAbsent(to,n->graph.addVertex(T.id,n,"name",n));
            source.addEdge(predicate,target,"chapter",row.get("chapter_no"),"evidence",data.path("evidence").asText(),"detail",data.path("detail").asText());
        }
        Set<Object> selected = new LinkedHashSet<>();
        if (characters.isEmpty()) selected.addAll(vertices.keySet());
        else {
            var traversal=graph.traversal();
            for (String character : characters) {
                if (!vertices.containsKey(character)) continue;
                selected.add(character);
                selected.addAll(traversal.V(character).repeat(__.both().simplePath()).emit().times(depth).dedup().id().toList());
            }
        }
        List<Map<String,Object>> nodes=new ArrayList<>(), edges=new ArrayList<>();
        for (Object id : selected) nodes.add(Map.of("id",id,"name",id));
        graph.edges().forEachRemaining(edge -> {
            if (selected.contains(edge.outVertex().id()) && selected.contains(edge.inVertex().id())) edges.add(Map.of(
                    "subject",edge.outVertex().id(),"predicate",edge.label(),"object",edge.inVertex().id(),
                    "chapter",edge.value("chapter"),"detail",edge.value("detail"),"evidence",edge.value("evidence")));
        });
        return Map.of("version",version,"nodes",nodes,"edges",edges);
    }
    public String context(Map<String,Object> novel, int chapter) {
        String id=(String)novel.get("id"); long version=Store.number(novel,"memory_version");
        var plan=Store.string(novel,"plan").isBlank()?json.read("{}"):json.read(Store.string(novel,"plan"));
        List<String> characters=new ArrayList<>();
        plan.path("chapters").forEach(item -> {
            if (item.path("number").asInt()==chapter) item.path("characters").forEach(c->characters.add(c.asText()));
        });
        List<Map<String,Object>> relevant=new ArrayList<>();
        var relatedGraph=graph(id,version,characters,2);
        String terms=json.write(relatedGraph.get("nodes"));
        Set<String> requestedKeys=new HashSet<>();
        plan.path("chapters").forEach(item->{if(item.path("number").asInt()==chapter) item.path("foreshadowIds").forEach(key->requestedKeys.add(key.asText()));});
        List<Map.Entry<Integer,Map<String,Object>>> ranked=new ArrayList<>();
        for (var row : snapshot(id,version)) {
            var data=json.read((String)row.get("content"));
            if (data.path("deleted").asBoolean(false) || "RELATION".equals(row.get("kind"))) continue;
            int score=0;
            if("WORLD".equals(row.get("kind"))) score=30;
            if("FORESHADOW".equals(row.get("kind")) && data.path("active").asBoolean(false)) score=50;
            if("FORESHADOW".equals(row.get("kind")) && data.path("dueChapter").asInt(Integer.MAX_VALUE)<=chapter+Store.integer(novel,"stage_size")) score=90;
            for(var name:characters) if(data.toString().contains(name)) score=80;
            if(terms.contains(data.path("name").asText("\u0000"))) score=85;
            if(requestedKeys.contains(row.get("memory_key"))) score=100;
            if(Store.integer(row,"chapter_no")>=chapter-4) score=Math.max(score,40);
            if(score>0) ranked.add(Map.entry(score,Map.of("key",row.get("memory_key"),"kind",row.get("kind"),"chapter",row.get("chapter_no"),"data",data)));
        }
        ranked.sort(Map.Entry.<Integer,Map<String,Object>>comparingByKey().reversed());
        ranked.stream().limit(50).forEach(entry->relevant.add(entry.getValue()));
        var summaries=store.list("chapters.summaries",id,chapter,chapter%10==0?9:4);
        var previous=store.list("chapters.previous",id,chapter-1);
        String tail=previous.isEmpty()?"":(String)previous.get(0).get("body");
        if(tail.length()>4000) tail=tail.substring(tail.length()-4000);
        Map<String,Object> context=new LinkedHashMap<>();
        context.put("title",novel.get("title"));context.put("premise",novel.get("premise"));context.put("bible",novel.get("bible"));
        context.put("approvedPlan",plan);context.put("chapter",chapter);context.put("memoryVersion",version);
        context.put("facts",relevant);context.put("relationships",relatedGraph);context.put("recentSummaries",summaries);
        context.put("previousEnding",tail);context.put("omittedMatchingFacts",Math.max(0,ranked.size()-50));
        return json.write(context);
    }
    public Object planningContext(Map<String,Object> novel) {
        var snapshot=snapshot((String)novel.get("id"),Store.number(novel,"memory_version"));
        Map<String,Object> result=new LinkedHashMap<>();
        for(String kind:List.of("WORLD","CHARACTER","FORESHADOW","EVENT")) {
            var selected=snapshot.stream().filter(row->kind.equals(row.get("kind")))
                    .filter(row->!json.read((String)row.get("content")).path("deleted").asBoolean(false))
                    .filter(row->!kind.equals("FORESHADOW") || json.read((String)row.get("content")).path("active").asBoolean(false))
                    .limit(kind.equals("FORESHADOW")?30:15)
                    .map(row->Map.of("key",row.get("memory_key"),"chapter",row.get("chapter_no"),"data",json.read((String)row.get("content")))).toList();
            result.put(kind,selected);
        }
        result.put("retainedMemoryCount",snapshot.size());return result;
    }
    public void validateDelta(JsonNode delta, String body) {
        json.text(delta,"summary");
        if (!delta.path("facts").isArray() || !delta.path("relations").isArray()) throw new IllegalArgumentException("记忆变更必须包含 facts 与 relations 数组");
        if(delta.get("facts").size()+delta.get("relations").size()>40) throw new IllegalArgumentException("单章记忆变更最多40条");
        Set<String> keys=new HashSet<>();
        for(var fact:delta.get("facts")) {
            if(!Set.of("WORLD","CHARACTER","EVENT","FORESHADOW").contains(json.text(fact,"kind"))) throw new IllegalArgumentException("记忆种类无效");
            String key=json.text(fact,"key");
            if(key.length()>350 || !keys.add(key)) throw new IllegalArgumentException("记忆 key 重复或过长");
            json.text(fact,"name"); evidence(fact,body);
        }
        for(var relation:delta.get("relations")) {
            String key=relationKey(relation);
            if(key.length()>350 || !keys.add(key)) throw new IllegalArgumentException("关系 key 重复或过长");
            evidence(relation,body);
        }
    }
    private void evidence(JsonNode item,String body) {
        String evidence=json.text(item,"evidence");
        if(evidence.length()<4 || !body.contains(evidence)) throw new IllegalArgumentException("记忆证据必须引用正文中至少4个字符的原文");
    }
    private String relationKey(JsonNode relation) {
        return "relation:"+json.text(relation,"subject")+":"+json.text(relation,"predicate")+":"+json.text(relation,"object");
    }
    public void commit(String novel,int chapter,long version,JsonNode delta) {
        for(var fact:delta.path("facts")) save(novel,chapter,version,json.text(fact,"key"),json.text(fact,"kind"),fact);
        for(var relation:delta.path("relations")) save(novel,chapter,version,relationKey(relation),"RELATION",relation);
    }
    private void save(String novel,int chapter,long version,String key,String kind,JsonNode content) {
        store.update("memories.create",
                UUID.randomUUID().toString(),novel,key,kind,chapter,version,json.write(content));
    }
}
