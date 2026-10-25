package dev.novel.integration.model;

import dev.novel.repository.Store;
import dev.novel.support.Json;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.http.*;
import java.net.ConnectException;
import java.io.IOException;
import java.time.Duration;
import java.util.*;

@Service
public class ModelCatalog {
    private final Environment environment;
    private final Store store;
    private final Json json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public record Profile(String endpoint, String apiKey, String name, String billingMode,
                          BigDecimal inputPerMillion, BigDecimal outputPerMillion, String displayName,
                          List<String> reasoningEfforts, String reasoningEffort) {
    }

    public record Selection(String provider, String endpoint, String apiKey, String model, String billingMode,
                            BigDecimal inputPrice, BigDecimal outputPrice, String reasoningEffort) {
        public boolean ready() {
            return !endpoint.isBlank() && !apiKey.isBlank() && !model.isBlank() &&
                    (billingMode.equals("unmetered") || (inputPrice.signum() > 0 && outputPrice.signum() > 0));
        }
    }

    public ModelCatalog(Environment environment, Store store, Json json) {
        this.environment = environment;
        this.store = store;
        this.json = json;
    }

    public String defaultProvider() {
        return environment.getProperty("novel.default-provider", "default");
    }

    private Map<String, Profile> profiles() {
        return Binder.get(environment).bind("novel.providers", Bindable.mapOf(String.class, Profile.class)).orElse(Map.of());
    }

    public Selection resolve(String provider, String chosenModel, String role) {
        return resolve(provider, chosenModel, role, null);
    }

    public Selection resolve(String provider, String chosenModel, String role, String chosenEffort) {
        provider = provider == null || provider.isBlank() ? defaultProvider() : provider;
        String prefix;
        String endpoint, key, name, mode, defaultEffort;
        BigDecimal input, output;
        List<String> efforts;
        if (provider.equals("default")) {
            prefix = "novel.agents." + role.toLowerCase(Locale.ROOT).replace('_', '-') + ".";
            endpoint = property(prefix, "endpoint", environment.getProperty("novel.model.endpoint", ""));
            key = property(prefix, "api-key", environment.getProperty("novel.model.api-key", ""));
            name = property(prefix, "name", environment.getProperty("novel.model.name", ""));
            mode = property(prefix, "billing-mode", environment.getProperty("novel.model.billing-mode", "metered"));
            input = environment.getProperty(prefix + "input-per-million", BigDecimal.class, environment.getProperty("novel.model.input-per-million", BigDecimal.class, BigDecimal.ZERO));
            output = environment.getProperty(prefix + "output-per-million", BigDecimal.class, environment.getProperty("novel.model.output-per-million", BigDecimal.class, BigDecimal.ZERO));
            defaultEffort = property(prefix, "reasoning-effort", environment.getProperty("novel.model.reasoning-effort", ""));
            efforts = Binder.get(environment).bind("novel.model.reasoning-efforts", Bindable.listOf(String.class)).orElse(List.of());
        } else {
            Profile profile = profiles().get(provider);
            if (profile == null) throw new IllegalArgumentException("模型服务不存在：" + provider);
            endpoint = Objects.toString(profile.endpoint(), "");
            key = Objects.toString(profile.apiKey(), "");
            name = Objects.toString(profile.name(), "");
            mode = Objects.toString(profile.billingMode(), "metered");
            input = profile.inputPerMillion() == null ? BigDecimal.ZERO : profile.inputPerMillion();
            output = profile.outputPerMillion() == null ? BigDecimal.ZERO : profile.outputPerMillion();
            efforts = profile.reasoningEfforts() == null ? List.of() : List.copyOf(profile.reasoningEfforts());
            defaultEffort = Objects.toString(profile.reasoningEffort(), "");
        }
        if (!Set.of("metered", "unmetered").contains(mode))
            throw new IllegalArgumentException("billing-mode 必须为 metered 或 unmetered");
        if (mode.equals("unmetered")) {
            input = BigDecimal.ZERO;
            output = BigDecimal.ZERO;
        }
        if (chosenModel != null && !chosenModel.isBlank()) name = chosenModel;
        String effort = chosenEffort == null || chosenEffort.isBlank() ? defaultEffort : chosenEffort;
        if (!effort.isEmpty() && !efforts.contains(effort))
            throw new IllegalArgumentException("该模型服务不支持所选思考强度：" + effort);
        return new Selection(provider, endpoint, key, name, mode, input, output, effort);
    }

    private String property(String prefix, String key, String defaultValue) {
        return environment.getProperty(prefix + key, defaultValue);
    }

    public Selection forTask(Map<String, Object> task) {
        String provider = defaultProvider(), model = "", effort = null;
        if (task.get("provider") != null) {
            provider = Store.string(task, "provider");
            model = Store.string(task, "model_name");
            effort = (String) task.get("reasoning_effort");
        } else if (task.get("novel_id") != null) {
            var novel = store.novel((String) task.get("novel_id"));
            provider = Store.string(novel, "provider");
            model = Store.string(novel, "model_name");
            effort = task.get("reasoning_effort") == null ? Store.string(novel, "reasoning_effort") : Store.string(task, "reasoning_effort");
        }
        return resolve(provider, model, Store.string(task, "kind"), effort);
    }

    public Map<String, Object> publicProfiles() {
        List<Map<String, Object>> options = new ArrayList<>();
        var ids = new LinkedHashSet<String>();
        ids.add("default");
        ids.addAll(profiles().keySet());
        for (String id : ids) {
            var selection = resolve(id, "", "PLAN");
            String label = id.equals("default") ? "云端服务" : Objects.toString(profiles().get(id).displayName(), id);
            List<String> efforts = id.equals("default") ?
                    Binder.get(environment).bind("novel.model.reasoning-efforts", Bindable.listOf(String.class)).orElse(List.of()) :
                    Objects.requireNonNullElse(profiles().get(id).reasoningEfforts(), List.of());
            options.add(Map.of("id", id, "label", label, "defaultModel", selection.model(), "ready", selection.ready(), "billingMode", selection.billingMode(),
                    "reasoningEfforts", efforts, "defaultReasoningEffort", selection.reasoningEffort()));
        }
        return Map.of("defaultProvider", defaultProvider(), "providers", options);
    }

    public List<String> models(String provider) {
        var selected = resolve(provider, "", "PLAN");
        if (!selected.ready()) throw new IllegalStateException("请在本机配置该服务的地址、凭据及计费方式");
        var endpoint = ModelEndpoint.resolve(selected.endpoint()).resolve("../models");
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + selected.apiKey()).GET().build();
        String target = endpoint.getHost() + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort());
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        } catch (HttpTimeoutException error) {
            throw new ModelServiceException(provider, ModelServiceException.Reason.TIMEOUT, "模型服务 " + provider + "（" + target + "）读取模型列表超时，请检查网络和服务状态", error);
        } catch (ConnectException error) {
            throw new ModelServiceException(provider, ModelServiceException.Reason.CONNECT, "无法连接模型服务 " + provider + "（" + target + "），请确认服务已启动并检查服务地址", error);
        } catch (IOException error) {
            throw new ModelServiceException(provider, ModelServiceException.Reason.CONNECT, "模型服务 " + provider + "（" + target + "）网络请求失败，请检查网络和服务地址", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelServiceException(provider, ModelServiceException.Reason.INTERRUPTED, "模型服务 " + provider + "的列表请求已中断，请重新读取", error);
        }
        if (response.statusCode() != 200) {
            boolean auth = response.statusCode() == 401 || response.statusCode() == 403;
            throw new ModelServiceException(provider, auth ? ModelServiceException.Reason.AUTH : ModelServiceException.Reason.UPSTREAM,
                    "模型服务 " + provider + "返回 HTTP " + response.statusCode() + (auth ? "，请检查本机配置的 API key" : "，请检查供应商服务状态"), null);
        }
        try {
            var root = json.read(response.body());
            if (root == null) throw new IllegalArgumentException("响应为空");
            var data = root.path("data");
            if (!data.isArray()) throw new IllegalArgumentException("缺少 data 数组");
            List<String> models = new ArrayList<>();
            for (var entry : data) {
                String name = json.text(entry, "id");
                if (!name.toLowerCase(Locale.ROOT).contains("image") && !name.equals("codex-auto-review"))
                    models.add(name);
            }
            if (models.isEmpty()) throw new IllegalArgumentException("没有可用文本模型");
            return models;
        } catch (IllegalArgumentException error) {
            throw new ModelServiceException(provider, ModelServiceException.Reason.INVALID_RESPONSE, "模型服务 " + provider + "未返回有效的文本模型列表", error);
        }
    }
}
