package dev.novel.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

@Configuration
@ConditionalOnProperty(name="novel.neo4j.enabled",havingValue="true")
public class Neo4jConfiguration {
    public record Connection(String uri,String database,String username,String password) {}
    @Bean Connection neo4jConnection(ObjectMapper mapper,
            @Value("${novel.neo4j.config-file:./config/neo4j-local.json}") String path) throws IOException {
        var connection=mapper.readValue(Path.of(path).toFile(),Connection.class);
        if(connection.uri()==null || connection.database()==null || connection.username()==null || connection.password()==null)
            throw new IllegalArgumentException("Neo4j 连接配置需要 uri、database、username、password");
        return connection;
    }
    @Bean(destroyMethod="close") Driver storyGraphDriver(Connection connection) {
        var driver=GraphDatabase.driver(connection.uri(),AuthTokens.basic(connection.username(),connection.password()),
                Config.builder().withMaxConnectionPoolSize(8).withConnectionTimeout(5,TimeUnit.SECONDS)
                        .withMaxTransactionRetryTime(0,TimeUnit.SECONDS).build());
        driver.verifyConnectivity();
        return driver;
    }
}
