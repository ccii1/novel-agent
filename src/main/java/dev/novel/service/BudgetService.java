package dev.novel.service;

import dev.novel.repository.Store;
import dev.novel.repository.mapper.ModelCallMapper;
import dev.novel.repository.model.ModelCallReservation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.*;

@Service
public class BudgetService {
    private final Store store;
    private final BigDecimal monthlyLimit;
    private final int requestsPerMinute;
    private final ModelCallMapper calls;
    public BudgetService(Store store,ModelCallMapper calls, @Value("${novel.monthly-budget}") BigDecimal limit,
            @Value("${novel.model.requests-per-minute:30}") int requestsPerMinute) {
        if(requestsPerMinute<1) throw new IllegalArgumentException("每分钟调用额度必须大于零");
        this.store = store; this.monthlyLimit = limit;this.requestsPerMinute=requestsPerMinute;
        this.calls=calls;
    }
    public String month() { return YearMonth.now(ZoneId.of("Asia/Shanghai")).toString(); }
    public BigDecimal monthlyUsed(String month) { return calls.monthlyUsed(month); }
    public BigDecimal novelUsed(String novelId) { return calls.novelTotal(novelId); }
    public Map<String,Object> overview() {
        String billingMonth=month();
        var used = monthlyUsed(billingMonth);
        return Map.of("month", billingMonth, "limit", monthlyLimit, "usedAndReserved", used, "remaining", monthlyLimit.subtract(used),
                "calls", calls.overview());
    }
    @Transactional
    public void reserve(Map<String,Object> task, BigDecimal amount, String model, BigDecimal inputPrice, BigDecimal outputPrice) {
        reserve(task,amount,model,inputPrice,outputPrice,null,null);
    }
    @Transactional
    public void reserve(Map<String,Object> task, BigDecimal amount, String model, BigDecimal inputPrice, BigDecimal outputPrice,
            SkillService.Snapshot skill,String requestBody) {
        reserve(task,amount,model,inputPrice,outputPrice,skill,requestBody,"default","metered");
    }
    @Transactional
    public void reserve(Map<String,Object> task, BigDecimal amount, String model, BigDecimal inputPrice, BigDecimal outputPrice,
            SkillService.Snapshot skill,String requestBody,String provider,String billingMode) {
        reserve(task,amount,model,inputPrice,outputPrice,skill,requestBody,provider,billingMode,"direct-http",UUID.randomUUID().toString());
    }
    @Transactional
    public void reserve(Map<String,Object> task, BigDecimal amount, String model, BigDecimal inputPrice, BigDecimal outputPrice,
            SkillService.Snapshot skill,String requestBody,String provider,String billingMode,String framework,String callId) {
        if(!Set.of("metered","unmetered").contains(billingMode)) throw new IllegalArgumentException("计费方式无效");
        boolean metered=billingMode.equals("metered");
        if(amount.signum()<0 || inputPrice.signum()<0 || outputPrice.signum()<0 ||
                (!metered && (amount.signum()!=0 || inputPrice.signum()!=0 || outputPrice.signum()!=0)))
            throw new IllegalArgumentException("调用费用与计费方式不一致");
        if(calls.lockBudget()==null) throw new IllegalStateException("费用锁记录不存在");
        String billingMonth=month();
        long recent=calls.recentCount();
        if(recent>=requestsPerMinute) throw new RateLimited();
        if(metered && monthlyUsed(billingMonth).add(amount).compareTo(monthlyLimit)>0) throw new BudgetExceeded("本月预算不足");
        String novelId=(String)task.get("novel_id");
        if(novelId!=null) {
            var novel=store.lockNovel(novelId);
            boolean outlineRequest="PLAN_CHANGE".equals(task.get("kind")) && PlanChangeService.allowedStatus(novel.get("status"));
            if(!outlineRequest && !Set.of("PLANNING","WRITING","CHECKING","REFLECTING","EXTRACTING","CHECKING_MEMORY").contains(novel.get("status")))
                throw new IllegalStateException("小说已暂停或正在等待审核");
            if(metered && calls.novelMonthlyUsed(novelId,billingMonth).add(amount).compareTo((BigDecimal)novel.get("novel_budget"))>0)
                throw new BudgetExceeded("本小说本月预算不足");
            if(metered && Store.integer(task,"chapter_no")>0 && calls.chapterUsed(novelId,Store.integer(task,"chapter_no")).add(amount).compareTo((BigDecimal)novel.get("chapter_budget"))>0)
                throw new BudgetExceeded("本章费用上限不足");
        }
        int inserted=calls.reserve(new ModelCallReservation(callId,Store.string(task,"id"),novelId,
                Store.integer(task,"chapter_no"),billingMonth,amount,model,inputPrice,outputPrice,provider,billingMode,
                skill==null?null:skill.version(),skill==null?null:skill.fingerprint(),
                skill==null?null:String.join("\n",skill.files()),requestBody,framework,(String)task.get("execution_id")));
        if(inserted!=1) throw new IllegalStateException("费用预留记录未成功创建");
    }
    @Transactional
    public void reconcile(String id, BigDecimal actual) {
        if (actual == null || actual.signum() < 0) throw new IllegalArgumentException("实际费用必须非负");
        if(calls.lockBudget()==null) throw new IllegalStateException("费用锁记录不存在");
        var call=calls.find(id);
        if(call==null) throw new NoSuchElementException("调用记录不存在");
        if("unmetered".equals(call.get("billing_mode")) && actual.signum()!=0)
            throw new IllegalArgumentException("免费服务的实际费用必须为零");
        int updated=calls.reconcile(actual,id);
        if(updated!=1) throw new IllegalStateException("仅可核对结果不确定的调用");
        store.event(null,null,"BILLING_RECONCILED",id+" 费用="+actual);
    }
    public static class BudgetExceeded extends IllegalStateException {
        public BudgetExceeded(String message) { super(message); }
    }
    public static class RateLimited extends IllegalStateException {
        public RateLimited() { super("等待每分钟模型调用额度"); }
    }
}
