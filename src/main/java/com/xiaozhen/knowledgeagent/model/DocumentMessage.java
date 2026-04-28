package com.xiaozhen.knowledgeagent.model;

import java.io.Serializable;

public class DocumentMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private String docId;
    private byte[] content;
    private String fileName;

    public DocumentMessage() {}

    public DocumentMessage(String docId, byte[] content, String fileName) {
        this.docId = docId;
        this.content = content;
        this.fileName = fileName;
    }

    public String getDocId() { return docId; }
    public void setDocId(String docId) { this.docId = docId; }
    public byte[] getContent() { return content; }
    public void setContent(byte[] content) { this.content = content; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
}