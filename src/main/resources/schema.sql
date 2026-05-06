-- 项目根目录 knowledge-agent/src/main/resources/schema.sql
CREATE DATABASE IF NOT EXISTS knowledge_agent DEFAULT CHARACTER SET utf8mb4;
USE knowledge_agent;
CREATE TABLE IF NOT EXISTS document (
                                        id VARCHAR(8) PRIMARY KEY,
                                        file_name VARCHAR(255) NOT NULL,
                                        status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
                                        chunk_count INT NULL,
                                        char_count INT NULL,
                                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;