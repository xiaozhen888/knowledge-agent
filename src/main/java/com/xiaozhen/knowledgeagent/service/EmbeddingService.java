package com.xiaozhen.knowledgeagent.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzh.BgeSmallZhEmbeddingModel;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

@Service
public class EmbeddingService {

    private EmbeddingModel embeddingModel;

    @PostConstruct
    public void init() {
        embeddingModel = new BgeSmallZhEmbeddingModel();
    }

    public float[] embed(String text) {
        Embedding embedding = embeddingModel.embed(text).content();
        return embedding.vector();
    }
}