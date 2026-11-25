package cn.gson.financial.ai;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * FN-AI-T3（187）— PDF 解析（<b>内存处理，不落盘</b>）。
 *
 * <p>策略：先用 {@link PDFTextStripper} 抽文本——抽得到（非扫描件）→ 走<b>文本路径</b>；
 * 抽不到（扫描件/图片型 PDF）→ {@link PDFRenderer} 把前 N 页渲染成 PNG → 走 <b>vision 路径</b>。
 * 多页文本合并；扫描件只取前 {@link #MAX_SCAN_PAGES} 页（控成本/时延）。
 */
@Slf4j
@Component
public class PdfExtractor {

    /** 扫描件最多渲染前 N 页走 vision（187 拍板：前 3 页）。 */
    static final int MAX_SCAN_PAGES = 3;
    /** 渲染 DPI（清晰度 vs 体积折中）。 */
    static final int RENDER_DPI = 144;
    /** 抽到的（去空白）文本达到该长度才视为"文本型 PDF"，否则当扫描件渲染。 */
    static final int TEXT_MIN_CHARS = 10;

    /**
     * 解析结果：{@code text} 非空 → 文本型（并入文本路径）；{@code pageImagesBase64} 非空 → 扫描件渲染图（走 vision）。
     * 二者互斥（文本型不渲染、扫描件文本为空）。
     */
    public record PdfContent(String text, List<String> pageImagesBase64, int totalPages, int renderedPages) {
        /** 兼容旧 2 参构造（测试/老调用），页数信息未知置 0。 */
        public PdfContent(String text, List<String> pageImagesBase64) {
            this(text, pageImagesBase64, 0, 0);
        }

        boolean hasText() {
            return text != null && !text.isBlank();
        }

        boolean hasImages() {
            return pageImagesBase64 != null && !pageImagesBase64.isEmpty();
        }

        /** 扫描件实际页数超过已渲染页数 → 有内容被截断（调用方应提醒人工）。 */
        boolean scanTruncated() {
            return renderedPages > 0 && totalPages > renderedPages;
        }
    }

    /**
     * 抽 PDF 内容。解析失败抛 {@link PdfExtractException}（调用方按文件粒度降级，不阻断其它输入）。
     */
    public PdfContent extract(byte[] pdfBytes) {
        if (pdfBytes == null || pdfBytes.length == 0) {
            throw new PdfExtractException("PDF 内容为空");
        }
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            String text = new PDFTextStripper().getText(doc);
            if (text != null && text.replaceAll("\\s", "").length() >= TEXT_MIN_CHARS) {
                return new PdfContent(text.trim(), List.of());
            }
            // 扫描件：渲染前 N 页为 PNG base64
            int totalPages = doc.getNumberOfPages();
            PDFRenderer renderer = new PDFRenderer(doc);
            int pages = Math.min(totalPages, MAX_SCAN_PAGES);
            List<String> imgs = new ArrayList<>(pages);
            for (int i = 0; i < pages; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, RENDER_DPI, ImageType.RGB);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);
                imgs.add(Base64.getEncoder().encodeToString(baos.toByteArray()));
            }
            if (imgs.isEmpty()) {
                throw new PdfExtractException("PDF 无文本且无可渲染页");
            }
            log.info("[FN-AI-T3] PDF 扫描件渲染 {}/{} 页走 vision", imgs.size(), totalPages);
            return new PdfContent("", imgs, totalPages, imgs.size());
        } catch (IOException e) {
            throw new PdfExtractException("PDF 解析失败：" + e.getMessage(), e);
        }
    }

    /** PDF 解析失败（损坏/加密/非 PDF）；调用方按文件粒度兜底（warnings + 继续），不全局 500。 */
    public static class PdfExtractException extends RuntimeException {
        public PdfExtractException(String message) {
            super(message);
        }

        public PdfExtractException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
