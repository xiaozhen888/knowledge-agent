package com.xiaozhen.knowledgeagent.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ChunkResult {
    private String content;
    private String docId;
    private String docName;
    private int chunkIndex;
    private double score;

    // 完整构造器
    public ChunkResult(String content, String docId, String docName, int chunkIndex, double score) {
        this.content = content;
        this.docId = docId;
        this.docName = docName;
        this.chunkIndex = chunkIndex;
        this.score = score;
    }

    // 常用构造器（score默认0）
    public ChunkResult(String content, String docId, String docName, int chunkIndex) {
        this(content, docId, docName, chunkIndex, 0.0);
    }
}