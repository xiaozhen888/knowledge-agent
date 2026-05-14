package com.xiaozhen.knowledgeagent.model;

import lombok.Data;
import java.util.List;

@Data
public class CachedAnswer {
    private String answer;
    private List<String> sources;  // 引用的文档来源，如 ["八股文.pdf:99"]
    private long cachedAt;       // 缓存时间戳
}