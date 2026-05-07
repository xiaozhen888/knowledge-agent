CREATE DATABASE IF NOT EXISTS knowledge_agent DEFAULT CHARACTER SET utf8mb4;
USE knowledge_agent;
CREATE TABLE IF NOT EXISTS document (
                                        id VARCHAR(8) PRIMARY KEY,
                                        file_name VARCHAR(255) NOT NULL,
                                        status VARCHAR(20) NOT NULL DEFAULT 'PROCESSING',
                                        chunk_count INT NULL,
                                        char_count INT NULL,
                                        active TINYINT(1) DEFAULT 1 COMMENT '是否激活参与问答，1=是，0=否',
                                        file_md5 VARCHAR(32) DEFAULT NULL COMMENT '文件MD5值，用于去重',
                                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 用户反馈表
CREATE TABLE IF NOT EXISTS feedback (
                                        id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
                                        session_id VARCHAR(50) NOT NULL COMMENT '会话ID',
                                        question TEXT NOT NULL COMMENT '用户问题',
                                        answer TEXT NOT NULL COMMENT 'AI回答',
                                        rating VARCHAR(10) NOT NULL COMMENT '评价: like 或 dislike',
                                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '反馈时间'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户反馈记录表';