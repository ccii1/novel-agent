package dev.novel.integration.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.function.Supplier;
import dev.novel.support.Json;

@Service
public class MemoryProjectionCache {
    private final RedissonClient redis;
    private final Json json;
    private final ObjectMapper mapper;
    private final String prefix;
    private final dev.novel.repository.Store store;

    public MemoryProjectionCache(ObjectProvider<RedissonClient> redis,Json json,ObjectMapper mapper,
            @Value("${novel.redis.key-prefix:novel-agent}") String prefix,dev.novel.repository.Store store) {
        this.redis=redis.getIfAvailable();this.json=json;this.mapper=mapper;this.prefix=prefix;this.store=store;
    }

    public Map<String,Object> graph(String novel,long version,String query,Supplier<Map<String,Object>> loader) {
        String fingerprint=DigestUtils.md5DigestAsHex(query.getBytes(StandardCharsets.UTF_8));
        // 命中缓存之前也要核对作品访问范围，权限检查不能由缓存命中绕过。
        String owner=dev.novel.repository.Store.string(store.novel(novel),"user_id");
        return read(prefix+":graph:v2:"+owner+":"+novel+":"+version+":"+fingerprint,new TypeReference<Map<String,Object>>() {},loader);
    }

    public List<Map<String,Object>> snapshot(String novel,long version,Supplier<List<Map<String,Object>>> loader) {
        String owner=dev.novel.repository.Store.string(store.novel(novel),"user_id");
        return read(prefix+":memory:v2:"+owner+":"+novel+":"+version,new TypeReference<List<Map<String,Object>>>() {},loader);
    }

    private <T> T read(String key,TypeReference<T> type,Supplier<T> loader) {
        if(redis==null) return loader.get();
        var bucket=redis.<String>getBucket(key,StringCodec.INSTANCE);
        String cached=bucket.get();
        if(cached!=null) return mapper.convertValue(json.read(cached),type);
        var value=loader.get();
        bucket.set(json.write(value),Duration.ofMinutes(30));
        return value;
    }
}
