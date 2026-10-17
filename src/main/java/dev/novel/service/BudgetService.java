package dev.novel.service;

import dev.novel.repository.Store;

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
        String statement=switch(condition) {
            case "billing_month=?" -> "calls.monthlyUsed";
            case "novel_id=? AND billing_month=?" -> "calls.novelUsed";
            case "novel_id=? AND chapter_no=?" -> "calls.chapterUsed";
            case "novel_id=?" -> "calls.novelTotal";
            default -> throw new IllegalArgumentException("费用统计条件无效");
        };
        return (BigDecimal) store.one(statement,args).get("amount");
    }
    public Map<String,Object> overview() {
        var used = used("billing_month=?", month());
        return Map.of("month", month(), "limit", monthlyLimit, "usedAndReserved", used, "remaining", monthlyLimit.subtract(used),
                "calls", store.list("calls.overview"));
    }
    public void reserve(Map<String,Object> task, BigDecimal amount, String model, BigDecimal inputPrice, BigDecimal outputPrice) {
        reserve(task,amount,model,inputPrice,outputPrice,null,null);
    }
    public void reserve(Map<String,Object> task, BigDecimal amount, String model, BigDecimal inputPrice, BigDecimal outputPrice,
            SkillService.Snapshot skill,String requestBody) {
        reserve(task,amount,model,inputPrice,outputPrice,skill,requestBody,"default","metered");
    }
    public void reserve(Map<String,Object> task, BigDecimal amount, String model, BigDecimal inputPrice, BigDecimal outputPrice,
            SkillService.Snapshot skill,String requestBody,String provider,String billingMode) {
        if(!Set.of("metered","unmetered").contains(billingMode)) throw new IllegalArgumentException("计费方式无效");
        boolean metered=billingMode.equals("metered");
        if(amount.signum()<0 || inputPrice.signum()<0 || outputPrice.signum()<0 ||
                (!metered && (amount.signum()!=0 || inputPrice.signum()!=0 || outputPrice.signum()!=0)))
            throw new IllegalArgumentException("调用费用与计费方式不一致");
        store.transaction(() -> {
            store.one("calls.lockBudget");
            long recent=((Number)store.one("calls.recent").get("amount")).longValue();
            if(recent>=requestsPerMinute) throw new RateLimited();
            if (metered && used("billing_month=?", month()).add(amount).compareTo(monthlyLimit) > 0) throw new BudgetExceeded("本月预算不足");
            String novelId = (String) task.get("novel_id");
            if (novelId != null) {
                var novel = store.lockNovel(novelId);
                if (!Set.of("PLANNING","WRITING","CHECKING","REFLECTING","EXTRACTING","CHECKING_MEMORY").contains(novel.get("status")))
                    throw new IllegalStateException("小说已暂停或正在等待审核");
                if (metered && used("novel_id=? AND billing_month=?", novelId, month()).add(amount).compareTo((BigDecimal)novel.get("novel_budget")) > 0)
                    throw new BudgetExceeded("本小说本月预算不足");
                if (metered && Store.integer(task,"chapter_no") > 0 && used("novel_id=? AND chapter_no=?", novelId, task.get("chapter_no")).add(amount).compareTo((BigDecimal)novel.get("chapter_budget")) > 0)
                    throw new BudgetExceeded("本章费用上限不足");
            }
            store.update("calls.create",
                    UUID.randomUUID().toString(), task.get("id"), novelId, task.get("chapter_no"), month(), amount, model, inputPrice, outputPrice,provider,billingMode);
            if(skill!=null) store.update("calls.skill",
                    skill.version(),skill.fingerprint(),String.join("\n",skill.files()),requestBody,task.get("id"));
            return null;
        });
    }
    public void reconcile(String id, BigDecimal actual) {
        if (actual == null || actual.signum() < 0) throw new IllegalArgumentException("实际费用必须非负");
        store.transaction(() -> {
            store.one("calls.lockBudget");
            var call=store.one("calls.find",id);
            if("unmetered".equals(call.get("billing_mode")) && actual.signum()!=0)
                throw new IllegalArgumentException("免费服务的实际费用必须为零");
            int updated = store.update("calls.reconcile", actual, id);
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
