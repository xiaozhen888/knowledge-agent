package com.xiaozhen.knowledgeagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkResult {
    private String content;
    private String docId;
    private String docName;
    private int chunkIndex;
    private double score;

    public ChunkResult(String content, String docId, String docName, int chunkIndex) {
        this.content = content;
        this.docId = docId;
        this.docName = docName;
        this.chunkIndex = chunkIndex;
        this.score = 0.0;
    }
}