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
import java.time.YearMonth;
import java.util.*;

/**
 * 开放 API — 原始文件（银行流水文件、发票文件等）
 * 路径前缀 /openapi/v1/files
 *
 *   GET  /                       §5.1 列举文件
 *   GET  /{fileId}/download      §5.2 下载原文件二进制
 *   POST /{fileId}/mark-parsed   §5.3 标记已处理
 *
 * 数据来源：fxy_financial_file_info 表（财务系统现有的原始文件管理表）。
 * 物理文件目录由 customize-config.screen-bank-path 配置项指定。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/files")
@SaIgnore
public class OpenApiFileController {

    private final FileInfoRepository fileInfoRepository;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    @Value("${customize-config.screen-bank-path:}")
    private String saveFinancialBankPath;

    private static final QFileInfo qFileInfo = QFileInfo.fileInfo;

    /**
     * 对外文件类型 ↔ 内部 FileInfo.type 字段值的映射。
     * 当前 financial 系统只支持 bankStatement 物理上传，其他类型即使列出来也无法下载（getSavePath 返回 null）。
     */
    private static final Map<String, String> EXT_TO_INTERNAL = Map.of(
            "bank_statement", "bankStatement",
            "invoice", "invoice",
            "assets", "assets",
            "salary", "salary",
            "other", "other"
    );

    private static final Map<String, String> INTERNAL_TO_EXT;
    static {
        Map<String, String> m = new HashMap<>();
        EXT_TO_INTERNAL.forEach((ext, internal) -> m.put(internal, ext));
        INTERNAL_TO_EXT = Collections.unmodifiableMap(m);
    }

    // ═══════════════════════════════════════════════════════════════
    // §5.1 列举文件
    // GET /openapi/v1/files
    // ═══════════════════════════════════════════════════════════════

    @GetMapping
    public JsonResult list(@RequestParam Long accountSetsId,
                           @RequestParam(required = false) String period,
                           @RequestParam(required = false) String type,
                           @RequestParam(required = false, defaultValue = "any") String parsed,
                           @RequestParam(required = false) String bankName) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }

        BooleanBuilder where = new BooleanBuilder();
        where.and(qFileInfo.accountSetsId.eq(accountSetsId));
        // 默认排除已删除
        where.and(qFileInfo.isDelete.eq(0).or(qFileInfo.isDelete.isNull()));

        // 期间过滤
        if (StrUtil.isNotBlank(period)) {
            try {
                YearMonth ym = YearMonth.parse(period);
                where.and(qFileInfo.accountSetPeriod.eq(ym.atDay(1)));
            } catch (Exception e) {
                return JsonResult.failure("period 格式错误，应为 YYYY-MM：" + period);
            }
        }

        // 类型过滤（外部 snake_case → 内部 camelCase）
        if (StrUtil.isNotBlank(type)) {
            String internal = EXT_TO_INTERNAL.get(type.trim().toLowerCase());
            if (internal == null) {
                return JsonResult.failure("type 非法：" + type
                        + "（合法值：bank_statement / invoice / assets / salary / other）");
            }
            where.and(qFileInfo.type.eq(internal));
        }

        // parsed 过滤
        switch (parsed == null ? "any" : parsed.trim().toLowerCase()) {
            case "pending" -> where.and(qFileInfo.isDeal.eq(0).or(qFileInfo.isDeal.isNull()));
            case "parsed" -> where.and(qFileInfo.isDeal.eq(1));
            case "any" -> { /* no-op */ }
            default -> {
                return JsonResult.failure("parsed 非法：" + parsed
                        + "（合法值：any / pending / parsed）");
            }
        }

        // bankName：FileInfo 里没单独字段，存在 businessName/businessCode 里。简单按 businessName 模糊匹配
        if (StrUtil.isNotBlank(bankName)) {
            where.and(qFileInfo.businessName.contains(bankName.trim())
                    .or(qFileInfo.businessCode.contains(bankName.trim())));
        }

        List<FileInfo> rows = bqf.selectFrom(qFileInfo)
                .where(where)
                .orderBy(qFileInfo.createTime.desc(), qFileInfo.id.desc())
                .fetch();

        List<Map<String, Object>> result = new ArrayList<>();
        for (FileInfo f : rows) {
            result.add(toDto(f));
        }
        // xiaowei AI 解析对接：返回 {rows, total} 结构
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rows", result);
        data.put("total", result.size());
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // §5.2 下载原文件
    // GET /openapi/v1/files/{fileId}/download?accountSetsId=88
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
        if (fi == null) {
            writeJsonError(response, 404, "文件不存在或不属于该账套：" + fileId);
            return;
        }
        if (Integer.valueOf(1).equals(fi.getIsDelete())) {
            writeJsonError(response, 410, "文件已删除");
            return;
        }

        // 解析物理路径
        File physicalFile = resolvePhysicalFile(fi);
        if (physicalFile == null) {
            writeJsonError(response, 415, "type=" + fi.getType() + " 暂不支持下载（仅 bankStatement 已配置物理目录）");
            return;
        }
        if (!physicalFile.exists() || !physicalFile.isFile()) {
            log.warn("openapi download 物理文件不存在: fileId={}, path={}", fileId, physicalFile.getAbsolutePath());
            writeJsonError(response, 404, "服务端物理文件丢失：" + fi.getFileName());
            return;
        }

        // 设置响应头
        String mime = guessMimeType(fi.getFileSuffix());
        response.setContentType(mime);
        response.setContentLengthLong(physicalFile.length());
        String displayName = StrUtil.isNotBlank(fi.getOriginalFileName()) ? fi.getOriginalFileName() : fi.getFileName();
        String encoded = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        response.setHeader("X-File-Id", String.valueOf(fileId));
        if (StrUtil.isNotBlank(fi.getType())) {
            response.setHeader("X-File-Type", INTERNAL_TO_EXT.getOrDefault(fi.getType(), fi.getType()));
        }

        try (FileInputStream in = new FileInputStream(physicalFile)) {
            IoUtil.copy(in, response.getOutputStream());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // §5.3 标记已处理
    // POST /openapi/v1/files/{fileId}/mark-parsed
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/{fileId}/mark-parsed")
    @Transactional
    public JsonResult markParsed(@PathVariable Long fileId,
                                 @RequestParam Long accountSetsId,
                                 @RequestBody(required = false) MarkParsedReq req) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }

        FileInfo fi = fileInfoRepository.findById(new PrimaryKey(fileId, accountSetsId)).orElse(null);
        if (fi == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errorType", "NOT_FOUND");
            return JsonResult.failure("文件不存在或不属于该账套：" + fileId).setData(data);
        }
        if (Integer.valueOf(1).equals(fi.getIsDelete())) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errorType", "ALREADY_DELETED");
            return JsonResult.failure("文件已删除").setData(data);
        }

        fi.setIsDeal(1);
        // 把关联记录 ID 拼到 businessCode 里（用逗号分隔），便于审计追溯。
        // linkedRecordIds（bank_statement_detail 记录 ID）优先于 voucherIds，二者只取其一。
        int linkedCount = 0;
        if (req != null) {
            List<Long> ids = (req.getLinkedRecordIds() != null && !req.getLinkedRecordIds().isEmpty())
                    ? req.getLinkedRecordIds()
                    : req.getVoucherIds();
            if (ids != null && !ids.isEmpty()) {
                fi.setBusinessCode(ids.stream()
                        .map(String::valueOf)
                        .reduce((a, b) -> a + "," + b)
                        .orElse(null));
                linkedCount = ids.size();
            }
            if (StrUtil.isNotBlank(req.getNote())) {
                String note = req.getNote().trim();
                if (note.length() > 50) note = note.substring(0, 50);
                fi.setBusinessName(note);
            }
        }
        fileInfoRepository.save(fi);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileId", fileId);
        data.put("parsed", true);
        data.put("linkedRecordCount", linkedCount);
        data.put("linkedVoucherCount", linkedCount);  // 兼容旧字段名
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    private Map<String, Object> toDto(FileInfo f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("fileId", f.getId());     // xiaowei 期望的字段名
        m.put("accountSetsId", f.getAccountSetsId());
        m.put("type", INTERNAL_TO_EXT.getOrDefault(f.getType(), f.getType()));
        m.put("fileName", f.getOriginalFileName());
        m.put("storageName", f.getFileName());
        m.put("fileSuffix", f.getFileSuffix());
        m.put("mimeType", guessMimeType(f.getFileSuffix()));
        // FileInfo 没有 fileSize 字段；如果物理文件存在可补，但避免每次列举都做 IO，先返回 null
        m.put("fileSize", null);
        // 时间字段
        m.put("uploadDate", f.getCreateTime());
        m.put("uploadedAt", f.getCreateTime());     // xiaowei 期望的字段名
        m.put("uploadByUserId", null);              // FileInfo 没记上传人
        m.put("uploadByUserName", null);
        m.put("uploadedBy", null);                  // xiaowei 期望的字段名
        m.put("period", f.getAccountSetPeriod() != null
                ? String.format("%04d-%02d", f.getAccountSetPeriod().getYear(), f.getAccountSetPeriod().getMonthValue())
                : null);
        // 银行流水：businessName 存的是银行名
        m.put("bankName", f.getBusinessName());
        m.put("accountNumber", null);               // 当前 FileInfo 未记录银行账号
        m.put("customerId", null);                  // 当前 FileInfo 未关联客户
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("businessCode", f.getBusinessCode());
        meta.put("businessName", f.getBusinessName());
        m.put("metadata", meta);
        m.put("extra", meta);                       // xiaowei 期望的字段名
        m.put("parsed", Integer.valueOf(1).equals(f.getIsDeal()));
        m.put("parseErrorReason", null);
        m.put("downloadUrl", "/openapi/v1/files/" + f.getId() + "/download?accountSetsId=" + f.getAccountSetsId());
        m.put("linkedRecordIds", parseVoucherIds(f.getBusinessCode()));
        return m;
    }

    private static List<Long> parseVoucherIds(String businessCode) {
        if (StrUtil.isBlank(businessCode)) return Collections.emptyList();
        List<Long> ids = new ArrayList<>();
        for (String s : businessCode.split(",")) {
            try {
                ids.add(Long.parseLong(s.trim()));
            } catch (NumberFormatException ignored) {
                // businessCode 在 mark-parsed 之外可能是其他业务用途的字符串，无法解析就跳过
            }
        }
        return ids;
    }

    /**
     * 根据 FileInfo.type 决定物理目录，拼出物理文件 File 对象。
     * 当前仅 bankStatement 类型有配置物理目录（screen-bank-path）。
     */
    private File resolvePhysicalFile(FileInfo fi) {
        if (StrUtil.isBlank(fi.getFileName())) return null;
        String type = fi.getType();
        String dir;
        if ("bankStatement".equalsIgnoreCase(type)) {
            dir = saveFinancialBankPath;
        } else {
            // TODO: 其他 type 后续配置
            return null;
        }
        if (StrUtil.isBlank(dir)) return null;
        return new File(dir, fi.getFileName());
    }

    private static String guessMimeType(String suffix) {
        if (StrUtil.isBlank(suffix)) return "application/octet-stream";
        return switch (suffix.trim().toLowerCase()) {
            case "pdf" -> "application/pdf";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls" -> "application/vnd.ms-excel";
            case "csv" -> "text/csv";
            case "txt" -> "text/plain";
            case "json" -> "application/json";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }

    private static void writeJsonError(HttpServletResponse response, int httpStatus, String msg) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json; charset=UTF-8");
        String body = "{\"success\":false,\"code\":-1,\"msg\":\"" + msg.replace("\"", "\\\"") + "\",\"data\":null}";
        response.getWriter().write(body);
    }

    @Data
    public static class MarkParsedReq {
        private List<Long> voucherIds;
        private List<Long> linkedRecordIds;
        private String note;
    }
}
