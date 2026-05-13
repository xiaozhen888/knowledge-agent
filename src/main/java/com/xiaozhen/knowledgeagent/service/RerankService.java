package com.xiaozhen.knowledgeagent.service;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;
import com.xiaozhen.knowledgeagent.model.ChunkResult;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RerankService {

    private static final Logger logger = LoggerFactory.getLogger(RerankService.class);

    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;

    private final EmbeddingService embeddingService;

    @Value("${reranker.model.max-length:512}")
    private int maxLength;

    @Value("${reranker.batch-size:8}")
    private int batchSize;

    public RerankService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @PostConstruct
    public void init() {
        try {
            ClassPathResource modelResource = new ClassPathResource("models/reranker/model.onnx");
            File tempModel = File.createTempFile("reranker-model", ".onnx");
            tempModel.deleteOnExit();
            try (InputStream is = modelResource.getInputStream()) {
                Files.copy(is, tempModel.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            ClassPathResource tokenizerResource = new ClassPathResource("models/reranker/tokenizer.json");
            File tempTokenizer = File.createTempFile("reranker-tokenizer", ".json");
            tempTokenizer.deleteOnExit();
            try (InputStream is = tokenizerResource.getInputStream()) {
                Files.copy(is, tempTokenizer.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }

            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
            session = env.createSession(tempModel.getAbsolutePath(), opts);

            tokenizer = HuggingFaceTokenizer.builder((Map<String, ?>) tempTokenizer.toPath())
                    .optMaxLength(maxLength)
                    .optPadToMaxLength()
                    .optTruncation(true)
                    .build();

            logger.info("BGE-Reranker Cross-Encoder 加载成功！");

        } catch (Exception e) {
            logger.error("Cross-Encoder Reranker 模型加载失败，将降级为弱精排方案", e);
            session = null;
        }
    }

    public List<ChunkResult> rerank(List<ChunkResult> candidates, String question, int topK) {
        long startTime = System.currentTimeMillis();

        if (session == null || candidates.isEmpty()) {
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }

        try {
            List<String> contents = candidates.stream()
                    .map(ChunkResult::getContent)
                    .collect(Collectors.toList());

            float[] scores = batchInfer(question, contents);

            for (int i = 0; i < candidates.size(); i++) {
                candidates.get(i).setScore(scores[i]);
            }

            double threshold = -10.0;
            List<ChunkResult> filtered = candidates.stream()
                    .filter(c -> c.getScore() > threshold)
                    .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                    .collect(Collectors.toList());

            if (filtered.isEmpty()) {
                logger.warn("精排后无有效候选（全部低于阈值 {}）", threshold);
                return new ArrayList<>();
            }

            logger.info("Cross-Encoder精排完成，候选{}个，过滤后{}个，耗时{}ms",
                    candidates.size(), filtered.size(), System.currentTimeMillis() - startTime);

            return filtered.subList(0, Math.min(topK, filtered.size()));

        } catch (Exception e) {
            logger.error("精排异常，降级返回粗排结果", e);
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }

    private float[] batchInfer(String question, List<String> candidates) {
        int size = candidates.size();
        float[] allScores = new float[size];

        for (int i = 0; i < size; i += batchSize) {
            int end = Math.min(i + batchSize, size);
            List<String> batch = candidates.subList(i, end);
            float[] batchScores = inferBatch(question, batch);
            System.arraycopy(batchScores, 0, allScores, i, batchScores.length);
        }

        return allScores;
    }

    private float[] inferBatch(String question, List<String> batch) {
        int n = batch.size();
        long[][] inputIdsBatch = new long[n][];
        long[][] attentionMaskBatch = new long[n][];
        int maxSeqLen = 0;

        for (int i = 0; i < n; i++) {
            var encoding = tokenizer.encode(question, batch.get(i));
            inputIdsBatch[i] = encoding.getIds();
            attentionMaskBatch[i] = encoding.getAttentionMask();
            maxSeqLen = Math.max(maxSeqLen, inputIdsBatch[i].length);
        }

        long[][] paddedIds = pad(inputIdsBatch, maxSeqLen);
        long[][] paddedMask = pad(attentionMaskBatch, maxSeqLen);

        try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(env, paddedIds);
             OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(env, paddedMask);
             OrtSession.Result result = session.run(Map.of(
                     "input_ids", inputIdsTensor,
                     "attention_mask", attentionMaskTensor
             ))) {

            float[][] outputs = (float[][]) result.get(0).getValue();
            float[] scores = new float[n];
            for (int i = 0; i < n; i++) {
                scores[i] = outputs[i][0];
            }
            return scores;

        } catch (OrtException e) {
            logger.error("ONNX batch推理失败", e);
            float[] fail = new float[n];
            Arrays.fill(fail, -9999.0f);
            return fail;
        }
    }

    private long[][] pad(long[][] sequences, int maxLen) {
        long[][] padded = new long[sequences.length][maxLen];
        for (int i = 0; i < sequences.length; i++) {
            System.arraycopy(sequences[i], 0, padded[i], 0, sequences[i].length);
        }
        return padded;
    }
}