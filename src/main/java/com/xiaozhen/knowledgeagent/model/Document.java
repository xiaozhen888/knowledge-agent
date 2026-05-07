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

    private Boolean active = true;

    private Boolean deleted = false;

    @Column(name = "file_md5")
    private String fileMd5;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Document() {}

    public Document(String id, String fileName, String status) {
        this.id = id;
        this.fileName = fileName;
        this.status = status;
        this.active = true;
        this.deleted = false;
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
    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }
    public Boolean getDeleted() { return deleted; }
    public void setDeleted(Boolean deleted) { this.deleted = deleted; }
    public String getFileMd5() { return fileMd5; }
    public void setFileMd5(String fileMd5) { this.fileMd5 = fileMd5; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}