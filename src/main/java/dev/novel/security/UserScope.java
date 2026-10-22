package dev.novel.security;

import org.springframework.security.core.context.SecurityContextHolder;

public final class UserScope {
    private static final ThreadLocal<String> WORKER = new ThreadLocal<>();
    private UserScope() {}
    public static String current() {
        if(WORKER.get()!=null) return WORKER.get();
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        return authentication!=null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())?authentication.getName():null;
    }
    public static String owner() { return current()==null?"owner":current(); }
    public static void enter(String user) {
        if(user==null || user.isBlank()) throw new IllegalStateException("后台任务缺少所属用户");
        WORKER.set(user);
    }
    public static void clear() { WORKER.remove(); }
}
