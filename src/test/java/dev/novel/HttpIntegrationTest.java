package dev.novel;

import dev.novel.support.Json;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.URI;
import java.net.http.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"spring.datasource.url=jdbc:h2:mem:http;DB_CLOSE_DELAY=-1","novel.engine-enabled=false"})
class HttpIntegrationTest {
    @LocalServerPort int port;
    @Autowired Json json;
    final HttpClient http=HttpClient.newHttpClient();
    private HttpResponse<String> request(String path,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(body!=null) builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        return http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void servesWorkspaceAndPublicStatusWithoutExposingSecrets() throws Exception {
        var document = org.jsoup.Jsoup.parse(request("/",null).body());
        assertThat(document.title()).contains("Novel Agent");
        assertThat(document.select("#root")).hasSize(1);
        String module = document.selectFirst("script[type=module]").attr("src");
        assertThat(module).startsWith("/assets/");
        assertThat(request(module,null).statusCode()).isEqualTo(200);
        var response=request("/api/status",null);assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("modelConfigured").doesNotContain("api-key","Authorization");
        assertThat(json.read(response.body()).path("retryModelSelection").asBoolean()).isTrue();
        assertThat(json.read(response.body()).path("skill").path("version").asText()).isEqualTo("2.0.1");
        assertThat(request("/api/skills",null).body()).contains("7d874e032d2de3dace74ae585cd2dbfbac2cde69");
        var providers=request("/api/providers",null);
        assertThat(providers.statusCode()).isEqualTo(200);
        assertThat(providers.body()).contains("defaultProvider","billingMode").doesNotContain("api-key","apiKey","endpoint");
    }
    @Test void createsNovelThroughRealHttpAndEnforcesApprovalState() throws Exception {
        var created=request("/api/novels","""
            {"title":"密信","premise":"追查一封密信的去向","bible":"保持人物知情范围","stageSize":2,"novelBudget":100,"chapterBudget":5}
            """);
        assertThat(created.statusCode()).isEqualTo(200);String id=json.read(created.body()).get("id").asText();
        var detail=request("/api/novels/"+id,null);assertThat(detail.body()).contains("PLANNING","PLAN");
        var summaries = json.read(request("/api/novels/summaries",null).body());
        var summary = java.util.stream.StreamSupport.stream(summaries.spliterator(),false)
            .filter(row -> row.path("id").asText().equals(id)).findFirst().orElseThrow();
        assertThat(summary.path("title").asText()).isEqualTo("密信");
        assertThat(summary.has("bible")).isFalse();
        assertThat(summary.has("premise")).isFalse();
        var before = json.read(request("/api/novels/"+id+"/snapshot",null).body());
        assertThat(before.path("event_cursor").asLong()).isPositive();
        assertThat(request("/api/novels/"+id+"/pause","{}").statusCode()).isEqualTo(200);
        var after = json.read(request("/api/novels/"+id+"/snapshot",null).body());
        assertThat(after.path("event_cursor").asLong()).isGreaterThan(before.path("event_cursor").asLong());
        assertThat(after.path("status").asText()).isEqualTo("PAUSED");
        assertThat(request("/api/novels/"+id+"/approve-plan","null").statusCode()).isEqualTo(409);
        assertThat(request("/api/novels/"+id+"/graph",null).statusCode()).isEqualTo(200);
        var truth=request("/api/novels/"+id+"/truth-files",null);
        assertThat(truth.statusCode()).isEqualTo(200);
        assertThat(truth.body()).contains("handoff_current.md","pending_hooks.md");
        var archive=request("/api/novels/"+id+"/truth-files/export",null);
        assertThat(archive.statusCode()).isEqualTo(200);
        assertThat(archive.headers().firstValue("content-type").orElseThrow()).contains("application/zip");
        assertThat(request("/api/novels/"+id+"/quality",null).statusCode()).isEqualTo(200);
    }
    @Test void validatesInputAndMissingRecords() throws Exception {
        assertThat(request("/api/novels","{}").statusCode()).isEqualTo(400);
        assertThat(request("/api/novels/missing",null).statusCode()).isEqualTo(404);
        assertThat(request("/api/research","{\"urls\":[]}").statusCode()).isEqualTo(400);
        assertThat(request("/api/research","{\"brief\":\"职业悬疑\",\"provider\":\"missing\",\"model\":\"any-model\"}").statusCode()).isEqualTo(400);
        assertThat(request("/api/research/missing",null).statusCode()).isEqualTo(404);
        assertThat(request("/api/research/missing/messages","{\"message\":\" \"}").statusCode()).isEqualTo(400);
        assertThat(request("/api/research","{\"brief\":\"职业悬疑，希望主角依靠修理技能调查旧案\"}").statusCode()).isEqualTo(200);
    }
    @Test void exposesPersistentObservationDashboardAndValidatesFeedback() throws Exception {
        var response=request("/api/observability",null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("summary","tasks","total","storage");
        assertThat(request("/api/observability?page=-1",null).statusCode()).isEqualTo(400);
        assertThat(request("/api/observability/tasks/missing",null).statusCode()).isEqualTo(404);
        assertThat(request("/api/observability/tasks/missing/progress",null).statusCode()).isEqualTo(404);
        assertThat(request("/api/observability/tasks/missing/feedback","{\"rating\":6,\"note\":\"评分无效\"}").statusCode()).isEqualTo(400);
    }
}
