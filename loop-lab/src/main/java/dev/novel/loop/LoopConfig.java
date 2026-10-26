package dev.novel.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Set;

public record LoopConfig(URI endpoint, String apiKey) {
    public LoopConfig {
        if (!"http".equals(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null
                || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !Set.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost())) {
            throw new IllegalArgumentException("学习入口需要本机 HTTP 模型地址");
        }
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("${")) {
            throw new IllegalArgumentException("本机模型凭据需要明确配置");
        }
    }

    public static LoopConfig load(Path file) throws IOException {
        JsonNode local = new ObjectMapper(new YAMLFactory()).readTree(file.toFile())
                .path("novel").path("providers").path("local");
        if (!"unmetered".equals(local.path("billing-mode").asText())) {
            throw new IllegalArgumentException("学习入口只接受 unmetered 本机服务");
        }
        URI base = URI.create(local.path("endpoint").asText());
        String path = base.getPath().replaceAll("/+$", "");
        if (!path.endsWith("/chat/completions")) path += "/chat/completions";
        if (base.getRawQuery() != null || base.getRawFragment() != null || base.getUserInfo() != null) {
            throw new IllegalArgumentException("模型地址不能包含凭据、查询参数或片段");
        }
        return new LoopConfig(base.resolve(path), local.path("api-key").asText());
    }

    @Override
    public String toString() {
        return "LoopConfig[凭据隐藏]";
    }
}
