package dev.novel;

import dev.novel.repository.Store;
import dev.novel.service.*;
import dev.novel.security.UserScope;
import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.datasource.url=jdbc:h2:mem:user-isolation;DB_CLOSE_DELAY=-1","novel.engine-enabled=false"})
class UserIsolationIntegrationTest {
    @LocalServerPort int port;
    @Autowired Store store;
    @Autowired TestDatabase db;
    @Autowired Json json;
    @Autowired ProjectService projects;
    @Autowired ResearchService research;
    @Autowired BudgetService budget;
    @Autowired dev.novel.agent.AgentContextService context;

    private AuthHttp account() throws Exception {
        var http=new AuthHttp(port);
        String name="user_"+UUID.randomUUID().toString().replace("-","").substring(0,20);
        assertThat(http.request("/api/auth/register",json.write(Map.of("username",name,"password","Valid-password-123"))).statusCode()).isEqualTo(200);
        http.login(name,"Valid-password-123");return http;
    }
    private String create(AuthHttp http) throws Exception {
        var response=http.request("/api/novels",json.write(Map.of("title","隔离验证","premise","追查失踪信件","bible","人物保留信息边界","stageSize",1,"novelBudget",10,"chapterBudget",1)));
        assertThat(response.statusCode()).isEqualTo(200);return json.read(response.body()).path("id").asText();
    }
    @Test void anonymousRequestsAndMissingCsrfAreRejected() throws Exception {
        var http=new AuthHttp(port);
        for(String path:List.of("/api/novels","/api/budget","/api/observability","/api/research","/api/status","/api/auth/me"))
            assertThat(http.request(path,null).statusCode()).as(path).isEqualTo(401);
        var response=http.client.send(HttpRequest.newBuilder(URI.create(http.base+"/api/auth/register"))
            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{} ")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
    }
    @Test void registrationHashesPasswordsAndRejectsDuplicateReservedOrInvalidNames() throws Exception {
        var http=account();String name=json.read(http.request("/api/auth/me",null).body()).path("username").asText();
        assertThat(db.one("SELECT password_hash FROM app_users WHERE username=?",name).get("password_hash").toString()).startsWith("$2").doesNotContain("Valid-password");
        assertThat(http.request("/api/auth/register",json.write(Map.of("username",name,"password","Valid-password-123"))).statusCode()).isEqualTo(400);
        assertThat(http.request("/api/auth/register","{\"username\":\"owner\",\"password\":\"Valid-password-123\"}").statusCode()).isEqualTo(400);
        assertThat(http.request("/api/auth/register","{\"username\":\"BAD\",\"password\":\"short\"}").statusCode()).isEqualTo(400);
        assertThat(http.request("/api/auth/logout","{}").statusCode()).isEqualTo(200);
        assertThat(http.request("/api/auth/me",null).statusCode()).isEqualTo(401);
    }
    @Test void usersCannotReadOrMutateOtherNovelsTasksCallsResearchAndEdits() throws Exception {
        var first=account();var second=account();String novel=create(first);String other=create(second);
        String task=Store.string(store.one("tasks.byNovel",novel),"id");
        String user=Store.string(store.novel(novel),"user_id");
        assertThat(store.one("tasks.find",task).get("user_id")).isEqualTo(user);
        String call=UUID.randomUUID().toString();
        budget.reserve(store.one("tasks.find",task),new BigDecimal("0.25"),"billing-validation",BigDecimal.ONE,BigDecimal.ONE,null,null,"default","metered","validation",call);
        db.update("UPDATE model_calls SET status='UNCERTAIN' WHERE id=?",call);
        String report=UUID.randomUUID().toString();
        db.update("INSERT INTO research(id,task_id,sources,ideas,brief,user_id) VALUES(?,?, '[]','[]','隔离验证',?)",report,task,user);
        String edit=UUID.randomUUID().toString();
        store.update("edits.create",edit,novel,1,0,"修改验证","{} ");
        for(String path:List.of("/api/novels/"+novel,"/api/novels/"+novel+"/snapshot","/api/novels/"+novel+"/chapters/1",
                "/api/novels/"+novel+"/graph","/api/novels/"+novel+"/memories","/api/novels/"+novel+"/export",
                "/api/novels/"+novel+"/truth-files/export","/api/novels/"+novel+"/plan-changes","/api/novels/"+novel+"/coordination",
                "/api/observability/tasks/"+task,"/api/observability/tasks/"+task+"/progress","/api/calls/"+call,
                "/api/research/"+report,"/api/agents/metrics?novelId="+novel,"/api/observability?novelId="+novel))
            assertThat(second.request(path,null).statusCode()).as(path).isEqualTo(404);
        for(String path:List.of("/api/novels/"+novel+"/pause","/api/tasks/"+task+"/retry","/api/calls/"+call+"/reconcile",
                "/api/edits/"+edit+"/approve","/api/research/"+report+"/messages","/api/observability/tasks/"+task+"/feedback"))
            assertThat(second.request(path,"{}").statusCode()).as(path).isEqualTo(404);
        assertThat(first.request("/api/novels/"+novel,null).statusCode()).isEqualTo(200);
        assertThat(second.request("/api/novels/summaries",null).body()).contains(other).doesNotContain(novel);
        assertThat(second.request("/api/novels",null).body()).doesNotContain(novel);
        assertThat(second.request("/api/observability",null).body()).doesNotContain(task);
        assertThat(second.request("/api/budget",null).body()).doesNotContain(call);
        assertThat(json.read(second.request("/api/budget",null).body()).path("usedAndReserved").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(second.request("/api/research",null).body()).doesNotContain(report);
        assertThat(first.request("/api/research",null).body()).contains(report);
    }
    @Test void backgroundTasksRetainOwnerAndRejectCrossOwnerTools() throws Exception {
        var http=account();String novel=create(http);String owner=Store.string(store.novel(novel),"user_id");
        UserScope.enter(owner);
        String researchTask;
        try {
            researchTask=research.create(List.of(),"职业悬疑");
            assertThat(store.one("tasks.find",researchTask).get("user_id")).isEqualTo(owner);
        } finally { UserScope.clear(); }
        var task=new HashMap<>(store.one("tasks.byNovel",novel));task.put("user_id","different_user");
        assertThatThrownBy(()->context.novel(task)).hasMessageContaining("所属用户");
        UserScope.enter("different_user");
        try {assertThatThrownBy(()->store.novel(novel)).isInstanceOf(NoSuchElementException.class);}
        finally {UserScope.clear();}
        assertThat(UserScope.current()).isNull();
    }
}
