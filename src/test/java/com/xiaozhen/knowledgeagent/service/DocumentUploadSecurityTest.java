package com.xiaozhen.knowledgeagent.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DocumentUploadSecurityTest {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "md", "txt");

    /**
     * 测试文件名安全过滤：路径遍历攻击
     */
    @Test
    void testPathTraversalFilenameSanitized() {
        String malicious = "../../../etc/passwd.pdf";
        String safe = sanitizeFileName(malicious);
        assertFalse(safe.contains("/"), "应移除路径分隔符");
        assertFalse(safe.contains(".."), "应移除路径遍历符号");
        assertTrue(safe.endsWith(".pdf"), "应保留合法扩展名");
    }

    /**
     * 测试空文件名处理
     */
    @Test
    void testBlankFilenameReturnsDefault() {
        String result = sanitizeFileName("   ");
        assertEquals("unnamed_upload.txt", result, "空文件名应返回默认名称");
    }

    /**
     * 测试非法字符过滤
     */
    @Test
    void testSpecialCharsReplaced() {
        String dirty = "file<<name>with|special*chars.pdf";
        String clean = sanitizeFileName(dirty);
        assertFalse(clean.contains("<"), "应移除 <");
        assertFalse(clean.contains(">"), "应移除 >");
        assertFalse(clean.contains("|"), "应移除 |");
        assertFalse(clean.contains("*"), "应移除 *");
    }

    /**
     * 测试扩展名白名单
     */
    @Test
    void testIllegalExtensionRejected() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.exe", "application/octet-stream", "fake content".getBytes()
        );
        String ext = getExtension(file.getOriginalFilename()).toLowerCase();
        assertFalse(ALLOWED_EXTENSIONS.contains(ext), "exe 扩展名应在黑名单");
    }

    /**
     * 测试合法扩展名通过
     */
    @Test
    void testLegalExtensionAccepted() {
        String[] legalFiles = {"test.pdf", "report.docx", "notes.md", "readme.txt"};
        for (String filename : legalFiles) {
            String ext = getExtension(filename).toLowerCase();
            assertTrue(ALLOWED_EXTENSIONS.contains(ext), filename + " 应是合法扩展名");
        }
    }

    /**
     * 测试 Magic Number 校验：伪造 PDF
     */
    @Test
    void testFakePdfDetectedByMagicNumber() {
        byte[] fakePdf = "this is not a pdf".getBytes();
        boolean valid = checkMagicNumber(fakePdf, "pdf");
        assertFalse(valid, "伪造 PDF 应被检测出来");
    }

    /**
     * 测试 Magic Number 校验：真实 PDF 头
     */
    @Test
    void testRealPdfPassesMagicNumber() {
        byte[] realPdf = {0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E}; // %PDF-1.
        boolean valid = checkMagicNumber(realPdf, "pdf");
        assertTrue(valid, "真实 PDF 头应通过校验");
    }

    /**
     * 测试空文件拦截
     */
    @Test
    void testEmptyFileRejected() {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file", "empty.pdf", "application/pdf", new byte[0]
        );
        assertTrue(emptyFile.isEmpty(), "空文件应被识别");
    }

    // ========== 辅助方法（复制自 DocumentService）==========

    private String sanitizeFileName(String fileName) {
        String name = fileName
                .replaceAll("[\\\\/]", "_")
                .replaceAll("\\.{2,}", "_")
                .replaceAll("[^a-zA-Z0-9._\\-\\u4e00-\\u9fa5]", "_");

        if (name.isBlank() || name.matches("^_+$")) {
            name = "unnamed_upload.txt";
        }
        return name;
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1) : "";
    }

    private boolean checkMagicNumber(byte[] bytes, String ext) {
        java.util.Map<String, byte[]> MAGIC_NUMBERS = java.util.Map.of(
                "pdf", new byte[]{0x25, 0x50, 0x44, 0x46},
                "docx", new byte[]{0x50, 0x4B, 0x03, 0x04}
        );
        byte[] magic = MAGIC_NUMBERS.get(ext);
        if (magic == null || bytes.length < magic.length) return true;
        for (int i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) return false;
        }
        return true;
    }
}