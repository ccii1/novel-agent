package dev.novel.security;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.*;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication(type=org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET)
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth->auth
                .requestMatchers("/api/auth/csrf","/api/auth/login","/api/auth/register").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().permitAll())
            .csrf(csrf->csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .formLogin(login->login.loginProcessingUrl("/api/auth/login")
                .successHandler((request,response,authentication)->reply(response,200,"{\"username\":\""+authentication.getName()+"\"}"))
                .failureHandler((request,response,error)->reply(response,401,"{\"error\":\"用户名或密码不正确\"}")))
            .logout(logout->logout.logoutUrl("/api/auth/logout").deleteCookies("JSESSIONID","XSRF-TOKEN")
                .logoutSuccessHandler((request,response,authentication)->reply(response,200,"{\"ok\":true}")))
            .exceptionHandling(errors->errors
                .authenticationEntryPoint((request,response,error)->reply(response,401,"{\"error\":\"请登录后继续\"}"))
                .accessDeniedHandler((request,response,error)->reply(response,403,"{\"error\":\"请求验证失败，请刷新页面后重试\"}")))
            .requestCache(cache->cache.disable());
        return http.build();
    }
    private static void reply(HttpServletResponse response,int status,String body) throws java.io.IOException {
        response.setStatus(status);response.setContentType("application/json;charset=UTF-8");response.getWriter().write(body);
    }
}
