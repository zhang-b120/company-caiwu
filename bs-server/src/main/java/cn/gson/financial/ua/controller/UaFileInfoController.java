package cn.gson.financial.ua.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.FileInfoDto;
import cn.gson.financial.kernel.model.entity.FileInfo;
import cn.gson.financial.kernel.model.ua.request.DeleteFileInfoReq;
import cn.gson.financial.kernel.model.ua.request.GetFileInfoListReq;
import cn.gson.financial.kernel.service.FileInfoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * @Author zhb
 * @Date 2025-07-16
 * @Description 文件信息控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/fileInfo")
public class UaFileInfoController {

    private final FileInfoService fileInfoService;

    /**
     * 上传文件
     * @param file
     * @param accountSetsId
     * @param period
     * @param type
     * @return
     */
    @PostMapping("/upload")
    @SaIgnore
    public JsonResult uploadFile(@RequestParam("file") MultipartFile file,
                                 @RequestParam Long accountSetsId,
                                 @RequestParam LocalDate period,
                                 @RequestParam String type,
                                 @RequestParam String fileName,
                                 @RequestParam(required = false) String businessCode,
                                 @RequestParam String businessName) {
        return JsonResult.successful(fileInfoService.uploadFile(file, accountSetsId, period, type, fileName, businessCode, businessName));
    }

    /**
     * 删除文件
     * @param req
     * @return
     */
    @PostMapping("/deleteFile")
    @SaIgnore
    public JsonResult deleteFile(@RequestBody DeleteFileInfoReq req) {
        return JsonResult.successful(fileInfoService.deleteFile(req.getAccountSetsId(), req.getId()));
    }

    /**
     * 获取银行文件列表
     * @param req
     * @return
     */
    @PostMapping("/list")
    @SaIgnore
    public JsonResult list(@RequestBody GetFileInfoListReq req) {
        return JsonResult.successful(fileInfoService.list(req.getAccountSetsId(),req.getPage(), req.getType(), req.getPeriod()));
    }

}