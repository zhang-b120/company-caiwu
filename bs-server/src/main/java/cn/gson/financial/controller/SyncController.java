package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.config.AppConfig;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.event.SubjectChangeEvent;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.enums.AccountStandard;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.BalanceCalcService;
import cn.gson.financial.sync.ImportService;
import cn.gson.financial.sync.SyncType;
import cn.gson.financial.sync.dto.SubjectImportMappingDto;
import cn.hutool.core.io.FileUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.json.JSONUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.time.YearMonth;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年08月09日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@RestController
@RequestMapping("sync")
@RequiredArgsConstructor
@SaCheckPermission("setting-sync-canview")
public class SyncController {

    private final AppConfig appConfig;

    private final BalanceCalcService balanceCalcService;

    @PostMapping("import")
    public JsonResult importData(@RequestParam("fileKm") MultipartFile fileKm,
                                 @RequestParam(value = "fileQc", required = false) MultipartFile fileQc,
                                 @RequestParam(value = "filePz") MultipartFile filePz,
                                 @RequestParam("soft") SyncType soft,
                                 @RequestParam("companyName") String companyName,
                                 @RequestParam("encoding") List<Integer> encoding,
                                 @RequestParam("astId") Long astId,
                                 @RequestParam("vatType") VatType vatType,
                                 @RequestParam("accountStandard") AccountStandard accountStandard,
                                 @RequestParam("subjectMapping") String subjectMappingStr,
                                 @SessionUser UserVo userVo) {
        try {
            List<SubjectImportMappingDto> subjectMappings = JSONUtil.toList(subjectMappingStr, SubjectImportMappingDto.class);
            ImportService importService = SpringUtil.getBean(soft.importService);
            AccountSets ast = importService.analysisData(fileKm, fileQc, filePz, companyName, encoding, subjectMappings, vatType, accountStandard, userVo.getId(), astId, soft);
            Subject subject = new Subject();
            subject.setAccountSetsId(ast.getId());
            SpringUtil.publishEvent(SubjectChangeEvent.create(subject, () ->
                    balanceCalcService.calc(ast.getId(), YearMonth.of(ast.getEnableDate().getYear(), ast.getEnableDate().getMonthValue()))));
            return JsonResult.successful();
        } catch (Exception e) {
            try {
                if (fileKm != null) {
                    FileUtil.writeFromStream(fileKm.getInputStream(), new File(appConfig.getUploadRoot("sync/" + userVo.getMobile()), "km-" + fileKm.getOriginalFilename()));
                }
                if (fileQc != null) {
                    FileUtil.writeFromStream(fileQc.getInputStream(), new File(appConfig.getUploadRoot("sync/" + userVo.getMobile()), "qc-" + fileQc.getOriginalFilename()));
                }
                if (filePz != null) {
                    FileUtil.writeFromStream(filePz.getInputStream(), new File(appConfig.getUploadRoot("sync/" + userVo.getMobile()), "pz-" + filePz.getOriginalFilename()));
                }
            } catch (Exception ee) {
                log.error("存储同步文件失败~", ee);
            }
            log.error("soft：{}", soft, e);
            throw new ServiceException(e.getMessage(), e);
        }
    }
}
