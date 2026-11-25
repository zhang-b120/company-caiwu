package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.config.AppConfig;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.Attachment;
import cn.gson.financial.kernel.model.enums.AttachmentType;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.AttachmentService;
import cn.gson.financial.kernel.tool.AttachUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileNameUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

/**
 *
 * <p><b>Copyright © 2010-2020开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : share</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022/3/23 11:24</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@Controller
@RequestMapping
@RequiredArgsConstructor
public class UploadController {

    private final AppConfig appConfig;

    private final AttachmentService attachmentService;

    private final AttachUtil attachUtil;

    @PostMapping("/upload/{type}")
    @ResponseBody
    @SaCheckLogin
    public JsonResult upload(@RequestParam("file") MultipartFile file,
                             @SessionAccountSet AccountSets accountSets,
                             @SessionUser UserVo userVo,
                             @RequestParam(value = "name", required = false) String name,
                             @PathVariable AttachmentType type) {
        try {

            String contentType = file.getContentType();

            Assert.isTrue(checkContentType(contentType), "上传文件类型不支持,请选择(图片,txt,文档,PDF)~");
            if (StrUtil.isEmpty(name)) {
                name = UUID.fastUUID() + "." + FileNameUtil.getSuffix(file.getOriginalFilename());
            }
            String path = attachUtil.saveFile(accountSets.getId(), file.getInputStream(), type, name);

            Attachment attachment = new Attachment();
            attachment.setAccountSetsId(accountSets.getId());
            attachment.setTitle(file.getOriginalFilename());
            attachment.setStorageTitle(name);
            attachment.setPath(path);
            attachment.setBakTime(LocalDateTime.now());
            attachment.setType(type);
            attachment.setUserId(userVo.getId());
            attachment.setFileType(contentType);
            attachment.setSize(file.getSize());
            attachmentService.save(attachment);

            return JsonResult.successful()
                    .data("filename", file.getOriginalFilename())
                    .data("path", attachUtil.getAttachPath(attachment.getPath()));
        } catch (IOException e) {
            log.error("上传图片失败,请检查配置路径~", e);
            return JsonResult.failure(e.getMessage());
        }
    }

    @GetMapping("/attachment/**")
    @SaCheckLogin
    public ResponseEntity<byte[]> attachment(HttpServletRequest request, String name, @SessionAccountSet AccountSets accountSets) {
        AttachUtil.FileRecord record = attachUtil.getFile(accountSets.getId(), request.getServletPath());
        return getResponseEntity(name, record.file());
    }
    @GetMapping("/assets/**")
    public ResponseEntity<byte[]> assets(HttpServletRequest request, String name) {
        File file = new File(appConfig.getUploadRoot(), request.getServletPath());
        return getResponseEntity(name, file);
    }

    @Value("${oc.screenshot-root:E:\\qykj\\file\\oc-screenshots}")
    private String ocScreenshotRoot;

    @GetMapping("/oc-screenshots/**")
    public ResponseEntity<byte[]> ocScreenshots(HttpServletRequest request) {
        String subPath = request.getServletPath().substring("/oc-screenshots/".length());
        File file = new File(ocScreenshotRoot, subPath);
        return getResponseEntity(null, file);
    }

    /** 代理 OC 机器本地截图文件（仅限 OC 输出目录，避免任意文件读取） */
    @Value("${oc.output-root:D:\\tax_output}")
    private String ocOutputRoot;

    @GetMapping("/oc-local-file")
    public ResponseEntity<byte[]> ocLocalFile(@RequestParam String path) {
        File file = new File(path);
        // 安全校验：只允许读取 OC 输出目录下的文件
        try {
            String canonical = file.getCanonicalPath();
            String allowedRoot = new File(ocOutputRoot).getCanonicalPath();
            if (!canonical.startsWith(allowedRoot)) {
                return ResponseEntity.status(403).build();
            }
        } catch (Exception e) {
            return ResponseEntity.status(400).build();
        }
        return getResponseEntity(null, file);
    }
    /**
     * 文件下回响应：PDF/图片 inline、其它附件下载；文件不存在 404。
     * <p>FN-DLV-T1（182）改 {@code public static} 供 {@code OpenApiInvoiceFileController} 复用（同一套服务范式，单一真源）。
     */
    public static ResponseEntity<byte[]> getResponseEntity(String name, File file) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.add("Cache-Control", "max-age=2592000");
            if (file.exists()) {
                ResponseEntity.BodyBuilder bodyBuilder = ResponseEntity.ok();
                // FN-DLV-FILENAME（262）：传【原始（未编码）】文件名给 ContentDisposition，由它做 RFC 5987 UTF-8 编码
                // （生成 filename*=UTF-8''…）。别再手工 URLEncoder.encode——那会把已编码串再喂给 ContentDisposition、
                // 二次编码出 %XX 乱码名（f.txt/丢扩展名问题的一部分根因）。
                String downloadName = StrUtil.emptyToDefault(name, FileNameUtil.getName(file));
                String mimeType = FileUtil.getMimeType(file.getAbsolutePath());
                if (mimeType != null) {
                    try {
                        MediaType mediaType = MediaType.parseMediaType(mimeType);
                        switch (mediaType.toString()) {
                            // PDF/图片：保留 contentType + 用 inline 保住浏览器/Electron 内联预览，同时给下载一个确定的文件名+扩展名
                            case MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_GIF_VALUE, MediaType.APPLICATION_PDF_VALUE -> {
                                bodyBuilder.contentType(mediaType);
                                headers.setContentDisposition(ContentDisposition.inline().filename(downloadName, StandardCharsets.UTF_8).build());
                            }
                            // 其它（ofd/xml/zip/doc…）：正常下载 disposition（修掉误用的 setContentDispositionFormData——那是 multipart 表单字段用的）
                            default -> headers.setContentDisposition(ContentDisposition.attachment().filename(downloadName, StandardCharsets.UTF_8).build());
                        }
                    } catch (Exception e) {
                        headers.setContentDisposition(ContentDisposition.attachment().filename(downloadName, StandardCharsets.UTF_8).build());
                    }
                } else {
                    headers.setContentDisposition(ContentDisposition.attachment().filename(downloadName, StandardCharsets.UTF_8).build());
                }

                return bodyBuilder
                        .headers(headers)
                        .contentLength(file.length())
                        .body(FileUtil.readBytes(file));
            }
        } catch (Exception e) {
            log.error("文件读取失败~", e);
        }
        return ResponseEntity.notFound().build();
    }

    /**
     * 检查文档类型
     *
     * @param contentType
     * @return
     */
    private static boolean checkContentType(String contentType) {
        return StrUtil.isNotEmpty(contentType)
                && (contentType.startsWith("image/")
                || contentType.startsWith("text/")
                || contentType.equalsIgnoreCase("application/pdf")
                || contentType.equalsIgnoreCase("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                || contentType.equalsIgnoreCase("application/msword")
                || contentType.equalsIgnoreCase("application/vnd.ms-excel")
                || contentType.equalsIgnoreCase("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }
}
