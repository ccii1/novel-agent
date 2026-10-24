package dev.novel.integration.graph;

import dev.novel.config.Neo4jConfiguration.Connection;
import dev.novel.support.Json;
import jakarta.annotation.PostConstruct;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Session;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;
import java.util.function.Supplier;

@Component
@ConditionalOnProperty(name="novel.neo4j.enabled",havingValue="true")
public class Neo4jStoryGraph {
    private final Driver driver;
    private final SessionConfig session;
    private final String namespace;
    private final Json json;
    public Neo4jStoryGraph(Driver driver,Connection connection,Json json,
            @Value("${novel.neo4j.namespace:novel-agent}") String namespace) {
        this.driver=driver;this.session=SessionConfig.forDatabase(connection.database());this.namespace=namespace;this.json=json;
    }
    @PostConstruct void schema() {
        try(var connection=driver.session(session)) {
            connection.run("CREATE CONSTRAINT novel_graph_snapshot IF NOT EXISTS FOR (s:NovelGraphSnapshot) REQUIRE s.key IS UNIQUE").consume();
            connection.run("CREATE CONSTRAINT novel_graph_entity IF NOT EXISTS FOR (n:NovelGraphEntity) REQUIRE (n.scope,n.name) IS UNIQUE").consume();
        }
    }
    public Map<String,Object> query(String owner,String novel,long version,List<String> characters,int depth,
            Supplier<List<Map<String,Object>>> loader) {
        String scope=json.write(List.of(namespace,owner,novel,version));
        try(var connection=driver.session(session)) {
            var cached=read(connection,scope,version,characters,depth);
            if(cached.isPresent()) return cached.get();
                var edges=new ArrayList<Map<String,Object>>();
                for(var row:loader.get()) {
                    if(!"RELATION".equals(row.get("kind"))) continue;
                    var data=json.read((String)row.get("content"));
                    if(data.path("deleted").asBoolean(false)) continue;
                    edges.add(Map.of("subject",json.text(data,"subject"),"predicate",json.text(data,"predicate"),
                            "object",json.text(data,"object"),"chapter",row.get("chapter_no"),
                            "detail",data.path("detail").asText(),"evidence",data.path("evidence").asText()));
                }
                // 同一作品版本的投影和完成标记在一个 Neo4j 事务内提交；并发请求通过标记节点写锁串行化。
                connection.executeWrite(tx->{
                    var marker=tx.run("MERGE (s:NovelGraphSnapshot {key:$scope}) SET s.locked=true,s.namespace=$namespace RETURN coalesce(s.ready,false) AS ready",
                            Map.of("scope",scope,"namespace",namespace)).single();
                    if(!marker.get("ready").asBoolean()) {
                        tx.run("""
                                UNWIND $edges AS edge
                                MERGE (a:NovelGraphEntity {scope:$scope,name:edge.subject})
                                MERGE (b:NovelGraphEntity {scope:$scope,name:edge.object})
                                SET a.namespace=$namespace,b.namespace=$namespace
                                CREATE (a)-[:STORY_RELATION {predicate:edge.predicate,chapter:edge.chapter,
                                  detail:edge.detail,evidence:edge.evidence}]->(b)
                                """,Map.of("scope",scope,"edges",edges,"namespace",namespace)).consume();
                        tx.run("MATCH (s:NovelGraphSnapshot {key:$scope}) SET s.ready=true",Map.of("scope",scope)).consume();
                    }
                    return null;
                });
            return read(connection,scope,version,characters,depth).orElseThrow(()->new IllegalStateException("Neo4j 关系投影未完成发布"));
        }
    }
    private Optional<Map<String,Object>> read(Session connection,String scope,long version,List<String> characters,int depth) {
        String selection=characters.isEmpty()?"MATCH (n:NovelGraphEntity {scope:$scope}) ":
                "MATCH (start:NovelGraphEntity {scope:$scope}) WHERE start.name IN $characters "+
                "MATCH p=(start)-[:STORY_RELATION*0.."+depth+"]-(n:NovelGraphEntity) "+
                "WHERE all(entity IN nodes(p) WHERE entity.scope=$scope) ";
        String query="MATCH (s:NovelGraphSnapshot {key:$scope,ready:true}) CALL { "+selection+
                "WITH DISTINCT n.name AS name ORDER BY name RETURN collect(name) AS names } "+"""
                CALL { WITH names
                  UNWIND names AS name
                  MATCH (a:NovelGraphEntity {scope:$scope,name:name})-[r:STORY_RELATION]->(b:NovelGraphEntity {scope:$scope})
                  WHERE b.name IN names
                  WITH a,r,b ORDER BY a.name,r.predicate,b.name
                  RETURN collect({subject:a.name,predicate:r.predicate,object:b.name,
                    chapter:r.chapter,detail:r.detail,evidence:r.evidence}) AS edges
                } RETURN names,edges
                """;
        return connection.executeRead(tx->tx.run(query,Map.of("scope",scope,"characters",characters)).list(record->{
            var nodes=record.get("names").asList(value->Map.of("id",value.asString(),"name",value.asString()));
            return Map.<String,Object>of("version",version,"nodes",nodes,"edges",record.get("edges").asList(value->value.asMap()));
        }).stream().findFirst());
    }
}
