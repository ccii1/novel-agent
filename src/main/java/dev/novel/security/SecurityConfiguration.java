package dev.novel.security;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.novel.dto.response.CommonResponses.*;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain security(HttpSecurity http, ObjectMapper json) throws Exception {
        http.authorizeHttpRequests(auth->auth
                .requestMatchers("/api/auth/csrf","/api/auth/login","/api/auth/register").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .csrf(csrf->csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .formLogin(login->login.loginProcessingUrl("/api/auth/login")
                .successHandler((request,response,authentication)->reply(response,200,new UserResponse(authentication.getName()),json))
                .failureHandler((request,response,error)->reply(response,401,new ErrorResponse("用户名或密码不正确"),json)))
            .logout(logout->logout.logoutUrl("/api/auth/logout").deleteCookies("JSESSIONID","XSRF-TOKEN")
                .logoutSuccessHandler((request,response,authentication)->reply(response,200,ActionResponse.SUCCESS,json)))
            .exceptionHandling(errors->errors
                .authenticationEntryPoint((request,response,error)->reply(response,401,new ErrorResponse("请登录后继续"),json))
                .accessDeniedHandler((request,response,error)->reply(response,403,new ErrorResponse("请求验证失败，请刷新页面后重试"),json)))
            .requestCache(cache->cache.disable());
        return http.build();
    }
    // 过滤链中的错误发生在 Controller 之前，也使用相同的响应 DTO。
    private static <T> void reply(HttpServletResponse response, int status, T body, ObjectMapper json) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        json.writeValue(response.getOutputStream(), body);
    }
}
