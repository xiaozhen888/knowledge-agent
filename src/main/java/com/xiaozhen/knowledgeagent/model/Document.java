package com.xiaozhen.knowledgeagent.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "document")
public class Document {

    @Id
    private String id;

    @Column(name = "file_name")
    private String fileName;

    private String status;

    @Column(name = "chunk_count")
    private Integer chunkCount;

    @Column(name = "char_count")
    private Integer charCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Document() {}

    public Document(String id, String fileName, String status) {
        this.id = id;
        this.fileName = fileName;
        this.status = status;
        this.createdAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }

    public Integer getCharCount() { return charCount; }
    public void setCharCount(Integer charCount) { this.charCount = charCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}