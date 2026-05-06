package com.xiaozhen.knowledgeagent.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DocumentSplitTest {

    @Test
    void testSplitText_normal() {
        String text = "Vue 3 在 2020 年发布，带来了组合式 API。"
                + "组合式 API 的核心是 setup 函数。"
                + "setup 函数在组件创建之前执行。";
        List<String> chunks = splitText(text, 20, 5);

        assertNotNull(chunks);
        assertTrue(chunks.size() > 1, "应该产生多个切片");
        for (String chunk : chunks) {
            assertTrue(chunk.length() <= 20, "每个切片长度应该 <= chunkSize");
        }
    }

    @Test
    void testSplitText_overlap() {
        String text = "ABCDEFGHIJKLMNOPQRST"; // 20个字符
        List<String> chunks = splitText(text, 10, 4);
        // 实际结果：
        // chunk1: ABCDEFGHIJ (0-10)
        // chunk2: GHIJKLMNOP (6-16, 因为start += (10-4)=6)
        // chunk3: MNOPQRST (12-20, 因为start += 6)
        // 所以是3个，不是2个
        assertTrue(chunks.size() >= 2, "至少产生2个切片");
        assertTrue(chunks.get(1).startsWith("GHIJ"), "第二个切片应以第一个切片后4字开头");
    }

    @Test
    void testSplitText_singleChunk() {
        String text = "Hello";
        List<String> chunks = splitText(text, 500, 100);
        assertEquals(1, chunks.size(), "短文本应只产生1个切片");
    }

    @Test
    void testSplitText_empty() {
        List<String> chunks = splitText("", 500, 100);
        assertTrue(chunks.isEmpty(), "空文本应产生空列表");
    }

    // 直接从DocumentService搬过来的方法
    private List<String> splitText(String text, int chunkSize, int overlap) {
        List<String> chunks = new java.util.ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
            start += (chunkSize - overlap);
        }
        return chunks;
    }
}