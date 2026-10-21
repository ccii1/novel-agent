CREATE TABLE plan_changes (
 id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, task_id VARCHAR(36) NOT NULL,
 stage_no INT NOT NULL, base_memory BIGINT NOT NULL, base_chapter INT NOT NULL,
 base_plan LONGTEXT NOT NULL, target_path VARCHAR(200) NOT NULL, instructions LONGTEXT NOT NULL,
 suggestion LONGTEXT, applied_plan LONGTEXT, status VARCHAR(30) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), applied_at TIMESTAMP(3) NULL DEFAULT NULL,
 INDEX plan_change_novel_idx(novel_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
