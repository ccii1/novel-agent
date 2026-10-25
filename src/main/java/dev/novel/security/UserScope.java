package dev.novel.security;

import org.springframework.security.core.context.SecurityContextHolder;

public final class UserScope {
    private static final ThreadLocal<String> WORKER = new ThreadLocal<>();

    private UserScope() {
    }

    public static String current() {
        // 后台工作线程使用任务身份；HTTP 请求使用 Spring Security 的认证身份。
        if (WORKER.get() != null) return WORKER.get();
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal()) ? authentication.getName() : null;
    }

    // 无请求身份的本地维护操作归属 owner；业务 HTTP 接口统一要求认证。
    public static String owner() {
        return current() == null ? "owner" : current();
    }

    public static void enter(String user) {
        if (user == null || user.isBlank()) throw new IllegalStateException("后台任务缺少所属用户");
        WORKER.set(user);
    }

    public static void clear() {
        WORKER.remove();
    }
}
