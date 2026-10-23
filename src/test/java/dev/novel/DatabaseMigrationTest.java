package dev.novel;

import dev.novel.tools.MigrateH2ToMySql;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class DatabaseMigrationTest {
    private DriverManagerDataSource database() {
        var source=new DriverManagerDataSource("jdbc:h2:mem:migration_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
        return source;
    }
    private DriverManagerDataSource populated() {
        var source=database();var jdbc=new JdbcTemplate(source);
        jdbc.update("INSERT INTO app_users(username,password_hash) VALUES(?,?)","migration_user",new BCryptPasswordEncoder().encode("Migration-validation-123"));
        jdbc.update("INSERT INTO novels(id,title,premise,bible,status,user_id) VALUES('novel','迁移验证','调查信件','保持人物状态','PLANNING','migration_user')");
        jdbc.update("INSERT INTO tasks(id,novel_id,kind,task_key,status,chapter_no,memory_version,payload,user_id) VALUES('task','novel','PLAN_CHANGE','migration-task','WAITING',1,0,'{}','migration_user')");
        jdbc.update("INSERT INTO plan_changes(id,novel_id,task_id,stage_no,base_memory,base_chapter,base_plan,target_path,instructions,status) VALUES('change','novel','task',1,0,1,'{}','/stageGoal','保留悬念','GENERATING')");
        jdbc.update("INSERT INTO model_calls(id,task_id,novel_id,chapter_no,billing_month,status,reserved,model,input_price,output_price) VALUES('call','task','novel',1,'2026-10','IN_FLIGHT',0.1,'migration-accounting',1,1)");
        jdbc.update("INSERT INTO model_call_turns(id,call_id,task_id,turn_no,status,request_body) VALUES('turn','call','task',1,'IN_FLIGHT','{}')");
        return source;
    }
    @Test void copiesAccountsOwnershipOutlineChangesAndIndividualRequests() throws Exception {
        var source=populated();var target=database();
        try(var from=source.getConnection();var to=target.getConnection()) {
            MigrateH2ToMySql.copyBusinessData(from,to);
        }
        var original=new JdbcTemplate(source);var copied=new JdbcTemplate(target);
        assertThat(copied.queryForMap("SELECT * FROM app_users")).isEqualTo(original.queryForMap("SELECT * FROM app_users"));
        assertThat(copied.queryForObject("SELECT user_id FROM tasks",String.class)).isEqualTo("migration_user");
        assertThat(copied.queryForObject("SELECT COUNT(*) FROM plan_changes",Integer.class)).isEqualTo(1);
        assertThat(copied.queryForObject("SELECT COUNT(*) FROM model_call_turns",Integer.class)).isEqualTo(1);
        assertThat(copied.queryForObject("SELECT COUNT(*) FROM budget_lock",Integer.class)).isEqualTo(1);
        assertThat(original.queryForObject("SELECT COUNT(*) FROM app_users",Integer.class)).isEqualTo(1);
    }
    @Test void refusesNonemptyTargetBeforeWritingAnyBusinessData() throws Exception {
        var source=populated();var target=database();var copied=new JdbcTemplate(target);
        copied.update("INSERT INTO app_users(username,password_hash) VALUES('existing_user','existing-record')");
        try(var from=source.getConnection();var to=target.getConnection()) {
            assertThatThrownBy(()->MigrateH2ToMySql.copyBusinessData(from,to)).hasMessageContaining("目标表已有数据");
        }
        assertThat(copied.queryForObject("SELECT COUNT(*) FROM novels",Integer.class)).isZero();
        assertThat(copied.queryForObject("SELECT username FROM app_users",String.class)).isEqualTo("existing_user");
    }
    @Test void migratesLegacySchemaBeforeMarkdownTablesWereIntroduced() throws Exception {
        var source=populated();var target=database();var old=new JdbcTemplate(source);
        old.execute("DROP TABLE memory_files");old.execute("DROP TABLE memory_file_versions");
        try(var from=source.getConnection();var to=target.getConnection()) { MigrateH2ToMySql.copyBusinessData(from,to); }
        var copied=new JdbcTemplate(target);
        assertThat(copied.queryForObject("SELECT COUNT(*) FROM novels",Integer.class)).isEqualTo(1);
        assertThat(copied.queryForObject("SELECT COUNT(*) FROM memory_file_versions",Integer.class)).isZero();
        assertThat(copied.queryForObject("SELECT COUNT(*) FROM memory_files",Integer.class)).isZero();
    }
    @Test void connectionCloseUndoesAllCopiesAfterSchemaMismatch() throws Exception {
        var source=populated();var target=database();
        new JdbcTemplate(source).execute("ALTER TABLE plan_changes ADD COLUMN source_extension INT");
        try(var from=source.getConnection();var to=target.getConnection()) {
            assertThatThrownBy(()->MigrateH2ToMySql.copyBusinessData(from,to)).isInstanceOf(java.sql.SQLException.class);
        }
        var copied=new JdbcTemplate(target);
        for(String table:java.util.List.of("app_users","novels","tasks","model_calls","model_call_turns"))
            assertThat(copied.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).as(table).isZero();
    }
}
