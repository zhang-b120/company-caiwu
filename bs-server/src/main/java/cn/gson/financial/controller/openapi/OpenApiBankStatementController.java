package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.FileInfo;
import cn.gson.financial.kernel.model.entity.QFileInfo;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.repo.FileInfoRepository;
import cn.gson.financial.openapi.client.XiaoweiParseClient;
import cn.hutool.core.io.file.FileNameUtil;
import cn.hutool.core.util.StrUtil;
import com.querydsl.core.BooleanBuilder;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 开放 API — 银行流水文件上传（FN-06）。
 * <p>
 * 路径前缀 {@code /openapi/v1/bank-statement}，全部接口免鉴权（@SaIgnore）。
 * <p>
 * 接口契约：用户（通过 WX-05 wechat 触发）上传银行流水文件 → 落到
 * {@code fxy_financial_file_info} 表 + 物理目录 → 异步触发 xiaowei 端解析。
 * 解析结果由 xiaowei 调 {@code POST /openapi/v1/files/{id}/mark-parsed} 回写。
 * <p>
 * 文件大小限制由 spring.servlet.multipart 控制（kernel application.yml: 50MB / request 100MB）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/bank-statement")
@SaIgnore
public class OpenApiBankStatementController {

    private final FileInfoRepository fileInfoRepository;
    private final XiaoweiParseClient xiaoweiParseClient;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    @Value("${customize-config.screen-bank-path:}")
    private String screenBankPath;

    @Value("${customize-config.base-path:}")
    private String basePath;

    /** 银行流水允许的文件后缀（xlsx / xls / csv / pdf / ofd） */
    private static final Set<String> ALLOWED_SUFFIXES = Set.of("xlsx", "xls", "csv", "pdf", "ofd");

    /** 与 FileInfoService.getSavePath 一致的内部 type 值（已被 OpenApiFileController 使用） */
    static final String INTERNAL_TYPE = "bankStatement";

    private static final QFileInfo qFileInfo = QFileInfo.fileInfo;

    // ═══════════════════════════════════════════════════════════════
    // POST /openapi/v1/bank-statement/upload
    // multipart/form-data
    // ═══════════════════════════════════════════════════════════════

    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<JsonResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("accountSetsId") Long accountSetsId,
            @RequestParam(value = "customerId", required = false) Long customerId,
            @RequestParam("bankName") String bankName,
            @RequestParam("periodYear") Integer periodYear,
            @RequestParam("periodMonth") Integer periodMonth) {

        // ---- 1. 必填字段校验 ----
        String validationError = validateRequest(file, accountSetsId, bankName, periodYear, periodMonth);
        if (validationError != null) {
            return ResponseEntity.badRequest().body(failureWithType("VALIDATION_ERROR", validationError));
        }

        // ---- 2. 文件后缀校验 ----
        String originalName = file.getOriginalFilename();
        String suffix = FileNameUtil.getSuffix(originalName);
        if (StrUtil.isBlank(suffix) || !ALLOWED_SUFFIXES.contains(suffix.toLowerCase(Locale.ROOT))) {
            return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .body(failureWithType("UNSUPPORTED_FILE_TYPE",
                            "不支持的文件后缀: " + suffix + "（仅允许 xlsx / xls / csv / pdf / ofd）"));
        }

        // ---- 3. 期间组装 ----
        LocalDate period;
        try {
            period = LocalDate.of(periodYear, periodMonth, 1);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(failureWithType("VALIDATION_ERROR",
                    "periodYear/periodMonth 不是合法日期: " + periodYear + "-" + periodMonth));
        }

        // ---- 4. 去重检查（同账套 + 同 originalName + 同期间 + 未删除） ----
        FileInfo existing = findDuplicate(accountSetsId, originalName, period);
        if (existing != null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errorType", "DUPLICATE_FILE");
            data.put("existingFileId", existing.getId());
            data.put("existingStorageName", existing.getFileName());
            data.put("existingUploadDate", existing.getCreateTime());
            data.put("existingParsed", Integer.valueOf(1).equals(existing.getIsDeal()));
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(JsonResult.failure("文件已存在（同账套+同名+同期间）: " + originalName).setData(data));
        }

        // ---- 5. 落地物理文件 + 入库 ----
        String saveDir = resolveSaveDir();
        if (StrUtil.isBlank(saveDir)) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(failureWithType("STORAGE_NOT_CONFIGURED",
                            "服务端未配置 customize-config.screen-bank-path 或 base-path"));
        }

        String storageName = accountSetsId + "-" + System.currentTimeMillis() + "." + suffix;
        File dir = new File(saveDir);
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("银行流水上传失败：无法创建目录 {}", saveDir);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(failureWithType("STORAGE_ERROR", "无法创建存储目录: " + saveDir));
        }
        File saveFile = new File(dir, storageName);
        try {
            file.transferTo(saveFile);
        } catch (IOException e) {
            log.error("银行流水文件落地失败 accountSetsId={} storageName={}", accountSetsId, storageName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(failureWithType("STORAGE_ERROR", "文件落地失败: " + e.getMessage()));
        }

        FileInfo fi = new FileInfo();
        fi.setAccountSetsId(accountSetsId);
        fi.setOriginalFileName(originalName);
        fi.setFileName(storageName);
        fi.setFileSuffix(suffix);
        fi.setAccountSetPeriod(period);
        fi.setType(INTERNAL_TYPE);
        // businessName 复用为银行名（与 FileInfoService.recognizeExcel 的语义一致）
        fi.setBusinessName(StrUtil.isBlank(bankName) ? null : bankName.trim());
        // businessCode 在已有体系里是银行编码（bank_lib.bank_code），上传时尚未确定 → 留空
        fi.setBusinessCode(null);
        fi.setCreateTime(LocalDateTime.now());
        fi.setIsDelete(0);
        fi.setIsDeal(0);

        FileInfo saved;
        try {
            saved = fileInfoRepository.save(fi);
        } catch (Exception e) {
            log.error("银行流水入库失败 storageName={}", storageName, e);
            // 已经落到磁盘但入库失败：尝试清理物理文件，避免孤儿
            //noinspection ResultOfMethodCallIgnored
            saveFile.delete();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(failureWithType("DB_ERROR", "文件入库失败: " + e.getMessage()));
        }

        // ---- 6. 异步触发 xiaowei 解析 ----
        boolean parseTriggered;
        try {
            xiaoweiParseClient.triggerParseBankAsync(
                    saved.getId(), accountSetsId, customerId,
                    bankName == null ? null : bankName.trim(), periodYear, periodMonth);
            parseTriggered = true;
        } catch (Exception e) {
            // @Async 提交本身极少抛异常；这里兜底
            log.warn("提交 xiaowei 异步解析任务失败 fileId={}", saved.getId(), e);
            parseTriggered = false;
        }

        // ---- 7. 响应 ----
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileId", saved.getId());
        data.put("accountSetsId", accountSetsId);
        data.put("storageName", storageName);
        data.put("originalName", originalName);
        data.put("size", file.getSize());
        data.put("period", String.format("%04d-%02d", periodYear, periodMonth));
        data.put("bankName", bankName);
        data.put("parsedTriggered", parseTriggered);
        return ResponseEntity.ok(JsonResult.successful(data));
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    private static String validateRequest(MultipartFile file, Long accountSetsId, String bankName,
                                          Integer periodYear, Integer periodMonth) {
        if (file == null || file.isEmpty()) {
            return "file 不能为空";
        }
        if (accountSetsId == null) {
            return "accountSetsId 不能为空";
        }
        if (StrUtil.isBlank(bankName)) {
            return "bankName 不能为空";
        }
        if (periodYear == null || periodYear < 1900 || periodYear > 9999) {
            return "periodYear 非法: " + periodYear;
        }
        if (periodMonth == null || periodMonth < 1 || periodMonth > 12) {
            return "periodMonth 非法: " + periodMonth;
        }
        if (StrUtil.isBlank(file.getOriginalFilename())) {
            return "file 缺少文件名";
        }
        return null;
    }

    /**
     * 同账套 + 同 originalName + 同期间 + 未删除 → 视为重复。
     */
    FileInfo findDuplicate(Long accountSetsId, String originalName, LocalDate period) {
        BooleanBuilder where = new BooleanBuilder();
        where.and(qFileInfo.accountSetsId.eq(accountSetsId));
        where.and(qFileInfo.originalFileName.eq(originalName));
        where.and(qFileInfo.accountSetPeriod.eq(period));
        where.and(qFileInfo.type.eq(INTERNAL_TYPE));
        where.and(qFileInfo.isDelete.eq(0).or(qFileInfo.isDelete.isNull()));
        return bqf.selectFrom(qFileInfo)
                .where(where)
                .orderBy(qFileInfo.createTime.desc(), qFileInfo.id.desc())
                .fetchFirst();
    }

    /**
     * 优先使用 {@code customize-config.screen-bank-path}；否则在 {@code base-path} 下用 {@code /bank-statements/} 子目录兜底。
     */
    String resolveSaveDir() {
        if (StrUtil.isNotBlank(screenBankPath)) {
            return screenBankPath;
        }
        if (StrUtil.isNotBlank(basePath)) {
            String sep = basePath.endsWith("/") || basePath.endsWith("\\") ? "" : File.separator;
            return basePath + sep + "bank-statements";
        }
        return null;
    }

    private static JsonResult failureWithType(String errorType, String msg) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", errorType);
        return JsonResult.failure(msg).setData(data);
    }
}
