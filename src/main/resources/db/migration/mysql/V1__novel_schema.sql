CREATE TABLE novels (
 id VARCHAR(36) PRIMARY KEY, title VARCHAR(200) NOT NULL, premise LONGTEXT NOT NULL,
 bible LONGTEXT NOT NULL, status VARCHAR(40) NOT NULL, stage_no INT NOT NULL DEFAULT 1,
 chapter_no INT NOT NULL DEFAULT 1, stage_size INT NOT NULL DEFAULT 10,
 memory_version BIGINT NOT NULL DEFAULT 0, plan LONGTEXT, reflection LONGTEXT,
 novel_budget DECIMAL(14,6) NOT NULL DEFAULT 100, chapter_budget DECIMAL(14,6) NOT NULL DEFAULT 5,
 error LONGTEXT, resume_status VARCHAR(40), created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 provider VARCHAR(80) NOT NULL DEFAULT 'default', model_name VARCHAR(200)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE tasks (
 id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36), kind VARCHAR(40) NOT NULL,
 task_key VARCHAR(200) NOT NULL UNIQUE, status VARCHAR(30) NOT NULL,
 chapter_no INT NOT NULL, revision INT NOT NULL DEFAULT 0, memory_version BIGINT NOT NULL,
 payload LONGTEXT NOT NULL, result LONGTEXT, error LONGTEXT,
 available_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 research_id VARCHAR(36), provider VARCHAR(80), model_name VARCHAR(200),
 INDEX task_status_idx(status,available_at,created_at), INDEX task_novel_idx(novel_id,chapter_no,revision,kind,status),
 INDEX task_research_idx(research_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE chapters (
 novel_id VARCHAR(36) NOT NULL, number INT NOT NULL, stage_no INT NOT NULL,
 revision INT NOT NULL, title VARCHAR(300) NOT NULL, body LONGTEXT NOT NULL,
 summary LONGTEXT, status VARCHAR(30) NOT NULL, memory_version BIGINT NOT NULL,
 published BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY(novel_id,number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE chapter_history (
 id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, number INT NOT NULL,
 revision INT NOT NULL, title VARCHAR(300) NOT NULL, body LONGTEXT NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), INDEX history_chapter_idx(novel_id,number,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE memories (
 id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, memory_key VARCHAR(400) NOT NULL,
 kind VARCHAR(30) NOT NULL, chapter_no INT NOT NULL, version BIGINT NOT NULL,
 content LONGTEXT NOT NULL, UNIQUE(novel_id,memory_key,version), INDEX memory_version_idx(novel_id,version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE model_calls (
 id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL UNIQUE,
 novel_id VARCHAR(36), chapter_no INT NOT NULL, billing_month VARCHAR(7) NOT NULL,
 status VARCHAR(30) NOT NULL, reserved DECIMAL(14,6) NOT NULL,
 actual DECIMAL(14,6), input_tokens BIGINT, output_tokens BIGINT,
 model VARCHAR(200) NOT NULL, input_price DECIMAL(14,6) NOT NULL, output_price DECIMAL(14,6) NOT NULL,
 response LONGTEXT, error LONGTEXT, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 finished_at TIMESTAMP(3) NULL DEFAULT NULL, latency_ms BIGINT, usage_json LONGTEXT, cached_input_tokens BIGINT,
 execution_id VARCHAR(36), skill_version VARCHAR(40), skill_fingerprint VARCHAR(64), skill_files LONGTEXT,
 request_body LONGTEXT, provider VARCHAR(80) NOT NULL DEFAULT 'default', billing_mode VARCHAR(30) NOT NULL DEFAULT 'metered',
 agent_framework VARCHAR(60) NOT NULL DEFAULT 'direct-http', INDEX call_month_idx(billing_month),
 INDEX call_created_idx(created_at), INDEX call_novel_idx(novel_id,billing_month,chapter_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE chapter_metrics (
 novel_id VARCHAR(36) NOT NULL, chapter_no INT NOT NULL, revision INT NOT NULL,
 characters INT NOT NULL, total_lines INT NOT NULL, dialogue_lines INT NOT NULL,
 dialogue_ratio DOUBLE NOT NULL, pacing_data LONGTEXT, PRIMARY KEY(novel_id,chapter_no,revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE budget_lock (id INT PRIMARY KEY) ENGINE=InnoDB;
INSERT INTO budget_lock(id) VALUES(1);
CREATE TABLE research (
 id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL UNIQUE, sources LONGTEXT NOT NULL,
 ideas LONGTEXT NOT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), brief LONGTEXT NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE research_messages (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, research_id VARCHAR(36) NOT NULL, role VARCHAR(20) NOT NULL,
 content LONGTEXT NOT NULL, ideas LONGTEXT, task_id VARCHAR(36),
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), INDEX research_message_idx(research_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE edit_requests (
 id VARCHAR(36) PRIMARY KEY, novel_id VARCHAR(36) NOT NULL, chapter_no INT NOT NULL,
 base_version BIGINT NOT NULL, instruction LONGTEXT NOT NULL, impact LONGTEXT NOT NULL,
 status VARCHAR(30) NOT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE events (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, novel_id VARCHAR(36), task_id VARCHAR(36), type VARCHAR(60) NOT NULL,
 message LONGTEXT NOT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 execution_id VARCHAR(36), INDEX event_task_idx(task_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE agent_executions (
 id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL, status VARCHAR(30) NOT NULL,
 result_source VARCHAR(30) NOT NULL, started_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 finished_at TIMESTAMP(3) NULL DEFAULT NULL, queue_ms BIGINT NOT NULL, duration_ms BIGINT, error LONGTEXT, result LONGTEXT,
 approved BOOLEAN, issue_count INT, major_change BOOLEAN, body_characters INT, fact_count INT, relation_count INT,
 INDEX execution_task_idx(task_id,started_at), INDEX execution_status_idx(status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
CREATE TABLE agent_feedback (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, task_id VARCHAR(36) NOT NULL, rating INT NOT NULL,
 note VARCHAR(4000) NOT NULL, created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 INDEX feedback_task_idx(task_id,id), CHECK(rating BETWEEN 1 AND 5)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
