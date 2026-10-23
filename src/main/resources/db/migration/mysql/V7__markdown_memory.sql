CREATE TABLE memory_file_versions (
 novel_id VARCHAR(36) NOT NULL, version BIGINT NOT NULL, base_version BIGINT NOT NULL,
 chapter_no INT NOT NULL, revision INT NOT NULL, fingerprint VARCHAR(64) NOT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(novel_id,version)
);
CREATE TABLE memory_files (
 novel_id VARCHAR(36) NOT NULL, version BIGINT NOT NULL, path VARCHAR(300) NOT NULL,
 physical_path VARCHAR(500) NOT NULL, fingerprint VARCHAR(64) NOT NULL, bytes INT NOT NULL,
 title VARCHAR(300) NOT NULL, description VARCHAR(1000) NOT NULL, kind VARCHAR(40) NOT NULL,
 entity_key VARCHAR(400) NOT NULL, chapter_no INT NOT NULL, source_revision INT NOT NULL,
 PRIMARY KEY(novel_id,version,path)
);
