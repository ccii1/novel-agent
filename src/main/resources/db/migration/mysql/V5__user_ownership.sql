CREATE TABLE app_users (
 username VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 password_hash VARCHAR(100) NOT NULL,
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
ALTER TABLE novels ADD COLUMN user_id VARCHAR(64) NOT NULL DEFAULT 'owner';
ALTER TABLE tasks ADD COLUMN user_id VARCHAR(64) NOT NULL DEFAULT 'owner';
ALTER TABLE research ADD COLUMN user_id VARCHAR(64) NOT NULL DEFAULT 'owner';
CREATE INDEX novel_user_idx ON novels(user_id,created_at);
CREATE INDEX task_user_idx ON tasks(user_id,created_at);
CREATE INDEX research_user_idx ON research(user_id,created_at);
