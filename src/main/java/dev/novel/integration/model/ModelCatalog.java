package dev.novel.integration.model;

import dev.novel.repository.Store;
import dev.novel.support.Json;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Service
public class ModelCatalog {
    private final Environment environment;
    private final Store store;
    private final Json json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    public record Profile(String endpoint,String apiKey,String name,String billingMode,
            BigDecimal inputPerMillion,BigDecimal outputPerMillion,String displayName) {}
    public record Selection(String provider,String endpoint,String apiKey,String model,String billingMode,
            BigDecimal inputPrice,BigDecimal outputPrice) {
        public boolean ready() {
            return !endpoint.isBlank() && !apiKey.isBlank() && !model.isBlank() &&
                    (billingMode.equals("unmetered") || (inputPrice.signum()>0 && outputPrice.signum()>0));
        }
    }
    public ModelCatalog(Environment environment,Store store,Json json) { this.environment=environment;this.store=store;this.json=json; }
    public String defaultProvider() { return environment.getProperty("novel.default-provider","default"); }
    private Map<String,Profile> profiles() {
        return Binder.get(environment).bind("novel.providers",Bindable.mapOf(String.class,Profile.class)).orElse(Map.of());
    }
    public Selection resolve(String provider,String chosenModel,String role) {
        provider=provider==null || provider.isBlank()?defaultProvider():provider;
        String prefix;
        String endpoint,key,name,mode;BigDecimal input,output;
        if(provider.equals("default")) {
            prefix="novel.agents."+role.toLowerCase(Locale.ROOT).replace('_','-')+".";
            endpoint=property(prefix,"endpoint",environment.getProperty("novel.model.endpoint",""));
            key=property(prefix,"api-key",environment.getProperty("novel.model.api-key",""));
            name=property(prefix,"name",environment.getProperty("novel.model.name",""));
            mode=property(prefix,"billing-mode",environment.getProperty("novel.model.billing-mode","metered"));
            input=environment.getProperty(prefix+"input-per-million",BigDecimal.class,environment.getProperty("novel.model.input-per-million",BigDecimal.class,BigDecimal.ZERO));
            output=environment.getProperty(prefix+"output-per-million",BigDecimal.class,environment.getProperty("novel.model.output-per-million",BigDecimal.class,BigDecimal.ZERO));
        } else {
            Profile profile=profiles().get(provider);
            if(profile==null) throw new IllegalArgumentException("模型服务不存在："+provider);
            endpoint=Objects.toString(profile.endpoint(),"");key=Objects.toString(profile.apiKey(),"");name=Objects.toString(profile.name(),"");
            mode=Objects.toString(profile.billingMode(),"metered");
            input=profile.inputPerMillion()==null?BigDecimal.ZERO:profile.inputPerMillion();
            output=profile.outputPerMillion()==null?BigDecimal.ZERO:profile.outputPerMillion();
        }
        if(!Set.of("metered","unmetered").contains(mode)) throw new IllegalArgumentException("billing-mode 必须为 metered 或 unmetered");
        if(mode.equals("unmetered")) {input=BigDecimal.ZERO;output=BigDecimal.ZERO;}
        if(chosenModel!=null && !chosenModel.isBlank()) name=chosenModel;
        return new Selection(provider,endpoint,key,name,mode,input,output);
    }
    private String property(String prefix,String key,String defaultValue) { return environment.getProperty(prefix+key,defaultValue); }
    public Selection forTask(Map<String,Object> task) {
        String provider=defaultProvider(),model="";
        if(task.get("novel_id")!=null) {
            var novel=store.novel((String)task.get("novel_id"));
            provider=Store.string(novel,"provider");model=Store.string(novel,"model_name");
        } else if(task.get("provider")!=null) {
            provider=Store.string(task,"provider");model=Store.string(task,"model_name");
        }
        return resolve(provider,model,Store.string(task,"kind"));
    }
    public Map<String,Object> publicProfiles() {
        List<Map<String,Object>> options=new ArrayList<>();
        var ids=new LinkedHashSet<String>();ids.add("default");ids.addAll(profiles().keySet());
        for(String id:ids) {
            var selection=resolve(id,"","PLAN");
            String label=id.equals("default")?"云端服务":Objects.toString(profiles().get(id).displayName(),id);
            options.add(Map.of("id",id,"label",label,"defaultModel",selection.model(),"ready",selection.ready(),"billingMode",selection.billingMode()));
        }
        return Map.of("defaultProvider",defaultProvider(),"providers",options);
    }
    public List<String> models(String provider) throws Exception {
        var selected=resolve(provider,"","PLAN");
        if(!selected.ready()) throw new IllegalStateException("请在本机配置该服务的地址、凭据及计费方式");
        var endpoint=ModelEndpoint.resolve(selected.endpoint()).resolve("../models");
        var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20)).header("Authorization","Bearer "+selected.apiKey()).GET().build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        if(response.statusCode()!=200) throw new IllegalStateException("模型列表返回 HTTP "+response.statusCode());
        var data=json.read(response.body()).path("data");
        if(!data.isArray()) throw new IllegalArgumentException("模型服务未返回有效列表");
        List<String> models=new ArrayList<>();
        for(var entry:data) {
            String name=json.text(entry,"id");
            if(!name.toLowerCase(Locale.ROOT).contains("image") && !name.equals("codex-auto-review")) models.add(name);
        }
        if(models.isEmpty()) throw new IllegalStateException("模型列表中没有可用的文本模型");
        return models;
    }
}
