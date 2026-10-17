package dev.novel.config;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.wrapper.MapWrapper;
import org.apache.ibatis.reflection.wrapper.ObjectWrapper;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.Locale;
import java.util.Map;

@Configuration
public class MyBatisConfiguration {
    @Bean ConfigurationCustomizer mapColumnNames() {
        return configuration -> {
            configuration.setCallSettersOnNulls(true);
            configuration.getTypeHandlerRegistry().register(java.sql.Clob.class,org.apache.ibatis.type.ClobTypeHandler.class);
            configuration.getTypeHandlerRegistry().register(org.apache.ibatis.type.JdbcType.CLOB,new org.apache.ibatis.type.ClobTypeHandler());
            configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
            configuration.setObjectWrapperFactory(new ObjectWrapperFactory() {
                @Override public boolean hasWrapperFor(Object object) { return object instanceof Map; }
                @Override @SuppressWarnings("unchecked") public ObjectWrapper getWrapperFor(MetaObject meta, Object object) {
                    return new MapWrapper(meta,(Map<String,Object>)object) {
                        @Override public String findProperty(String name, boolean camelCase) { return name.toLowerCase(Locale.ROOT); }
                    };
                }
            });
        };
    }
}
