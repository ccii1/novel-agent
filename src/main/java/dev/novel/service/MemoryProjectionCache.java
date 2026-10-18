package dev.novel.service;

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

    public MemoryProjectionCache(ObjectProvider<RedissonClient> redis,Json json,ObjectMapper mapper,
            @Value("${novel.redis.key-prefix:novel-agent}") String prefix) {
        this.redis=redis.getIfAvailable();this.json=json;this.mapper=mapper;this.prefix=prefix;
    }

    public Map<String,Object> graph(String novel,long version,String query,Supplier<Map<String,Object>> loader) {
        String fingerprint=DigestUtils.md5DigestAsHex(query.getBytes(StandardCharsets.UTF_8));
        return read(prefix+":graph:v1:"+novel+":"+version+":"+fingerprint,new TypeReference<Map<String,Object>>() {},loader);
    }

    public List<Map<String,Object>> snapshot(String novel,long version,Supplier<List<Map<String,Object>>> loader) {
        return read(prefix+":memory:v1:"+novel+":"+version,new TypeReference<List<Map<String,Object>>>() {},loader);
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
