package dev.novel;

import dev.novel.config.Neo4jConfiguration.Connection;
import dev.novel.integration.graph.StoryGraph;
import dev.novel.service.MemoryFileService;
import dev.novel.service.MemoryService;
import dev.novel.service.ProjectService;
import dev.novel.support.Json;
import dev.novel.support.MemoryMarkdown;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.neo4j.driver.Driver;
import org.neo4j.driver.SessionConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="NOVEL_NEO4J_TEST",matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:neo4j-tests;DB_CLOSE_DELAY=-1",
        "novel.engine-enabled=false","novel.redis.enabled=false","novel.neo4j.enabled=true",
        "novel.memory-directory=./.work/neo4j-memory-tests"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Neo4jIntegrationTest {
    private static final String NAMESPACE="novel-neo4j-test-"+UUID.randomUUID();
    @DynamicPropertySource static void configuration(DynamicPropertyRegistry registry) {
        registry.add("novel.neo4j.namespace",()->NAMESPACE);
    }
    @Autowired MemoryService memory;
    @Autowired MemoryFileService files;
    @Autowired MemoryMarkdown markdown;
    @Autowired ProjectService projects;
    @Autowired StoryGraph graph;
    @Autowired Json json;
    @Autowired Driver driver;
    @Autowired Connection connection;
    @Autowired TestDatabase store;
    @LocalServerPort int port;

    private String novel() { return projects.create("关系查询验证","调查密信","保持人物知情范围",1,BigDecimal.TEN,BigDecimal.ONE); }
    private com.fasterxml.jackson.databind.JsonNode delta(boolean deleted) {
        return json.tree(Map.of("summary","关系变化","facts",List.of(),"relations",List.of(
                Map.of("subject","林舟","predicate","TRUSTS","object","沈月","detail","交出钥匙","deleted",deleted,"evidence","林舟交出钥匙"),
                Map.of("subject","沈月","predicate","KNOWS","object","密信","detail","知道内容","deleted",false,"evidence","沈月知道密信"))));
    }
    @AfterAll void cleanGraph() {
        try(var session=driver.session(SessionConfig.forDatabase(connection.database()))) {
            session.run("MATCH (n) WHERE (n:NovelGraphEntity OR n:NovelGraphSnapshot) AND n.namespace=$namespace DETACH DELETE n",
                    Map.of("namespace",NAMESPACE)).consume();
        }
    }
    @Test void queriesKeepVersionsNovelsOwnersAndDepthIndependent() {
        String first=novel(),second=novel();
        memory.commit(first,1,1,delta(false));memory.commit(first,2,2,delta(true));
        assertThat(graph.mode()).isEqualTo("neo4j");
        assertThat((List<?>)memory.graph(first,1,List.of("林舟"),1).get("edges")).hasSize(1);
        assertThat((List<?>)memory.graph(first,1,List.of("林舟"),2).get("edges")).hasSize(2);
        assertThat((List<?>)memory.graph(first,2,List.of("林舟"),2).get("edges")).isEmpty();
        assertThat((List<?>)memory.graph(first,2,List.of(),2).get("edges")).hasSize(1);
        assertThat((List<?>)memory.graph(second,0,List.of(),2).get("edges")).isEmpty();
        assertThat((List<?>)graph.query("different-owner",first,1,List.of(),2,List::of).get("edges")).isEmpty();
        assertThatThrownBy(()->memory.graph(first,1,List.of(),4)).isInstanceOf(IllegalArgumentException.class);
        try {
            dev.novel.security.UserScope.enter("different-owner");
            assertThatThrownBy(()->memory.graph(first,1,List.of(),2)).isInstanceOfAny(NoSuchElementException.class,IllegalStateException.class);
        } finally { dev.novel.security.UserScope.clear(); }
    }
    @Test void nativeMarkdownRelationshipsEnterGraphAndKeepHistoricalState() {
        String id=novel();
        String body="```story-relations\n"+json.write(delta(false).path("relations"))+"\n```";
        String content=markdown.create("relation-trust","relation","信任与知情",List.of(),body);
        memory.commit(id,1,1,json.tree(Map.of("summary","交出钥匙","files",List.of(Map.of("path","relations/trust.md",
                "content",content,"expectedHash","","description","更新关系","evidencePaths",List.of("/paragraphs/p0"))))));
        assertThat((List<?>)memory.graph(id,1,List.of("林舟"),2).get("edges")).hasSize(2);
        String conflict=markdown.create("relation-conflict","relation","重复关系",List.of(),body);
        assertThatThrownBy(()->files.prepare(id,2,0,2,json.tree(Map.of("summary","重复关系","files",List.of(Map.of(
                "path","relations/conflict.md","content",conflict,"expectedHash","","description","重复","evidencePaths",List.of("/paragraphs/p0")))))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("关系重复");
        String next=markdown.create("relation-trust","relation","信任与知情",List.of(),"```story-relations\n"+json.write(delta(true).path("relations"))+"\n```");
        memory.commit(id,2,2,json.tree(Map.of("summary","关系解除","files",List.of(Map.of("path","relations/trust.md",
                "content",next,"expectedHash",files.hash(id,1,"relations/trust.md"),"description","关系解除","evidencePaths",List.of("/paragraphs/p0"))))));
        assertThat((List<?>)memory.graph(id,2,List.of("林舟"),2).get("edges")).isEmpty();
        assertThat((List<?>)memory.graph(id,1,List.of("林舟"),2).get("edges")).hasSize(2);
    }
    @Test void concurrentProjectionIsAtomicAndWarmQueriesDoNotReadMarkdownAgain() throws Exception {
        String id=UUID.randomUUID().toString();
        var facts=new ArrayList<Map<String,Object>>();
        for(var relation:delta(false).path("relations")) facts.add(Map.of("kind","RELATION","chapter_no",1,"content",json.write(relation)));
        var executor=Executors.newFixedThreadPool(6);
        try {
            var jobs=new ArrayList<Callable<Map<String,Object>>>();
            for(int i=0;i<6;i++) jobs.add(()->graph.query("owner",id,1,List.of("林舟"),2,()->facts));
            for(var result:executor.invokeAll(jobs)) assertThat((List<?>)result.get().get("edges")).hasSize(2);
        } finally { executor.shutdownNow(); }
        var warm=graph.query("owner",id,1,List.of("林舟"),2,()->{throw new AssertionError("已发布投影不应重新读取 Markdown");});
        assertThat((List<?>)warm.get("edges")).hasSize(2);
    }
    @Test void malformedNativeRelationsFailBeforePublication() {
        String content=markdown.create("bad-relation","relation","关系格式验证",List.of(),"```story-relations\n[{\"subject\":\"林舟\"}]\n```");
        assertThatThrownBy(()->markdown.parse(content)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("predicate");
        String wrongType=markdown.create("world-relation","world","规则",List.of(),"```story-relations\n"+json.write(delta(false).path("relations"))+"\n```");
        assertThatThrownBy(()->markdown.parse(wrongType)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("人物或关系");
    }
    @Test void authenticatedGraphHttpEndpointUsesNeo4j() throws Exception {
        String id=novel();memory.commit(id,1,1,delta(false));
        store.update("UPDATE novels SET memory_version=1 WHERE id=?",id);
        var http=AuthHttp.owner(port);
        var status=http.request("/api/status",null);
        assertThat(status.statusCode()).isEqualTo(200);
        assertThat(json.read(status.body()).path("graphDatabase").asText()).isEqualTo("neo4j");
        var response=http.request("/api/novels/"+id+"/graph?depth=2",null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json.read(response.body()).path("edges")).hasSize(2);
    }
    @Test void queryResultsMatchTinkerGraphAndRecordWarmQueryCost() {
        var facts=new ArrayList<Map<String,Object>>();
        for(int i=0;i<2000;i++) facts.add(Map.of("kind","RELATION","chapter_no",i+1,"content",json.write(Map.of(
                "subject","人物"+i,"predicate","KNOWS","object","人物"+(i+1),"detail","相识","evidence","章节记录","deleted",false))));
        String id=UUID.randomUUID().toString();var characters=List.of("人物1000");
        var expected=graph.query(facts,1,characters,2);
        var actual=graph.query("owner",id,1,characters,2,()->facts);
        for(String field:List.of("nodes","edges")) {
            var expectedItems=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            var actualItems=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
            json.read(json.write(expected)).path(field).forEach(expectedItems::add);
            json.read(json.write(actual)).path(field).forEach(actualItems::add);
            assertThat(actualItems).containsExactlyInAnyOrderElementsOf(expectedItems);
        }
        long local=System.nanoTime();
        for(int i=0;i<30;i++) graph.query(facts,1,characters,2);
        local=System.nanoTime()-local;
        long remote=System.nanoTime();
        for(int i=0;i<30;i++) graph.query("owner",id,1,characters,2,()->facts);
        remote=System.nanoTime()-remote;
        System.out.printf(Locale.ROOT,"Graph timing: 2000 relationships, 30 queries; TinkerGraph %.2f ms, Neo4j %.2f ms%n",local/1e6,remote/1e6);
    }
}
