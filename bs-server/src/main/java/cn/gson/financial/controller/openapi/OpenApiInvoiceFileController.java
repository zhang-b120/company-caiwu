package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.controller.UploadController;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.InvoiceCollectRequest;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.repo.InvoiceCollectRequestRepository;
import cn.gson.financial.security.SecurityConstants;
import cn.gson.financial.service.InvoiceFileStorageService;
import cn.hutool.core.io.file.FileNameUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * FN-DLV-T1（182）— 发票文件「上传中心存储 + 回写可访问 URL」端点。
 *
 * <p>开票成功后 BT 把 PDF/OFD/XML(+二维码) 上传到此处中心存储，回写 collect {@code /result} 的
 * {@code files} 改成可访问 URL，财务/收银端/客户凭 URL 在线下票。
 *
 * <ul>
 *   <li><b>上传</b> {@code POST /openapi/v1/invoice-files/upload}（multipart：{@code file}+{@code orderNo}+{@code kind}）
 *       —— <b>X-Internal-Token 硬挡</b>（与 claim/result 同款，配置为空也 403），存到
 *       {@code invoice-files.root\<orderNo>\<kind>.<ext>}，返 {@code {url, kind, orderNo}}。</li>
 *   <li><b>下载</b> {@code GET /invoice-files/**} —— 仿 {@code UploadController.ocScreenshots}，复用
 *       {@code UploadController.getResponseEntity}；<b>canonical 校验拦 {@code ../}</b>。
 *       公网经 8888 {@code /api/invoice-files/...} 反代到此端点。</li>
 * </ul>
 *
 * <p>鉴权抉择（见 182 风险 2）：上传端点必带 token；<b>下载端点沿用 OC 截图开放范式</b>（{@code @SaIgnore}，靠订单号不可猜），
 * 以便浏览器/收银端/客户直接用 URL 下票——若日后要更强保护，可叠加签名 URL（不改本端点契约）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@SaIgnore
public class OpenApiInvoiceFileController {

    private final InvoiceFileStorageService storageService;
    /** FN-DLV-FILENAME（262）：下载时按 orderNo 查发票记录拼业务文件名（发票号/销方简称）。 */
    private final InvoiceCollectRequestRepository collectRepo;
    /** FN-DLV-FILENAME（262）：按 accountSetsId 取销方单位名作文件名里的「销方简称」。 */
    private final AccountSetsRepository accountSetsRepo;

    /** 上传端点 X-Internal-Token（与 {@code /tax-credentials}、claim/result 同款；配置为空一律 403）。 */
    @Value("${financial.scan-direct.internal-token:}")
    String internalToken;

    private static final String DOWNLOAD_PREFIX = "/invoice-files/";

    /** 文件名里非法/危险字符（Windows 保留 + 控制字符 + 路径分隔）→ 一律去掉，杜绝下载名越界/异常。 */
    private static final String ILLEGAL_FILENAME_CHARS = "[\\\\/:*?\"<>|\\u0000-\\u001F]";
    /** 单个名字段（发票号/销方简称）最长，避免下载名过长被客户端截断。 */
    private static final int MAX_NAME_SEGMENT = 40;

    /**
     * 上传发票文件。multipart：{@code file}（必填）+ {@code orderNo}（必填）+ {@code kind}（pdf/ofd/xml/qrcode）。
     */
    @PostMapping("/openapi/v1/invoice-files/upload")
    public JsonResult upload(@RequestParam(value = "file", required = false) MultipartFile file,
                             @RequestParam(value = "orderNo", required = false) String orderNo,
                             @RequestParam(value = "kind", required = false) String kind,
                             HttpServletRequest request,
                             HttpServletResponse response) {
        // 敏感端点硬挡：缺/错/未配置 token → 403
        if (!internalTokenOk(request.getHeader(SecurityConstants.INTERNAL_TOKEN_HEADER))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            log.warn("[FN-DLV-T1] invoice-files/upload 拒绝：X-Internal-Token 缺失/不匹配/未配置 orderNo={}", orderNo);
            return JsonResult.failure("X-Internal-Token 缺失或不匹配", 403);
        }
        try {
            InvoiceFileStorageService.StoredFile stored = storageService.store(orderNo, kind, file);
            // 270：evidence 上传成功 → 回写 collect.evidence_url（同 qr_url/face_auth_qr_url 落库范式）。
            // best-effort：文件已落盘，回写列失败只 warn 不致上传失败（buildListRow 列空仍 stat 兜底）。
            if ("evidence".equals(stored.kind)) {
                try {
                    collectRepo.findByOrderNo(stored.orderNo).ifPresent(e -> {
                        e.setEvidenceUrl(stored.url);
                        collectRepo.save(e);
                    });
                } catch (Exception ex) {
                    log.warn("[FN-EVIDENCE-URL] evidence_url 回写失败 orderNo={}（文件已存，列空走 stat 兜底）：{}",
                            stored.orderNo, ex.getMessage());
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("url", stored.url);
            data.put("kind", stored.kind);
            data.put("orderNo", stored.orderNo);
            return JsonResult.successful(data);
        } catch (IllegalArgumentException e) {
            // 入参非法（orderNo/kind/file）→ 400 VALIDATION
            log.warn("[FN-DLV-T1] invoice-files/upload 参数非法 orderNo={} kind={}：{}", orderNo, kind, e.getMessage());
            return JsonResult.failure(e.getMessage(), 400).setData(Map.of("errorType", "VALIDATION"));
        } catch (IOException e) {
            // 落盘失败 → 500；交付≠开票（红线在 BT 侧兜底重试/留待补传，FN 仅如实报错）
            log.error("[FN-DLV-T1] invoice-files/upload 落盘失败 orderNo={} kind={}", orderNo, kind, e);
            return JsonResult.failure("文件存储失败：" + e.getMessage(), 500).setData(Map.of("errorType", "STORAGE"));
        }
    }

    /**
     * 下载发票文件。公网经 8888 {@code /api/invoice-files/<orderNo>/<文件名>} 反代到此。
     * 仿 {@code UploadController.ocScreenshots}，canonical 校验拦路径穿越。
     */
    @GetMapping("/invoice-files/**")
    public ResponseEntity<byte[]> download(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        int idx = servletPath.indexOf(DOWNLOAD_PREFIX);
        if (idx < 0) return ResponseEntity.notFound().build();
        String subPath = servletPath.substring(idx + DOWNLOAD_PREFIX.length());
        File file = storageService.resolveForDownload(subPath);
        if (file == null) {
            // 路径穿越或解析失败 → 403（拦 ../ 任意文件读取）
            return ResponseEntity.status(403).build();
        }
        // FN-DLV-FILENAME（262）：传一个确定的业务文件名（带正确扩展名），不再传 null → 下载名退成存储名 pdf.pdf
        // 被客户端/8888 代理猜成 f.txt。getResponseEntity 据此设 Content-Disposition（PDF/图片 inline 保预览）。
        String downloadName = buildDownloadName(subPath, file);
        return UploadController.getResponseEntity(downloadName, file);
    }

    /**
     * FN-DLV-FILENAME（262）构造下载文件名（B 档·业务友好，A 档兜底）：
     * <ul>
     *   <li><b>B 档</b>（命中发票记录）：{@code 发票_<发票号码>_<销方简称>.<ext>}；发票号缺 → 退回执号 → 退 orderNo；
     *       销方简称取不到则省略该段。</li>
     *   <li><b>A 档兜底</b>（查不到记录/异常）：{@code <orderNo>_<kind>.<ext>}（如 {@code ORD-123_pdf.pdf}），
     *       至少消掉 f.txt/丢扩展名。</li>
     * </ul>
     * 🟥 不动存储布局/内容，文件名是下载时即时构造（与磁盘存储名解耦，历史文件下载也拿到正确名）。
     */
    String buildDownloadName(String subPath, File file) {
        String storedName = file.getName();                       // <kind>.<ext>（兜底用）
        String ext = FileNameUtil.getSuffix(storedName);          // pdf/ofd/xml/png
        String orderNo = firstSegment(subPath);
        // A 档兜底名（无 orderNo 时退到存储名）
        String fallback = StrUtil.isBlank(orderNo)
                ? storedName
                : sanitize(orderNo) + "_" + storedName;
        if (StrUtil.isBlank(orderNo)) return fallback;
        try {
            Optional<InvoiceCollectRequest> opt = collectRepo.findByOrderNo(orderNo);
            if (opt.isEmpty()) return fallback;                   // 查不到 → A 档
            InvoiceCollectRequest e = opt.get();
            String invoiceNo = StrUtil.firstNonBlank(e.getInvoiceNumber(), e.getReceiptNo(), orderNo);
            String seller = resolveSellerShort(e.getAccountSetsId());
            StringBuilder sb = new StringBuilder("发票_").append(sanitize(invoiceNo));
            if (StrUtil.isNotBlank(seller)) {
                sb.append("_").append(sanitize(seller));
            }
            sb.append(".").append(StrUtil.isBlank(ext) ? "dat" : ext.toLowerCase());
            return sb.toString();
        } catch (Exception ex) {
            // 查库/拼名出任何意外都不能让下载挂掉——退 A 档兜底名
            log.warn("[FN-DLV-FILENAME] 构造下载文件名失败 subPath={}，退回兜底名 {}", subPath, fallback, ex);
            return fallback;
        }
    }

    /** 销方简称：按 accountSetsId 取账套单位名（取不到/异常 → null，文件名省略该段）。 */
    private String resolveSellerShort(Long accountSetsId) {
        if (accountSetsId == null) return null;
        try {
            return accountSetsRepo.findById(accountSetsId)
                    .map(cn.gson.financial.kernel.model.entity.AccountSets::getCompanyName)
                    .filter(StrUtil::isNotBlank)
                    .orElse(null);
        } catch (Exception ex) {
            log.warn("[FN-DLV-FILENAME] 取销方单位名失败 accountSetsId={}", accountSetsId, ex);
            return null;
        }
    }

    /** subPath（{@code <orderNo>/<kind>.<ext>}）取首段 orderNo。 */
    private static String firstSegment(String subPath) {
        if (StrUtil.isBlank(subPath)) return null;
        String s = subPath.replace('\\', '/');
        int slash = s.indexOf('/');
        return slash > 0 ? s.substring(0, slash) : (slash < 0 ? s : null);
    }

    /** 去文件名非法字符 + 折叠空白 + 截断，保中文（UTF-8 由 ContentDisposition 编码）。 */
    private static String sanitize(String raw) {
        if (StrUtil.isBlank(raw)) return "";
        String v = raw.replaceAll(ILLEGAL_FILENAME_CHARS, "").replaceAll("\\s+", " ").trim();
        if (v.length() > MAX_NAME_SEGMENT) v = v.substring(0, MAX_NAME_SEGMENT);
        return v;
    }

    /**
     * 内部 token 校验：配置非空且 header 匹配才放行；<b>配置为空也拒</b>（敏感端点不裸奔）。
     * 与 {@code OpenApiTaxCredentialsController.internalTokenOk} 同口径。
     */
    boolean internalTokenOk(String token) {
        if (internalToken == null || internalToken.isBlank()) {
            log.error("[FN-DLV-T1] financial.scan-direct.internal-token 未配置，invoice-files/upload 一律 403");
            return false;
        }
        return internalToken.equals(token);
    }
}
