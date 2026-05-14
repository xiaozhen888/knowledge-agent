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

import java.io.FileNotFoundException;
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

    @Value("${reranker.batch-size:32}")
    private int batchSize;

    public RerankService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    @PostConstruct
    public void init() {
        try {
            // 模型直接从磁盘读，不要从 classpath 复制到 /tmp
            File modelFile = new File("/app/models/reranker/model.onnx");
            if (!modelFile.exists()) {
                throw new FileNotFoundException("模型文件不存在: " + modelFile.getAbsolutePath());
            }

            // tokenizer 没有外部数据文件，继续从 classpath 复制到 /tmp 没问题，保持不动
            File tokenizerFile = new File("/app/models/reranker/tokenizer.json");
            if (!tokenizerFile.exists()) {
                throw new FileNotFoundException("tokenizer文件不存在: " + tokenizerFile.getAbsolutePath());
            }

            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(Math.max(1, Runtime.getRuntime().availableProcessors() / 2));

            // 用 modelFile
            session = env.createSession(modelFile.getAbsolutePath(), opts);

            // ===== 加这段 debug =====
            logger.debug("ONNX 模型输入节点:");
            for (Map.Entry<String, NodeInfo> entry : session.getInputInfo().entrySet()) {
                logger.debug("  {} -> {}", entry.getKey(), entry.getValue());
            }
            logger.debug("ONNX 模型输出节点:");
            for (Map.Entry<String, NodeInfo> entry : session.getOutputInfo().entrySet()) {
                logger.debug("  {} -> {}", entry.getKey(), entry.getValue());
            }
            // =======================

            tokenizer = HuggingFaceTokenizer.builder()
                    .optTokenizerPath(tokenizerFile.toPath())
                    .optMaxLength(maxLength)
                    .optPadToMaxLength()
                    .optTruncation(true)
                    .build();

            logger.debug("BGE-Reranker Cross-Encoder 加载成功！");

        } catch (Exception e) {
            logger.error("Cross-Encoder Reranker 模型加载失败，将降级为弱精排方案", e);
            session = null;
        }
    }

    public List<ChunkResult> rerank(List<ChunkResult> candidates, String question, int topK) {
        long startTime = System.currentTimeMillis();

        if (session == null || candidates.isEmpty()) {
            return candidates.stream()
                    .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                    .limit(Math.min(topK, candidates.size()))
                    .collect(Collectors.toList());
        }

        try {
            List<String> contents = candidates.stream()
                    .map(ChunkResult::getContent)
                    .collect(Collectors.toList());

            float[] scores = batchInfer(question, contents);

            for (int i = 0; i < candidates.size(); i++) {
                candidates.get(i).setScore(scores[i]);
            }

            // 直接按分数排序取 TopK，不再阈值过滤
            List<ChunkResult> sorted = candidates.stream()
                    .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                    .limit(Math.min(topK, candidates.size()))
                    .collect(Collectors.toList());

            logger.debug("Cross-Encoder精排完成，候选{}个，取Top{}，耗时{}ms",
                    candidates.size(), sorted.size(), System.currentTimeMillis() - startTime);

            if (!sorted.isEmpty()) {
                logger.debug("精排分数分布: Top1={}, Top3={}, Top5={}",
                        sorted.get(0).getScore(),
                        sorted.size() > 2 ? sorted.get(2).getScore() : "N/A",
                        sorted.size() > 4 ? sorted.get(4).getScore() : "N/A");
            }

            return sorted;

        } catch (Exception e) {
            logger.error("精排异常，降级返回粗排结果", e);
            return candidates.stream()
                    .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                    .limit(Math.min(topK, candidates.size()))
                    .collect(Collectors.toList());
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
            // BGE-Reranker 标准格式: question</s></s>passage
            String pairText = question + "</s></s>" + batch.get(i);
            var encoding = tokenizer.encode(pairText);
            // ===== debug: 打印 tokenizer 输出 =====
            logger.debug("tokenizer[{}] length={}, firstId={}, lastId={}",
                    i, encoding.getIds().length, encoding.getIds()[0],
                    encoding.getIds()[encoding.getIds().length - 1]);
            logger.debug("【Reranker】实际 encode 长度: {}, maxLength配置: {}",
                    encoding.getIds().length, maxLength);
            inputIdsBatch[i] = encoding.getIds();
            attentionMaskBatch[i] = encoding.getAttentionMask();
            maxSeqLen = Math.max(maxSeqLen, inputIdsBatch[i].length);
        }

        long[][] paddedIds = pad(inputIdsBatch, maxSeqLen);
        long[][] paddedMask = pad(attentionMaskBatch, maxSeqLen);

        // ===== debug: 打印 ONNX 节点名 =====
        logger.debug("ONNX input names: {}", session.getInputNames());
        logger.debug("ONNX output names: {}", session.getOutputNames());

        try (OnnxTensor inputIdsTensor = OnnxTensor.createTensor(env, paddedIds);
             OnnxTensor attentionMaskTensor = OnnxTensor.createTensor(env, paddedMask);
             OrtSession.Result result = session.run(Map.of(
                     "input_ids", inputIdsTensor,
                     "attention_mask", attentionMaskTensor
             ))) {

            // ===== debug: 打印输出形状和类型 =====
            OnnxValue output = result.get(0);
            logger.debug("ONNX output type={}, info={}", output.getType(), output.getInfo());

            Object rawValue = output.getValue();
            logger.debug("ONNX raw value class={}", rawValue.getClass().getName());

            // 尝试安全解析
            float[] scores = new float[n];
            if (rawValue instanceof float[][]) {
                float[][] outputs = (float[][]) rawValue;
                for (int i = 0; i < n; i++) {
                    scores[i] = outputs[i][0];
                    logger.debug("score[{}]={}", i, scores[i]);
                }
            } else if (rawValue instanceof float[]) {
                float[] outputs = (float[]) rawValue;
                for (int i = 0; i < n; i++) {
                    scores[i] = outputs[i];
                    logger.debug("score[{}]={}", i, scores[i]);
                }
            } else {
                logger.error("未知的 ONNX 输出格式: {}", rawValue);
                Arrays.fill(scores, -9999.0f);
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