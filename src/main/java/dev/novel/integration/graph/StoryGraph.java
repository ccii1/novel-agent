package dev.novel.integration.graph;

import dev.novel.support.Json;
import org.apache.tinkerpop.gremlin.structure.*;
import org.apache.tinkerpop.gremlin.tinkergraph.structure.TinkerGraph;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.__;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;

// 关系查询按配置选择服务，版本数据仅在需要创建图投影时读取。
@Component
public class StoryGraph {
    private final Json json;
    private final Neo4jStoryGraph neo4j;
    public StoryGraph(Json json,ObjectProvider<Neo4jStoryGraph> neo4j) { this.json=json;this.neo4j=neo4j.getIfAvailable(); }
    public String mode() { return neo4j==null?"tinkergraph":"neo4j"; }
    public Map<String,Object> query(String owner,String novel,long version,List<String> characters,int depth,Supplier<List<Map<String,Object>>> facts) {
        if(depth<1 || depth>3) throw new IllegalArgumentException("graph 查询深度必须为1至3");
        return neo4j==null?query(facts.get(),version,characters,depth):neo4j.query(owner,novel,version,characters,depth,facts);
    }

    public Map<String,Object> query(List<Map<String,Object>> facts,long version,List<String> characters,int depth) {
        if(depth<1 || depth>3) throw new IllegalArgumentException("graph 查询深度必须为1至3");
        try(var graph=TinkerGraph.open()) {
            Map<String,Vertex> vertices=new LinkedHashMap<>();
            for(var row:facts) {
                if(!"RELATION".equals(row.get("kind"))) continue;
                var data=json.read((String)row.get("content"));
                if(data.path("deleted").asBoolean(false)) continue;
                String from=json.text(data,"subject"),to=json.text(data,"object"),predicate=json.text(data,"predicate");
                Vertex source=vertices.computeIfAbsent(from,name->graph.addVertex(T.id,name,"name",name));
                Vertex target=vertices.computeIfAbsent(to,name->graph.addVertex(T.id,name,"name",name));
                source.addEdge(predicate,target,"chapter",row.get("chapter_no"),"evidence",data.path("evidence").asText(),"detail",data.path("detail").asText());
            }
            Set<Object> selected=new LinkedHashSet<>();
            if(characters.isEmpty()) selected.addAll(vertices.keySet());
            else {
                var traversal=graph.traversal();
                for(String character:characters) {
                    if(!vertices.containsKey(character)) continue;
                    selected.add(character);
                    // 同时遍历关系两端，simplePath 限制单条路径重复经过同一人物。
                    selected.addAll(traversal.V(character).repeat(__.both().simplePath()).emit().times(depth).dedup().id().toList());
                }
            }
            List<Map<String,Object>> nodes=new ArrayList<>(),edges=new ArrayList<>();
            for(Object id:selected) nodes.add(Map.of("id",id,"name",id));
            graph.edges().forEachRemaining(edge->{
                if(selected.contains(edge.outVertex().id()) && selected.contains(edge.inVertex().id())) edges.add(Map.of(
                    "subject",edge.outVertex().id(),"predicate",edge.label(),"object",edge.inVertex().id(),
                    "chapter",edge.value("chapter"),"detail",edge.value("detail"),"evidence",edge.value("evidence")));
            });
            // 返回普通数据后即可关闭本次查询的图，缓存不持有 Vertex 或 Edge。
            return Map.of("version",version,"nodes",nodes,"edges",edges);
        }
    }
}
