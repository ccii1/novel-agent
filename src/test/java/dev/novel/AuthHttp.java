package dev.novel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;

final class AuthHttp {
    final HttpClient client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build();
    final String base;
    final ObjectMapper json=new ObjectMapper();
    AuthHttp(int port) { base="http://127.0.0.1:"+port; }
    static AuthHttp owner(int port) throws Exception {
        var http=new AuthHttp(port);
        var credentials=http.json.readTree(Files.readString(Path.of("config/auth-local.json")));
        http.login("owner",credentials.path("password").asText());return http;
    }
    HttpResponse<String> request(String path,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base+path));
        if(body!=null) request.header("Content-Type","application/json").header("X-XSRF-TOKEN",csrf()).POST(HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    String csrf() throws Exception {
        var response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/csrf")).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200) throw new IllegalStateException("CSRF 获取失败");
        return json.readTree(response.body()).path("token").asText();
    }
    void login(String username,String password) throws Exception {
        var response=client.send(HttpRequest.newBuilder(URI.create(base+"/api/auth/login"))
                .header("Content-Type","application/x-www-form-urlencoded").header("X-XSRF-TOKEN",csrf())
                .POST(HttpRequest.BodyPublishers.ofString("username="+URLEncoder.encode(username,java.nio.charset.StandardCharsets.UTF_8)
                    +"&password="+URLEncoder.encode(password,java.nio.charset.StandardCharsets.UTF_8))).build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200) throw new IllegalStateException("测试账号登录失败："+response.statusCode());
    }
}
