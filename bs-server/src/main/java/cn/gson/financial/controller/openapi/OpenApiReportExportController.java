package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.report.export.ReportExportJob;
import cn.gson.financial.report.export.ReportExportService;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FN-08 异步报表导出 OpenAPI。
 *
 * <p>路径前缀 {@code /openapi/v1/report/export}，全部 {@code @SaIgnore}。
 *
 * <pre>
 *   POST /openapi/v1/report/export                  触发异步导出
 *   GET  /openapi/v1/report/export/{jobId}          查询 job 状态 + progress
 *   GET  /openapi/v1/report/export/{jobId}/download 下载结果文件
 * </pre>
 *
 * <p>注意：不动 {@link OpenApiReportController} 同步 GET /openapi/v1/report —— 那个保留给
 * 小数据量场景；批量大规模导出走这里的异步队列。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/report/export")
@SaIgnore
public class OpenApiReportExportController {

    private final ReportExportService exportService;

    // ───────────────────────────────────────────────────────────────────
    // 1. 触发异步导出
    // ───────────────────────────────────────────────────────────────────
    @PostMapping
    public JsonResult submit(@RequestBody ExportReq req) {
        try {
            ReportExportService.SubmitRequest sr = new ReportExportService.SubmitRequest();
            sr.accountSetsId = req.getAccountSetsId();
            sr.type = req.getType();
            sr.year = req.getYear();
            sr.month = req.getMonth();
            sr.format = req.getFormat();

            ReportExportJob job = exportService.submit(sr);
            return JsonResult.successful(toBriefDto(job));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-08] submit failed", e);
            return JsonResult.failure("提交导出任务失败：" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    // ───────────────────────────────────────────────────────────────────
    // 2. 查询状态
    // ───────────────────────────────────────────────────────────────────
    @GetMapping("/{jobId}")
    public JsonResult status(@PathVariable String jobId) {
        if (StrUtil.isBlank(jobId)) {
            return JsonResult.failure("jobId 不能为空");
        }
        ReportExportJob job = exportService.find(jobId);
        if (job == null) {
            return JsonResult.failure("job 不存在或已过期：" + jobId);
        }
        return JsonResult.successful(toFullDto(job));
    }

    // ───────────────────────────────────────────────────────────────────
    // 3. 下载结果
    // ───────────────────────────────────────────────────────────────────
    @GetMapping("/{jobId}/download")
    public ResponseEntity<byte[]> download(@PathVariable String jobId) {
        if (StrUtil.isBlank(jobId)) {
            return ResponseEntity.badRequest().build();
        }
        ReportExportJob job = exportService.find(jobId);
        if (job == null) {
            return ResponseEntity.notFound().build();
        }
        if (!"SUCCESS".equals(job.getStatus())) {
            // 不抛 500 — 给个空 body + 409 让调用方知道是状态问题，方便排查
            HttpHeaders h = new HttpHeaders();
            h.add("X-Export-Status", job.getStatus());
            h.add("X-Export-Progress", String.valueOf(job.getProgress()));
            return new ResponseEntity<>(new byte[0], h, org.springframework.http.HttpStatus.CONFLICT);
        }
        File file = exportService.findFile(jobId);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }

        HttpHeaders headers = new HttpHeaders();
        String name = job.getType() + "_" + job.getPeriod() + "." + extOf(job.getFormat());
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8);
        headers.setContentDisposition(ContentDisposition.attachment().filename(encoded).build());
        headers.add("X-Export-JobId", job.getJobId());
        headers.setContentType(mediaTypeFor(job.getFormat()));
        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(file.length())
                .body(FileUtil.readBytes(file));
    }

    // ───────────────────────────────────────────────────────────────────
    // helpers
    // ───────────────────────────────────────────────────────────────────

    /** POST body */
    @Data
    public static class ExportReq {
        private Long accountSetsId;
        private String type;
        private Integer year;
        private Integer month;
        private String format; // optional, default JSON
    }

    static String extOf(String format) {
        return switch (format == null ? "JSON" : format) {
            case "XLSX" -> "xlsx";
            case "PDF" -> "pdf";
            default -> "json";
        };
    }

    static MediaType mediaTypeFor(String format) {
        return switch (format == null ? "JSON" : format) {
            case "XLSX" -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            case "PDF" -> MediaType.APPLICATION_PDF;
            default -> MediaType.APPLICATION_JSON;
        };
    }

    static Map<String, Object> toBriefDto(ReportExportJob job) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", job.getJobId());
        m.put("status", job.getStatus());
        m.put("progress", job.getProgress());
        m.put("accountSetsId", job.getAccountSetsId());
        m.put("type", job.getType());
        m.put("period", job.getPeriod());
        m.put("format", job.getFormat());
        return m;
    }

    static Map<String, Object> toFullDto(ReportExportJob job) {
        Map<String, Object> m = toBriefDto(job);
        m.put("createdAt", job.getCreatedAt());
        m.put("completedAt", job.getCompletedAt());
        m.put("resultPath", job.getResultPath());
        m.put("resultSize", job.getResultSize());
        m.put("errorMsg", job.getErrorMsg());
        return m;
    }
}
