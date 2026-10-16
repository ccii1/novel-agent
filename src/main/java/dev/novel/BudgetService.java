package dev.novel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

@Service
public class BudgetService {
    private final Store store;
    private final BigDecimal monthlyLimit;
    private final int requestsPerMinute;
    public BudgetService(Store store, @Value("${novel.monthly-budget}") BigDecimal limit,
            @Value("${novel.model.requests-per-minute:30}") int requestsPerMinute) {
        if(requestsPerMinute<1) throw new IllegalArgumentException("每分钟调用额度必须大于零");
        this.store = store; this.monthlyLimit = limit;this.requestsPerMinute=requestsPerMinute;
    }
    public String month() { return YearMonth.now(ZoneId.of("Asia/Shanghai")).toString(); }
    public BigDecimal used(String condition, Object... args) {
        return (BigDecimal) store.one("SELECT COALESCE(SUM(COALESCE(actual,reserved)),0) AS amount FROM model_calls WHERE status<>'REJECTED' AND " + condition, args).get("amount");
    }
    public Map<String,Object> overview() {
        var used = used("billing_month=?", month());
        return Map.of("month", month(), "limit", monthlyLimit, "usedAndReserved", used, "remaining", monthlyLimit.subtract(used),
                "calls", store.list("SELECT id,task_id,novel_id,chapter_no,billing_month,status,reserved,actual,input_tokens,output_tokens,model,created_at FROM model_calls ORDER BY created_at DESC LIMIT 100"));
    }
    public void reserve(Map<String,Object> task, BigDecimal amount, String model, BigDecimal inputPrice, BigDecimal outputPrice) {
        store.transaction(() -> {
            store.one("SELECT id FROM budget_lock WHERE id=1 FOR UPDATE");
            long recent=((Number)store.one("SELECT COUNT(*) AS amount FROM model_calls WHERE created_at>DATEADD('SECOND',-60,CURRENT_TIMESTAMP)").get("amount")).longValue();
            if(recent>=requestsPerMinute) throw new RateLimited();
            if (used("billing_month=?", month()).add(amount).compareTo(monthlyLimit) > 0) throw new BudgetExceeded("本月预算不足");
            String novelId = (String) task.get("novel_id");
            if (novelId != null) {
                var novel = store.lockNovel(novelId);
                if (!Set.of("PLANNING","WRITING","CHECKING","REFLECTING","EXTRACTING","CHECKING_MEMORY").contains(novel.get("status")))
                    throw new IllegalStateException("小说已暂停或正在等待审核");
                if (used("novel_id=? AND billing_month=?", novelId, month()).add(amount).compareTo((BigDecimal)novel.get("novel_budget")) > 0)
                    throw new BudgetExceeded("本小说本月预算不足");
                if (Store.integer(task,"chapter_no") > 0 && used("novel_id=? AND chapter_no=?", novelId, task.get("chapter_no")).add(amount).compareTo((BigDecimal)novel.get("chapter_budget")) > 0)
                    throw new BudgetExceeded("本章费用上限不足");
            }
            store.db.update("INSERT INTO model_calls(id,task_id,novel_id,chapter_no,billing_month,status,reserved,model,input_price,output_price) VALUES(?,?,?,?,?,'IN_FLIGHT',?,?,?,?)",
                    UUID.randomUUID().toString(), task.get("id"), novelId, task.get("chapter_no"), month(), amount, model, inputPrice, outputPrice);
            return null;
        });
    }
    public void reconcile(String id, BigDecimal actual) {
        if (actual == null || actual.signum() < 0) throw new IllegalArgumentException("实际费用必须非负");
        store.transaction(() -> {
            store.one("SELECT id FROM budget_lock WHERE id=1 FOR UPDATE");
            int updated = store.db.update("UPDATE model_calls SET status='RECONCILED',actual=? WHERE id=? AND status='UNCERTAIN'", actual, id);
            if (updated != 1) throw new IllegalStateException("仅可核对结果不确定的调用");
            store.event(null,null,"BILLING_RECONCILED",id + " 费用=" + actual);
            return null;
        });
    }
    public static class BudgetExceeded extends IllegalStateException {
        public BudgetExceeded(String message) { super(message); }
    }
    public static class RateLimited extends IllegalStateException {
        public RateLimited() { super("等待每分钟模型调用额度"); }
    }
}
