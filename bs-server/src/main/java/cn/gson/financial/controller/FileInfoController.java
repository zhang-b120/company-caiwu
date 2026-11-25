package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.FileInfoDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.vo.FileInfoVo;
import cn.gson.financial.kernel.service.FileInfoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;

/**
 * @Author zhb
 * @Date 2025-07-16
 * @Description
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/fileInfo")
public class FileInfoController {

    private final FileInfoService fileInfoService;

    /**
     * 获取银行文件列表
     * @param accountSets
     * @param page
     * @param type
     * @param period
     * @return
     */
    @GetMapping("/list")
    public JsonResult list(@SessionAccountSet AccountSets accountSets, Page page, String type, LocalDate period) {
        PageResults<FileInfoDto> list = fileInfoService.list(accountSets.getId(), page, type, period);
        return JsonResult.successful(list);
    }

    /**
     * 修改业务编码
     * @param vo
     * @return
     */
    @SaIgnore
    @PostMapping("/updateBusinessCode")
    public JsonResult updateBusinessCode(@RequestBody FileInfoVo vo){
        return JsonResult.successful(fileInfoService.updateBusinessCode(vo));
    }

    /**
     * 处理文件
     * @param id
     * @return
     */
    @PostMapping("/dealFile")
    public JsonResult dealFile(@RequestParam Long id, @SessionAccountSet AccountSets accountSets){
        return JsonResult.successful(fileInfoService.dealFile(id, accountSets));
    }

    /**
     * 上传文件
     * @return
     */
    @PostMapping("/upload")
    public JsonResult uploadFile(@RequestParam("file") MultipartFile file,
                                 @SessionAccountSet AccountSets accountSets,
                                 @RequestParam LocalDate period,
                                 @RequestParam String type,
                                 @RequestParam String fileName,
                                 @RequestParam(required = false) String businessCode,
                                 @RequestParam String businessName) {
        return JsonResult.successful(fileInfoService.uploadFile(file, accountSets.getId(), period, type, fileName, businessCode, businessName));
    }
}
