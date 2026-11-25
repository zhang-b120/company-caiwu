package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.config.AppConfig;
import cn.gson.financial.kernel.config.ArchiveProcessor;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AutoBackupConfig;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.BackupService;
import cn.gson.financial.kernel.tool.RedisUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.date.TimeInterval;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.io.file.FileNameUtil;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ZipUtil;
import cn.hutool.poi.excel.ExcelUtil;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (Backup)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-12-09 00:27:16</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/backup")
@SaCheckPermission("setting-backup-canview")
public class BackupController {

    private final BackupService backupService;

    private final AppConfig appConfig;

    private final RedisUtil redisUtil;

    private final List<ArchiveProcessor> archiveProcessors;

    @Resource
    private MailProperties mailProperties;

    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(backupService.list(accountSets.getId()));
    }


    /**
     * 加载自动任务信息
     *
     * @param accountSets
     * @return
     */
    @GetMapping("auto/info")
    public JsonResult loadAutoInfo(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(backupService.loadAutoInfo(accountSets.getId()));
    }

    /**
     * 自动备份状态变更
     *
     * @param accountSets
     * @return
     */
    @GetMapping("auto/{status}")
    public JsonResult changeAutoStatus(@SessionAccountSet AccountSets accountSets, @PathVariable Boolean status) {
        if (StrUtil.isNotEmpty(mailProperties.getUsername())) {
            backupService.changeAuto(accountSets.getId(), status);
            return JsonResult.successful();
        }
        return JsonResult.failure("服务商未配置邮箱发送功能,启用失败~");
    }

    /**
     * 自动备份状态变更
     *
     * @param accountSets
     * @return
     */
    @PostMapping("auto")
    public JsonResult changeAutoConfig(@SessionAccountSet AccountSets accountSets, @RequestBody AutoBackupConfig config) {
        config.setAccountSetsId(accountSets.getId());
        backupService.changeAutoConfig(accountSets.getId(), config);
        return JsonResult.successful();
    }

    @PostMapping
    public JsonResult create(@SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets) {
        backupService.backup(userVo.getId(), accountSets.getId());
        return JsonResult.successful();
    }

    @DeleteMapping("{backupId:\\d+}")
    public JsonResult delete(@PathVariable Long backupId, @SessionAccountSet AccountSets accountSets) {
        backupService.delete(backupId, accountSets.getId());
        return JsonResult.successful();
    }

    @GetMapping("recover/{backupId:\\d+}/{type:\\d+}")
    public JsonResult recover(@PathVariable Long backupId, @PathVariable Integer type, @SessionAccountSet AccountSets accountSets, @SessionUser UserVo userVo) {
        backupService.recover(backupId, userVo.getId(), type, accountSets.getId());
        return JsonResult.successful();
    }

    @PostMapping("upload")
    public JsonResult upload(@RequestParam("file") MultipartFile file, @SessionAccountSet AccountSets accountSets, @SessionUser UserVo userVo) throws IOException {
        backupService.saveUpload(file.getOriginalFilename(), file.getInputStream(), accountSets, userVo.getId());
        return JsonResult.successful();
    }

    /**
     * 获取备份状态
     *
     * @param accountSets
     * @return
     */
    @GetMapping("status/archive")
    public JsonResult archiveStatus(@SessionAccountSet AccountSets accountSets) {
        String cacheKey = "archive:" + accountSets.getId();
        if (redisUtil.ttl(cacheKey) > 0) {
            return JsonResult.successful(true);
        }
        return JsonResult.successful(false);
    }

    @GetMapping("archive")
    public JsonResult archive(@SessionUser UserVo userVo) {
        String cacheKey = "archive:" + userVo.getAccountSets().getId();
        if (redisUtil.ttl(cacheKey) > 0) {
            return JsonResult.failure("上一次归档正在进行中，请稍后再试~");
        }

        //记录归档
        redisUtil.set(cacheKey, "" + userVo.getId(), 600);

        TimeInterval timer = DateUtil.timer();
        timer.start();
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        for (ArchiveProcessor processor : archiveProcessors) {
            log.info("\n账套：{},{}开始归档", cacheKey, processor.getClass().getName());
            CompletableFuture<Boolean> archive = processor.archive(userVo);
            archive.whenComplete((s, a) -> {
                if (s) {
                    log.info("\n账套：{}，{}耗时：{}", cacheKey, processor.getClass().getName(), timer.intervalMs());
                } else {
                    log.error("\n账套归档失败~：{}，{}耗时：{}", cacheKey, processor.getClass().getName(), timer.intervalMs());
                }
            });
            futures.add(archive);
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).whenComplete((a, b) -> {
            log.info("\n{}归档总共耗时：{}", cacheKey, timer.intervalMs());
            redisUtil.del(cacheKey);
        });
        return JsonResult.successful();
    }

    @GetMapping("archive/list")
    public JsonResult archiveList(@SessionAccountSet AccountSets accountSets) {
        File archiveDir = appConfig.getUploadRoot("ast-" + accountSets.getId() + "/archive");
        List<File> years = FileUtil.loopFiles(archiveDir, 1, File::isDirectory);
        years.sort(Comparator.naturalOrder());
        List<Dict> files = new ArrayList<>();
        for (File year : years) {
            Dict dir = Dict.create()
                    .set("parentDoc", "-")
                    .set("dir", true)
                    .set("bakTime", DateUtil.format(DateUtil.calendar(year.lastModified()).getTime(), "yyyy年MM月dd日 HH:mm:ss"))
                    .set("doc", year.getName());

            List<File> docs = FileUtil.loopFiles(year, 1, File::isFile);
            docs.sort(Comparator.naturalOrder());
            long dirLen = 0;
            for (File doc : docs) {
                files.add(Dict.create()
                        .set("parentDoc", year.getName())
                        .set("dir", false)
                        .set("year", year.getName())
                        .set("bakTime", DateUtil.format(DateUtil.calendar(doc.lastModified()).getTime(), "yyyy年MM月dd日 HH:mm:ss"))
                        .set("doc", doc.getName())
                        .set("docSize", doc.length()));
                dirLen += doc.length();
            }
            dir.set("docSize", dirLen);
            files.add(dir);
        }
        return JsonResult.successful(files);
    }

    @GetMapping("download/{backupId:\\d+}")
    public ResponseEntity<byte[]> download(@PathVariable Long backupId, @SessionAccountSet AccountSets accountSets) throws UnsupportedEncodingException {
        File file = backupService.download(backupId, accountSets.getId());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDispositionFormData("attachment", URLEncoder.encode("（数据包加密不能查看，仅供数据恢复使用）" + FileNameUtil.getName(file), StandardCharsets.UTF_8));
        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(file.length())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(FileUtil.readBytes(file));
    }

    /**
     * 归档下载
     *
     * @param year
     * @param doc
     * @param acId
     * @param userVo
     * @return
     * @throws UnsupportedEncodingException
     */
    @GetMapping({"download/archives", "download/archive/{year}/{doc}", "download/archive/{year}"})
    public ResponseEntity<byte[]> archiveDownload(@PathVariable(required = false) String year, @PathVariable(required = false) String doc, @SessionAccountSet AccountSets accountSets, @SessionUser UserVo userVo) {
        HttpHeaders headers = new HttpHeaders();
        File archiveDir = appConfig.getUploadRoot("ast-" + accountSets.getId() + "/archive");
        File file;
        if (StrUtil.isEmpty(doc) && StrUtil.isEmpty(year)) {
            file = ZipUtil.zip(archiveDir.getAbsolutePath(), new File(FileUtil.getTmpDir(), RandomUtil.randomString(8) + ".zip").getAbsolutePath(), false);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", URLEncoder.encode(FileNameUtil.getName("（归档Excel仅供查看，不能用于恢复使用）-" + userVo.getAccountSets().getCompanyName() + "-" + DateUtil.date().toDateStr() + ".zip"), StandardCharsets.UTF_8));
        } else if (StrUtil.isNotEmpty(doc)) {
            file = new File(archiveDir, year + "/" + doc);
            headers.set(HttpHeaders.CONTENT_TYPE, ExcelUtil.XLSX_CONTENT_TYPE);
            headers.setContentDispositionFormData("attachment", URLEncoder.encode(FileNameUtil.getName(doc), StandardCharsets.UTF_8));
        } else {
            file = ZipUtil.zip(new File(archiveDir, year).getAbsolutePath(), new File(FileUtil.getTmpDir(), RandomUtil.randomString(8) + ".zip").getAbsolutePath(), false);
            headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
            headers.setContentDispositionFormData("attachment", URLEncoder.encode(FileNameUtil.getName("（归档Excel仅供查看，不能用于恢复使用）-" + year + "-" + userVo.getAccountSets().getCompanyName() + "-" + DateUtil.date().toDateStr() + ".zip"), StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok()
                .headers(headers)
                .contentLength(file.length())
                .body(FileUtil.readBytes(file));
    }

    /**
     * 归档删除
     *
     * @param year
     * @param doc
     * @param acId
     * @return
     * @throws UnsupportedEncodingException
     */
    @DeleteMapping({"archive/{year}/{doc}", "archive/{year}"})
    public JsonResult archiveDel(@PathVariable String year, @PathVariable(required = false) String doc, @SessionAccountSet AccountSets accountSets) {
        File archiveDir = appConfig.getUploadRoot("ast-" + accountSets.getId() + "/archive");
        if (StrUtil.isNotEmpty(doc)) {
            FileUtil.del(new File(archiveDir, year + "/" + doc));
        } else {
            FileUtil.del(new File(archiveDir, year));
        }
        return JsonResult.successful();
    }
}

