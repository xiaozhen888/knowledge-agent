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

import java.nio.file.Path;
import java.util.*;

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
            Path modelPath = new ClassPathResource("models/reranker/model.onnx").getFile().toPath();
            Path tokenizerPath = new ClassPathResource("models/reranker/tokenizer.json").getFile().toPath();

            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));
            session = env.createSession(modelPath.toString(), opts);

            tokenizer = HuggingFaceTokenizer.builder((Map<String, ?>) tokenizerPath)
                    .optMaxLength(maxLength)
                    .optPadToMaxLength()
                    .optTruncation(true)
                    .build();

            logger.info("BGE-Reranker 加载成功，maxLength={}", maxLength);

        } catch (Exception e) {
            logger.error("Reranker 加载失败，使用降级方案", e);
            session = null;
        }
    }

    /**
     * 保持原有签名不变，内部修复batch、资源释放、异常处理
     */
    public List<ChunkResult> rerank(List<ChunkResult> candidates, String question, int topK) {
        long startTime = System.currentTimeMillis();

        if (session == null || candidates.isEmpty()) {
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }

        try {
            // 提取纯文本做 batch 推理
            List<String> contents = candidates.stream()
                    .map(ChunkResult::getContent)
                    .collect(java.util.stream.Collectors.toList());

            float[] scores = batchInfer(question, contents);

            // 分数绑回对象
            for (int i = 0; i < candidates.size(); i++) {
                candidates.get(i).setScore(scores[i]);
            }

            // 按精排分数降序
            candidates.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

            logger.info("Cross-Encoder精排完成，候选{}个，耗时{}ms",
                    candidates.size(), System.currentTimeMillis() - startTime);

            return candidates.subList(0, Math.min(topK, candidates.size()));

        } catch (Exception e) {
            logger.error("精排异常，降级返回粗排结果", e);
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }

    /**
     * 批量推理核心
     */
    private float[] batchInfer(String question, List<String> candidates) {
        int size = candidates.size();
        float[] allScores = new float[size];

        // 分批处理
        for (int i = 0; i < size; i += batchSize) {
            int end = Math.min(i + batchSize, size);
            List<String> batch = candidates.subList(i, end);
            float[] batchScores = inferBatch(question, batch);
            System.arraycopy(batchScores, 0, allScores, i, batchScores.length);
        }

        return allScores;
    }

    /**
     * 单批次ONNX推理
     */
    private float[] inferBatch(String question, List<String> batch) {
        int n = batch.size();
        long[][] inputIdsBatch = new long[n][];
        long[][] attentionMaskBatch = new long[n][];
        int maxSeqLen = 0;

        // 编码
        for (int i = 0; i < n; i++) {
            var encoding = tokenizer.encode(question, batch.get(i));
            inputIdsBatch[i] = encoding.getIds();
            attentionMaskBatch[i] = encoding.getAttentionMask();
            maxSeqLen = Math.max(maxSeqLen, inputIdsBatch[i].length);
        }

        // Padding
        long[][] paddedIds = pad(inputIdsBatch, maxSeqLen);
        long[][] paddedMask = pad(attentionMaskBatch, maxSeqLen);

        // 推理
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
            Arrays.fill(fail, -9999.0f); // 异常排末尾
            return fail;
        }
    }

    /**
     * 序列填充
     */
    private long[][] pad(long[][] sequences, int maxLen) {
        long[][] padded = new long[sequences.length][maxLen];
        for (int i = 0; i < sequences.length; i++) {
            System.arraycopy(sequences[i], 0, padded[i], 0, sequences[i].length);
        }
        return padded;
    }
}