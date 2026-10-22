package dev.novel.security;

import dev.novel.repository.Store;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;
import java.util.*;

@Component
public class OwnershipInterceptor implements HandlerInterceptor,WebMvcConfigurer {
    private final Store store;
    public OwnershipInterceptor(Store store) { this.store=store; }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this).addPathPatterns("/api/**"); }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        String user=UserScope.current();
        if(user==null) return true;
        @SuppressWarnings("unchecked")
        Map<String,String> variables=(Map<String,String>)request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if(variables!=null && variables.containsKey("id")) {
            String route=request.getRequestURI();
            String resource=route.startsWith("/api/novels/")?"novel":route.startsWith("/api/research/")?"research":
                route.startsWith("/api/tasks/") || route.startsWith("/api/observability/tasks/")?"task":
                route.startsWith("/api/calls/")?"call":route.startsWith("/api/edits/")?"edit":null;
            if(resource!=null) require(resource,variables.get("id"),user);
        }
        String novel=request.getParameter("novelId");
        if(novel!=null) require("novel",novel,user);
        return true;
    }
    private void require(String resource,String id,String user) {
        var rows=store.list("ownership."+resource,id);
        if(rows.isEmpty() || !user.equals(rows.get(0).get("user_id"))) throw new NoSuchElementException("记录不存在");
    }
}
