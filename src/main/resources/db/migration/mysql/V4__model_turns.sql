CREATE TABLE model_call_turns (
 id VARCHAR(36) PRIMARY KEY,call_id VARCHAR(36) NOT NULL,task_id VARCHAR(36) NOT NULL,turn_no INT NOT NULL,
 status VARCHAR(30) NOT NULL,request_body LONGTEXT NOT NULL,response_body LONGTEXT,
 input_tokens BIGINT,output_tokens BIGINT,cached_tokens BIGINT,cost DECIMAL(14,6),latency_ms BIGINT,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),finished_at TIMESTAMP(3) NULL DEFAULT NULL,
 UNIQUE(task_id,turn_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
