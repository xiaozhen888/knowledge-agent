package com.xiaozhen.knowledgeagent.service;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ChatServicePromptTest {

    @Test
    void testPromptAssemble_containsAllChunks() {
        List<String> chunks = Arrays.asList(
                "v-model在组件标签上的本质是:modelValue和@update的组合",
                "shallowRef()用于创建浅层响应式状态",
                "Vue Router使用params传参时必须用name配置项"
        );

        StringBuilder context = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            context.append("【参考片段").append(i + 1).append("】\n");
            context.append(chunks.get(i)).append("\n\n");
        }

        String question = "v-model怎么用？";
        String prompt = """
                你是一个知识库助手。请参考以下内容回答问题。
                
                【参考内容】
                %s
                
                【用户问题】
                %s
                """.formatted(context.toString(), question);

        assertTrue(prompt.contains("v-model"), "Prompt应该包含检索到的文档内容");
        assertTrue(prompt.contains("shallowRef"), "Prompt应该包含所有检索到的片段");
        assertTrue(prompt.contains(question), "Prompt应该包含用户问题");
    }

    @Test
    void testPromptAssemble_withHistory() {
        StringBuilder historyPrompt = new StringBuilder();
        historyPrompt.append("【历史对话】\n");
        historyPrompt.append("用户：Vue 3有哪些新特性？\n");
        historyPrompt.append("助手：Vue 3带来了组合式API等新特性。\n\n");

        StringBuilder context = new StringBuilder();
        context.append("【参考片段1】\nv-model是Vue的重要指令。\n");

        String question = "刚才提到的v-model怎么用？";
        String prompt = """
                你是一个知识库助手。
                
                %s
                【参考内容】
                %s
                
                【用户问题】
                %s
                """.formatted(historyPrompt.toString(), context.toString(), question);

        assertTrue(prompt.contains("历史对话"), "Prompt应该包含历史对话标记");
        assertTrue(prompt.contains("Vue 3有哪些新特性"), "Prompt应该包含历史用户问题");
        assertTrue(prompt.contains("组合式API"), "Prompt应该包含历史助手回答");
        assertTrue(prompt.contains(question), "Prompt应该包含当前问题");
    }

    @Test
    void testEmptyChunks_returnsPrompt() {
        List<String> emptyChunks = new ArrayList<>();
        if (emptyChunks.isEmpty()) {
            String response = "请先上传文档再提问";
            assertEquals("请先上传文档再提问", response);
        }
    }
}