# 智能知识库助手 (Knowledge Agent)

基于 **RAG（检索增强生成）** 架构的智能文档问答系统，上传PDF即可与文档内容对话。

## 技术架构

| 层级 | 技术 |
|------|------|
| 前端 | HTML + CSS + JavaScript |
| 后端 | Spring Boot 3.x |
| AI 框架 | LangChain4j |
| 大模型 | DeepSeek API |
| 消息队列 | RabbitMQ（异步文档处理） |
| 缓存/会话 | Redis（多轮对话记忆） |
| 文档解析 | Apache PDFBox |
| 部署 | Docker + Docker Compose |


## 核心功能

- ** 文档解析**：上传PDF自动提取文本并智能切片
- ** 混合检索**：融合Jaccard语义相似度与TF词频加权的检索策略
- ** RAG问答**：基于文档内容的精准回答，非凭空生成
- ** 多轮对话**：Redis存储会话历史，支持上下文追问
- ** 异步处理**：RabbitMQ实现文档上传与解析解耦，提升响应速度
- ** 容器化部署**：Docker Compose一键编排所有服务

## 技术栈

| 层级 | 技术 |
|------|------|
| 后端框架 | Spring Boot 3.x |
| AI框架 | LangChain4j |
| 大模型 | DeepSeek (OpenAI兼容接口) |
| 消息队列 | RabbitMQ |
| 缓存/会话 | Redis |
| 文档解析 | Apache PDFBox |
| 部署 | Docker + Docker Compose |

## 快速启动

### 环境要求
- JDK 17+
- Maven 3.8+
- Docker & Docker Compose

### 启动步骤

1. **克隆项目**
```bash
git clone https://github.com/你的用户名/knowledge-agent.git
cd knowledge-agent
