package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.FileInfo;
import cn.gson.financial.kernel.model.entity.QFileInfo;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.FileInfoRepository;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.querydsl.core.BooleanBuilder;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * 开放 API — 银行流水文件查询（BT 审核工作站 + XW AI 解析链路用）。
 * <p>
 * 路径前缀 {@code /openapi/v1/bank/files}。仅过滤 type=bankStatement，
 * 与通用的 {@link OpenApiFileController} 区分：
 *   - 通用 /files 接受多种文件类型 + 复杂过滤
 *   - 本控制器仅 bankStatement 类型，响应字段对齐 BT/XW spec
 * <p>
 * 4 个端点：
 *   GET   /                        当期文件列表（按 year/month）
 *   GET   /{fileId}/download       二进制下载（流式）
 *   PATCH /{fileId}/mark-parsed    标记已解析（记录条数）
 *   PATCH /{fileId}/unmark-parsed  取消已解析标记（重置 isDeal + 清解析条数；幂等）
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/bank/files")
@SaIgnore
public class OpenApiBankFilesController {

    private final FileInfoRepository fileInfoRepository;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    @Value("${customize-config.screen-bank-path:}")
    private String screenBankPath;

    @Value("${customize-config.base-path:}")
    private String basePath;

    private static final QFileInfo qFileInfo = QFileInfo.fileInfo;
    private static final String BANK_STATEMENT_TYPE = "bankStatement";

    // ═══════════════════════════════════════════════════════════════
    // 1. GET /  — 当期银行流水文件列表
    // ═══════════════════════════════════════════════════════════════

    @GetMapping
    public JsonResult list(@RequestParam Long accountSetsId,
                           @RequestParam(required = false) Integer year,
                           @RequestParam(required = false) Integer month,
                           @RequestParam(required = false) Boolean parsed) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");

        BooleanBuilder where = new BooleanBuilder();
        where.and(qFileInfo.accountSetsId.eq(accountSetsId));
        where.and(qFileInfo.type.eq(BANK_STATEMENT_TYPE));
        where.and(qFileInfo.isDelete.eq(0).or(qFileInfo.isDelete.isNull()));

        // year+month 二选一传：传了就过滤到指定月；都不传 = 全量
        if (year != null && month != null) {
            try {
                LocalDate period = LocalDate.of(year, month, 1);
                where.and(qFileInfo.accountSetPeriod.eq(period));
            } catch (Exception e) {
                return JsonResult.failure("year/month 非法：" + year + "-" + month);
            }
        } else if (year != null || month != null) {
            return JsonResult.failure("year 和 month 必须同时传或同时不传");
        }

        if (parsed != null) {
            if (parsed) where.and(qFileInfo.isDeal.eq(1));
            else        where.and(qFileInfo.isDeal.eq(0).or(qFileInfo.isDeal.isNull()));
        }

        List<FileInfo> rows = bqf.selectFrom(qFileInfo)
                .where(where)
                .orderBy(qFileInfo.createTime.desc(), qFileInfo.id.desc())
                .fetch();

        List<Map<String, Object>> files = new ArrayList<>(rows.size());
        for (FileInfo f : rows) files.add(toDto(f));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("files", files);
        data.put("total", files.size());
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. GET /{fileId}/download  — 二进制下载
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/{fileId}/download")
    public void download(@PathVariable Long fileId,
                         @RequestParam Long accountSetsId,
                         HttpServletResponse response) throws IOException {
        if (accountSetsId == null) {
            writeJsonError(response, 400, "accountSetsId 不能为空");
            return;
        }
        FileInfo fi = fileInfoRepository.findById(new PrimaryKey(fileId, accountSetsId)).orElse(null);
        if (fi == null || Integer.valueOf(1).equals(fi.getIsDelete())) {
            writeJsonError(response, 404, "文件不存在或已删除：" + fileId);
            return;
        }
        if (!BANK_STATEMENT_TYPE.equalsIgnoreCase(fi.getType())) {
            writeJsonError(response, 400,
                    "type 非 bankStatement，不在本接口受理范围：" + fi.getType());
            return;
        }

        File physical = resolvePhysicalFile(fi);
        if (physical == null || !physical.exists() || !physical.isFile()) {
            log.warn("openapi bank/files download 物理文件不存在: fileId={} path={}",
                    fileId, physical == null ? null : physical.getAbsolutePath());
            writeJsonError(response, 404, "服务端物理文件丢失：" + fi.getFileName());
            return;
        }

        response.setContentType(guessMimeType(fi.getFileSuffix()));
        response.setContentLengthLong(physical.length());
        String displayName = StrUtil.isNotBlank(fi.getOriginalFileName())
                ? fi.getOriginalFileName() : fi.getFileName();
        String encoded = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        response.setHeader("X-File-Id", String.valueOf(fileId));
        response.setHeader("X-File-Type", "bank_statement");

        try (FileInputStream in = new FileInputStream(physical)) {
            IoUtil.copy(in, response.getOutputStream());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. PATCH /{fileId}/mark-parsed  — 标记已解析 + 记录条数
    // ═══════════════════════════════════════════════════════════════

    @PatchMapping("/{fileId}/mark-parsed")
    @Transactional
    public JsonResult markParsed(@PathVariable Long fileId,
                                 @RequestParam Long accountSetsId,
                                 @RequestBody(required = false) MarkParsedReq req) {
        if (accountSetsId == null) return failureWithType("VALIDATION_ERROR", "accountSetsId 不能为空");

        FileInfo fi = fileInfoRepository.findById(new PrimaryKey(fileId, accountSetsId)).orElse(null);
        if (fi == null) return failureWithType("NOT_FOUND", "文件不存在或不属于该账套：" + fileId);
        if (Integer.valueOf(1).equals(fi.getIsDelete())) {
            return failureWithType("ALREADY_DELETED", "文件已删除：" + fileId);
        }
        if (!BANK_STATEMENT_TYPE.equalsIgnoreCase(fi.getType())) {
            return failureWithType("VALIDATION_ERROR",
                    "type 非 bankStatement，不在本接口受理范围：" + fi.getType());
        }

        fi.setIsDeal(1);
        // 用 business_code 记录已解析的条数（int 形式，便于后续读出来做幂等判断）。
        // 注意：此字段在 OpenApiFileController.mark-parsed 路径下被用于存 linkedRecordIds（逗号串），
        // 两个接口语义不同；银行流水走本接口，不会混用。
        if (req != null && req.getParsedStatementCount() != null && req.getParsedStatementCount() >= 0) {
            fi.setBusinessCode(String.valueOf(req.getParsedStatementCount()));
        }
        fileInfoRepository.save(fi);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileId", fileId);
        data.put("parsed", true);
        data.put("parsedStatementCount", parseCount(fi.getBusinessCode()));
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. PATCH /{fileId}/unmark-parsed  — 取消已解析标记（XW 重跑 / 误标修正用）
    //
    // FN-NEW-BANK-FILE-UNMARK：之前 parsed=true 后只能改 DB 重置，给 XW 一个
    // 显式 endpoint 翻 isDeal=0 + 清掉 business_code（解析条数），幂等。
    // ═══════════════════════════════════════════════════════════════

    @PatchMapping("/{fileId}/unmark-parsed")
    @Transactional
    public JsonResult unmarkParsed(@PathVariable Long fileId,
                                   @RequestParam Long accountSetsId) {
        if (accountSetsId == null) return failureWithType("VALIDATION_ERROR", "accountSetsId 不能为空");

        FileInfo fi = fileInfoRepository.findById(new PrimaryKey(fileId, accountSetsId)).orElse(null);
        if (fi == null) return failureWithType("NOT_FOUND", "文件不存在或不属于该账套：" + fileId);
        if (Integer.valueOf(1).equals(fi.getIsDelete())) {
            return failureWithType("ALREADY_DELETED", "文件已删除：" + fileId);
        }
        if (!BANK_STATEMENT_TYPE.equalsIgnoreCase(fi.getType())) {
            return failureWithType("VALIDATION_ERROR",
                    "type 非 bankStatement，不在本接口受理范围：" + fi.getType());
        }

        boolean wasParsed = Integer.valueOf(1).equals(fi.getIsDeal());
        Integer previousCount = parseCount(fi.getBusinessCode());

        // 幂等：未解析状态再调一次仍返 success，但不重复 save
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileId", fileId);
        data.put("parsed", false);
        data.put("previousParsedCount", previousCount);
        data.put("alreadyUnmarked", !wasParsed);

        if (wasParsed) {
            fi.setIsDeal(0);
            // 清掉解析条数（与 mark-parsed 复用同字段，必须配套清掉避免错乱）
            fi.setBusinessCode(null);
            fileInfoRepository.save(fi);
        }
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    private Map<String, Object> toDto(FileInfo f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("fileName", StrUtil.isNotBlank(f.getOriginalFileName())
                ? f.getOriginalFileName() : f.getFileName());
        // 银行流水：上传时 businessName 存的是 bankName
        m.put("bankName", f.getBusinessName());
        m.put("bankAccount", null);   // FileInfo 实体不存银行账号；BT 解析文件内容时识别
        m.put("uploadAt", f.getCreateTime());
        m.put("size", resolveFileSize(f));    // 读物理文件大小（每行一次 IO；银行流水通常 ≤ 12 个/月）
        m.put("parsed", Integer.valueOf(1).equals(f.getIsDeal()));
        m.put("parsedStatementCount", parseCount(f.getBusinessCode()));
        m.put("parsedAt", null);              // FileInfo 没存"标记时间"，已在 spec 中标注 optional
        m.put("period", f.getAccountSetPeriod() != null
                ? String.format("%04d-%02d", f.getAccountSetPeriod().getYear(), f.getAccountSetPeriod().getMonthValue())
                : null);
        m.put("downloadUrl", "/openapi/v1/bank/files/" + f.getId()
                + "/download?accountSetsId=" + f.getAccountSetsId());
        return m;
    }

    /** package-private hook：测试覆写避开物理文件依赖 */
    Long resolveFileSize(FileInfo f) {
        File physical = resolvePhysicalFile(f);
        if (physical == null || !physical.exists() || !physical.isFile()) return null;
        return physical.length();
    }

    /** 与 OpenApiBankStatementController.resolveSaveDir 一致的目录解析 */
    private File resolvePhysicalFile(FileInfo fi) {
        if (StrUtil.isBlank(fi.getFileName())) return null;
        String dir;
        if (StrUtil.isNotBlank(screenBankPath)) {
            dir = screenBankPath;
        } else if (StrUtil.isNotBlank(basePath)) {
            String sep = basePath.endsWith("/") || basePath.endsWith("\\") ? "" : File.separator;
            dir = basePath + sep + "bank-statements";
        } else {
            return null;
        }
        return new File(dir, fi.getFileName());
    }

    private static Integer parseCount(String s) {
        if (StrUtil.isBlank(s)) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String guessMimeType(String suffix) {
        if (StrUtil.isBlank(suffix)) return "application/octet-stream";
        return switch (suffix.trim().toLowerCase()) {
            case "pdf" -> "application/pdf";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls" -> "application/vnd.ms-excel";
            case "csv" -> "text/csv";
            case "ofd" -> "application/ofd";
            default -> "application/octet-stream";
        };
    }

    private static JsonResult failureWithType(String errorType, String msg) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", errorType);
        return JsonResult.failure(msg).setData(data);
    }

    private static void writeJsonError(HttpServletResponse response, int httpStatus, String msg) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json; charset=UTF-8");
        String body = "{\"success\":false,\"code\":-1,\"msg\":\""
                + msg.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"data\":null}";
        response.getWriter().write(body);
    }

    @Data
    public static class MarkParsedReq {
        /** 解析出的流水条数（非负数）。null/负数 → 不更新该字段 */
        private Integer parsedStatementCount;
    }
}
