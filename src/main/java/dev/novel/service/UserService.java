package dev.novel.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.novel.repository.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.UUID;

@Service
public class UserService implements UserDetailsService {
    private final UserMapper users;
    private final PasswordEncoder passwords;
    private final ObjectMapper json;
    private final Path bootstrap;
    public UserService(UserMapper users,PasswordEncoder passwords,ObjectMapper json,
            @Value("${novel.auth.bootstrap-file:config/auth-local.json}") String bootstrap) {
        this.users=users;this.passwords=passwords;this.json=json;this.bootstrap=Path.of(bootstrap);
    }
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void initializeOwner() throws IOException {
        if(users.find("owner")!=null) return;
        Map<String,String> credentials;
        if(Files.exists(bootstrap)) {
            var node=json.readTree(Files.readString(bootstrap));
            credentials=Map.of("username",node.path("username").asText(),"password",node.path("password").asText());
            if(!"owner".equals(credentials.get("username"))) throw new IllegalStateException("本地管理账号必须为 owner");
            validate("owner",credentials.get("password"));
        } else {
            credentials=Map.of("username","owner","password",UUID.randomUUID().toString());
            Files.createDirectories(bootstrap.toAbsolutePath().getParent());
            Files.writeString(bootstrap,json.writerWithDefaultPrettyPrinter().writeValueAsString(credentials),StandardOpenOption.CREATE_NEW);
        }
        users.create("owner",passwords.encode(credentials.get("password")));
    }
    public void register(String username,String password) {
        validate(username,password);
        if("owner".equals(username)) throw new IllegalArgumentException("该用户名不可注册");
        try { users.create(username,passwords.encode(password)); }
        catch(DuplicateKeyException duplicate) { throw new IllegalArgumentException("该用户名已注册"); }
    }
    private void validate(String username,String password) {
        if(username==null || !username.matches("[a-z][a-z0-9_]{2,31}"))
            throw new IllegalArgumentException("用户名需要3至32个小写字母、数字或下划线，并以字母开头");
        if(password==null || password.length()<10 || password.length()>64 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new IllegalArgumentException("密码需要10至64个字符，UTF-8 长度最多72字节");
    }
    @Override public UserDetails loadUserByUsername(String username) {
        var user=users.find(username);
        if(user==null) throw new UsernameNotFoundException("用户名或密码不正确");
        return User.withUsername(username).password(user.get("password_hash").toString()).roles("USER").build();
    }
}
