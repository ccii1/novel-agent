package dev.novel;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:selection;DB_CLOSE_DELAY=-1","novel.engine-enabled=false",
        "novel.default-provider=local-test","novel.providers.local-test.endpoint=http://localhost:18080/v1",
        "novel.providers.local-test.api-key=test-configuration-only","novel.providers.local-test.name=text-model",
        "novel.providers.local-test.billing-mode=unmetered"})
class ModelSelectionTest {
    @Autowired Store store;
    @Autowired ProjectService projects;
    @Autowired ModelCatalog catalog;
    @Autowired BudgetService budget;
    @Autowired Json json;
    @BeforeEach void clean() {
        for(String table:List.of("events","model_calls","tasks","novels")) store.db.update("DELETE FROM "+table);
    }
    @Test void publicCatalogKeepsCredentialsPrivateAndUsesFreeProfile() {
        var selection=catalog.resolve(null,"selected-text-model","WRITE");
        assertThat(selection.ready()).isTrue();
        assertThat(selection.model()).isEqualTo("selected-text-model");
        assertThat(selection.inputPrice()).isZero();
        assertThat(selection.outputPrice()).isZero();
        assertThat(json.write(catalog.publicProfiles())).contains("local-test","unmetered")
                .doesNotContain("test-configuration-only","localhost","apiKey","endpoint");
    }
    @Test void novelSelectionRequiresIdleStateAndKeepsPriorCall() {
        String id=projects.create("密信","追查密信","保持人物记忆",1,new BigDecimal("100"),new BigDecimal("5"));
        var task=store.one("SELECT * FROM tasks WHERE novel_id=?",id);
        budget.reserve(task,BigDecimal.ONE,"paid-model",BigDecimal.ONE,BigDecimal.ONE);
        assertThatThrownBy(()->projects.selectModel(id,"local-test","another-model")).isInstanceOf(IllegalStateException.class);
        projects.pause(id);
        store.db.update("UPDATE tasks SET status='RUNNING' WHERE id=?",task.get("id"));
        assertThatThrownBy(()->projects.selectModel(id,"local-test","another-model")).hasMessageContaining("等待");
        store.db.update("UPDATE tasks SET status='WAITING' WHERE id=?",task.get("id"));
        projects.selectModel(id,"local-test","another-model");
        assertThat(catalog.forTask(task).model()).isEqualTo("another-model");
        var call=store.one("SELECT * FROM model_calls WHERE task_id=?",task.get("id"));
        assertThat(call.get("model")).isEqualTo("paid-model");
        assertThat(call.get("billing_mode")).isEqualTo("metered");
        assertThat(budget.used("novel_id=?",id)).isEqualByComparingTo("1");
    }
    @Test void freeCallsRemainAvailableAfterPaidBudgetIsConsumed() {
        String first=store.enqueue(null,"RESEARCH",0,0,0,"paid",Map.of());
        budget.reserve(store.one("SELECT * FROM tasks WHERE id=?",first),new BigDecimal("300"),"paid-model",BigDecimal.ONE,BigDecimal.ONE);
        String second=store.enqueue(null,"RESEARCH",0,0,0,"free",Map.of());
        budget.reserve(store.one("SELECT * FROM tasks WHERE id=?",second),BigDecimal.ZERO,"text-model",BigDecimal.ZERO,BigDecimal.ZERO,null,null,"local-test","unmetered");
        assertThat(budget.used("billing_month=?",budget.month())).isEqualByComparingTo("300");
        assertThat(store.one("SELECT billing_mode FROM model_calls WHERE task_id=?",second).get("billing_mode")).isEqualTo("unmetered");
        store.db.update("UPDATE model_calls SET status='UNCERTAIN' WHERE task_id=?",second);
        String call=(String)store.one("SELECT id FROM model_calls WHERE task_id=?",second).get("id");
        assertThatThrownBy(()->budget.reconcile(call,BigDecimal.ONE)).isInstanceOf(IllegalArgumentException.class);
        budget.reconcile(call,BigDecimal.ZERO);
    }
}
