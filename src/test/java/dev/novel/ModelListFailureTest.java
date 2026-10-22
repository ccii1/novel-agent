package dev.novel;

import dev.novel.support.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.ServerSocket;
import java.io.IOException;
import java.io.UncheckedIOException;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "spring.datasource.url=jdbc:h2:mem:model-list-failure;DB_CLOSE_DELAY=-1","novel.engine-enabled=false",
        "novel.providers.unavailable.api-key=configuration-for-network-validation",
        "novel.providers.unavailable.name=text-model","novel.providers.unavailable.billing-mode=unmetered"})
class ModelListFailureTest {
    private static final int CLOSED_PORT=closedPort();
    @DynamicPropertySource static void endpoint(DynamicPropertyRegistry properties) {
        properties.add("novel.providers.unavailable.endpoint",()->"http://127.0.0.1:"+CLOSED_PORT+"/v1");
    }
    private static int closedPort() {
        try(var socket=new ServerSocket(0)) { return socket.getLocalPort(); }
        catch(IOException error) { throw new UncheckedIOException(error); }
    }
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired Json json;
    @Test void closedServiceReturnsActionableErrorWithoutCredentials() throws Exception {
        var response=AuthHttp.owner(port).request("/api/providers/unavailable/models",null);
        assertThat(response.statusCode()).isEqualTo(502);
        var body=json.read(response.body());
        assertThat(body.path("provider").asText()).isEqualTo("unavailable");
        assertThat(body.path("errorCode").asText()).isEqualTo("MODEL_SERVICE_CONNECT");
        assertThat(body.path("error").asText()).contains("无法连接模型服务","127.0.0.1:"+CLOSED_PORT,"服务已启动");
        assertThat(response.body()).doesNotContain("configuration-for-network-validation","Authorization","Bearer","ClosedChannelException");
    }
    @Test void unknownProviderRemainsInputError() throws Exception {
        assertThat(AuthHttp.owner(port).request("/api/providers/missing/models",null).statusCode()).isEqualTo(400);
    }
}
