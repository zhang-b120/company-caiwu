package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.FieldWorkPhoto;
import cn.gson.financial.kernel.service.FieldWorkPhotoService;
import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 开放 API — 外勤拍照归档（FN-07）
 * <p>
 * 给 FM-12 外勤手机端提供"拍照 → 上传 → 关联客户档案 + 任务"的归档 API。
 * 路径前缀 /openapi/v1/field-work，全部接口免鉴权（@SaIgnore）。
 *
 *   POST /photo                  上传照片（multipart/form-data）
 *   GET  /photo                  分页列表
 *   GET  /photo/{id}             查元数据
 *   GET  /photo/{id}/download    下载图片二进制
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/field-work")
@SaIgnore
public class OpenApiFieldWorkController {

    private final FieldWorkPhotoService fieldWorkPhotoService;

    // ═══════════════════════════════════════════════════════════════
    // 1. 上传照片
    // POST /openapi/v1/field-work/photo  (multipart/form-data)
    // ═══════════════════════════════════════════════════════════════

    @PostMapping(value = "/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JsonResult upload(@RequestParam("file") MultipartFile file,
                             @RequestParam("accountSetsId") Long accountSetsId,
                             @RequestParam(value = "customerId", required = false) Long customerId,
                             @RequestParam(value = "taskId", required = false) Long taskId,
                             @RequestParam(value = "gps", required = false) String gps,
                             @RequestParam(value = "timestamp", required = false)
                             @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime timestamp,
                             @RequestParam(value = "description", required = false) String description,
                             @RequestParam(value = "uploadedBy", required = false) Long uploadedBy) {
        if (file == null || file.isEmpty()) {
            return JsonResult.failure("file 不能为空");
        }
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (!FieldWorkPhotoService.isAllowedImage(file.getContentType())) {
            return JsonResult.failure("仅支持图片格式 (image/jpeg, image/png, image/heic, image/webp)，实际：" + file.getContentType());
        }
        try {
            FieldWorkPhoto saved = fieldWorkPhotoService.upload(file, accountSetsId,
                    customerId, taskId, gps, timestamp, description, uploadedBy);
            return JsonResult.successful(toDto(saved));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("openapi field-work upload 失败", e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "上传失败");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. 分页列表
    // GET /openapi/v1/field-work/photo?accountSetsId=&customerId=&taskId=&page=&size=
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/photo")
    public JsonResult list(@RequestParam("accountSetsId") Long accountSetsId,
                           @RequestParam(value = "customerId", required = false) Long customerId,
                           @RequestParam(value = "taskId", required = false) Long taskId,
                           @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                           @RequestParam(value = "size", required = false, defaultValue = "20") Integer size) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        Page<FieldWorkPhoto> p = fieldWorkPhotoService.list(accountSetsId, customerId, taskId,
                page == null ? 1 : page, size == null ? 20 : size);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", (int) p.getTotalElements());
        data.put("page", p.getNumber() + 1);
        data.put("size", p.getSize());
        data.put("rows", p.getContent().stream().map(OpenApiFieldWorkController::toDto).toList());
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. 查元数据
    // GET /openapi/v1/field-work/photo/{id}
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/photo/{id}")
    public JsonResult detail(@PathVariable Long id) {
        return fieldWorkPhotoService.findById(id)
                .map(p -> JsonResult.successful(toDto(p)))
                .orElseGet(() -> JsonResult.failure("photo 不存在: " + id));
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. 下载图片二进制
    // GET /openapi/v1/field-work/photo/{id}/download
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/photo/{id}/download")
    public void download(@PathVariable Long id, HttpServletResponse response) throws IOException {
        FieldWorkPhoto photo = fieldWorkPhotoService.findById(id).orElse(null);
        if (photo == null) {
            writeJsonError(response, 404, "photo 不存在: " + id);
            return;
        }
        File f;
        try {
            f = fieldWorkPhotoService.resolveAbsoluteFile(photo);
        } catch (SecurityException e) {
            writeJsonError(response, 403, e.getMessage());
            return;
        }
        if (f == null || !f.exists() || !f.isFile()) {
            log.warn("openapi field-work download 物理文件不存在: id={}, path={}", id,
                    f == null ? null : f.getAbsolutePath());
            writeJsonError(response, 404, "服务端物理文件丢失：" + photo.getStorageName());
            return;
        }

        response.setContentType(StrUtil.isNotBlank(photo.getMimeType()) ? photo.getMimeType() : "application/octet-stream");
        response.setContentLengthLong(f.length());
        String displayName = StrUtil.isNotBlank(photo.getFileName()) ? photo.getFileName() : photo.getStorageName();
        String encoded = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
        // 图片走 inline 让浏览器直接预览；如果是其他保留 attachment
        String disposition = StrUtil.startWithIgnoreCase(photo.getMimeType(), "image/") ? "inline" : "attachment";
        response.setHeader("Content-Disposition",
                disposition + "; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        response.setHeader("X-Photo-Id", String.valueOf(id));

        try (FileInputStream in = new FileInputStream(f)) {
            IoUtil.copy(in, response.getOutputStream());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    private static Map<String, Object> toDto(FieldWorkPhoto p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("accountSetsId", p.getAccountSetsId());
        m.put("customerId", p.getCustomerId());
        m.put("taskId", p.getTaskId());
        m.put("fileName", p.getFileName());
        m.put("storageName", p.getStorageName());
        m.put("storagePath", p.getStoragePath());
        m.put("fileSuffix", p.getFileSuffix());
        m.put("mimeType", p.getMimeType());
        m.put("fileSize", p.getFileSize());
        m.put("gpsLat", p.getGpsLat());
        m.put("gpsLng", p.getGpsLng());
        m.put("capturedAt", p.getCapturedAt());
        m.put("description", p.getDescription());
        m.put("uploadedBy", p.getUploadedBy());
        m.put("createdAt", p.getCreatedAt());
        m.put("downloadUrl", "/openapi/v1/field-work/photo/" + p.getId() + "/download");
        return m;
    }

    private static void writeJsonError(HttpServletResponse response, int httpStatus, String msg) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json; charset=UTF-8");
        String body = "{\"success\":false,\"code\":-1,\"msg\":\""
                + msg.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\",\"data\":null}";
        response.getWriter().write(body);
    }
}
