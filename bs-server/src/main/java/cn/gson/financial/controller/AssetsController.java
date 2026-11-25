package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.kernel.annotation.DueCheck;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.dto.AssetsTypeDto;
import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Assets;
import cn.gson.financial.kernel.model.entity.AssetsChange;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.enums.AssetsDisposeType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.vo.AssetsImportVo;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.*;
import cn.gson.financial.kernel.service.AssetsService.AssetsQuery;
import cn.gson.financial.kernel.tool.ImportVoUtil;
import cn.gson.financial.kernel.tool.ValidatorUtils;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 固定资产管理</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequestMapping("/assets")
@RequiredArgsConstructor
public class AssetsController {

    private final AssetsService assetsService;

    private final AssetsTypeService assetsTypeService;

    private final SubjectService subjectService;

    private final AssetsDepreciationService assetsDepreciationService;

    @PostMapping("list")
    @SaCheckPermission("fixedassets-fixedassets-card-canview")
    public JsonResult list(Page page, @RequestBody AssetsQuery query, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(assetsService.list(page, query, accountSets.getId()));
    }

    @PostMapping
    @DueCheck
    @SaCheckPermission("ixedassets-fixedassets-card-canedit")
    public JsonResult save(@RequestBody Assets entity, @SessionAccountSet AccountSets accountSets) {
        entity.setAccountSetsId(accountSets.getId());
        assetsService.save(entity);
        return JsonResult.successful(entity);
    }

    @DeleteMapping("{id:\\d+}")
    @DueCheck
    @SaCheckPermission("ixedassets-fixedassets-card-candelete")
    public JsonResult delete(@PathVariable Long id, @SessionAccountSet AccountSets accountSets) {
        assetsService.delete(id, accountSets.getId());
        return JsonResult.successful();
    }

    @DeleteMapping("batch")
    @DueCheck
    @SaCheckPermission("ixedassets-fixedassets-card-candelete")
    public JsonResult batchDelete(Long[] id, @SessionAccountSet AccountSets accountSets) {
        for (Long integer : id) {
            assetsService.delete(integer, accountSets.getId());
        }
        return JsonResult.successful();
    }

    @PostMapping("copy")
    @DueCheck
    @SaCheckPermission("ixedassets-fixedassets-card-canedit")
    public JsonResult copy(@RequestBody Long[] copyList, @SessionAccountSet AccountSets accountSets) {
        assetsService.copy(copyList, accountSets.getId());
        return JsonResult.successful();
    }

    @GetMapping("/change")
    @SaCheckPermission("ixedassets-fixedassets-card-canchange")
    public JsonResult changeList(Page page, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(assetsService.changeList(page, accountSets.getId()));
    }

    @PostMapping("/change")
    @DueCheck
    @SaCheckPermission("ixedassets-fixedassets-card-canchange")
    public JsonResult changeSave(@RequestBody AssetsChange entity, @SessionAccountSet AccountSets accountSets) {
        entity.setAccountSetsId(accountSets.getId());
        assetsService.changeSave(entity);
        return JsonResult.successful(entity);
    }

    @PostMapping("/summary")
    public JsonResult summary(@RequestBody Dict dict, @SessionAccountSet AccountSets accountSets) {
        dict.set("accountSetsId", accountSets.getId());
        return JsonResult.successful(assetsService.summary(dict));
    }

    @PostMapping("/check")
    public JsonResult check(@RequestBody AssetsQuery query, @SessionAccountSet AccountSets accountSets, @SessionUser UserVo userVo) {
        return JsonResult.successful(assetsService.check(accountSets.getId(), userVo.getLocalCurrencyId(), query));
    }

    @GetMapping("/dispose/{assetsId:\\d+}")
    public JsonResult dispose(@PathVariable Long assetsId, AssetsDisposeType disposeType, @SessionAccountSet AccountSets accountSets) {
        assetsService.dispose(accountSets.getId(), assetsId, disposeType);
        return JsonResult.successful();
    }

    @DeleteMapping("/change/{id:\\d+}")
    public JsonResult changeDelete(@PathVariable Long id, @SessionAccountSet AccountSets accountSets) {
        assetsService.changeDelete(new PrimaryKey(id, accountSets.getId()));
        return JsonResult.successful();
    }

    @GetMapping("/change/relate/{voucherId:\\d+}")
    public JsonResult relateVoucher(@PathVariable Long voucherId, @RequestParam("id") Long[] idList, @SessionAccountSet AccountSets accountSets) {
        assetsService.relateVoucher(voucherId, idList, accountSets.getId());
        return JsonResult.successful();
    }

    @GetMapping("/change/generate/voucher")
    public JsonResult generateVoucher(@RequestParam("id") Long[] idList, @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(assetsService.generateVoucher(idList, accountSets.getId()));
    }

    @GetMapping("/checkDate")
    public JsonResult checkDateList(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(assetsService.checkDateList(accountSets.getId()));
    }

    @GetMapping("/template")
    @SaCheckPermission("fixedassets-fixedassets-card-canimport")
    public ResponseEntity<byte[]> template(@SessionAccountSet AccountSets accountSets, @SessionUser UserVo userVo) throws IOException {
        Long accountSetId = accountSets.getId();
        List<Subject> subjectVos = subjectService.selectData(accountSetId);
        AuxiliaryDataService dataService = SpringUtil.getBean(AccountingCategoryType.DEPARTMENT.dataService);
        List<? extends AcDetails> details = dataService.loadData(accountSetId);
        List<AssetsTypeDto> assetsTypeDtos = assetsTypeService.list(accountSetId);

        ExcelReader reader = ExcelUtil.getReader(ResourceUtil.getResource("template/assets-template.xls").openStream());
        Workbook workbook = reader.getWorkbook();
        Sheet hidden = workbook.getSheet("hidden");
        for (int i = 0; i < assetsTypeDtos.size(); i++) {
            Row row = hidden.getRow(i);
            if (row == null) {
                row = hidden.createRow(i);
            }
            Cell cell = row.getCell(0);
            if (cell == null) {
                cell = row.createCell(0);
            }
            cell.setCellValue("'" + assetsTypeDtos.get(i).getName());
        }
        Name category1Name = workbook.getName("hidden");
        category1Name.setRefersToFormula("hidden!" + "$A$1:$A$" + assetsTypeDtos.size());
        CellStyle cellStyle = workbook.createCellStyle();
        cellStyle.setQuotePrefixed(true);

        Sheet sheet = workbook.getSheetAt(1);
        for (int i = 0; i < subjectVos.size(); i++) {
            Subject subjectVo = subjectVos.get(i);
            Row row = sheet.createRow(i + 1);
            Cell cell = row.createCell(0);
            cell.setCellStyle(cellStyle);
            cell.setCellValue(subjectVo.getCode());
            row.createCell(1).setCellValue(subjectVo.getFullName().replace(subjectVo.getCode() + "-", ""));
        }

        sheet = workbook.getSheetAt(2);
        for (int i = 0; i < details.size(); i++) {
            AcDetails d = details.get(i);
            Row row = sheet.createRow(i + 1);
            Cell cell = row.createCell(0);
            cell.setCellStyle(cellStyle);
            cell.setCellValue(d.getCode());
            row.createCell(1).setCellValue(d.getName());
        }

        workbook.setActiveSheet(0);
        workbook.setSelectedTab(0);
        ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
        workbook.write(byteOutputStream);
        HttpHeaders httpHeaders = new HttpHeaders();
        String fileName = userVo.getAccountSets().getCompanyName() + "-资产导入模板.xls";
        httpHeaders.setContentType(new MediaType("application", "vnd.ms-excel"));
        httpHeaders.setContentDisposition(ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(byteOutputStream.toByteArray(),
                httpHeaders,
                HttpStatus.OK);
    }

    @PostMapping("/import")
    @DueCheck
    @SaCheckPermission("fixedassets-fixedassets-card-canimport")
    public JsonResult importVoucher(@RequestParam("file") MultipartFile multipartFile, @SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets) throws IOException {
        Long accountSetsId = accountSets.getId();
        ExcelReader reader = ExcelUtil.getReader(multipartFile.getInputStream());
        ImportVoUtil.setHeaderAlias(AssetsImportVo.class, reader);
        List<AssetsImportVo> voList = reader.readAll(AssetsImportVo.class);
        voList = voList.stream().filter(s -> StrUtil.isEmpty(s.getCheck()) && StrUtil.isNotEmpty(s.getName()) && StrUtil.isNotEmpty(s.getCode())).collect(Collectors.toList());
        AuxiliaryDataService dataService = SpringUtil.getBean(AccountingCategoryType.DEPARTMENT.dataService);
        List<? extends AcDetails> details = dataService.loadData(accountSetsId);
        List<AssetsTypeDto> assetsTypeDtos = assetsTypeService.list(accountSetsId);
        LocalDate localDate = assetsDepreciationService.getLast(accountSetsId);
        if (localDate == null) {
            localDate = userVo.getAccountSets().getCurrentAccountDate();
        }
        Map<String, Long> deptMap = details.stream().collect(Collectors.toMap(AcDetails::getCode, AcDetails::getId));
        Map<String, String> deptNameMap = details.stream().collect(Collectors.toMap(AcDetails::getCode, AcDetails::getName));
        Map<String, Long> typeMap = assetsTypeDtos.stream().collect(Collectors.toMap(a -> a.getName().trim(), AssetsTypeDto::getId));
        List<AssetsImportVo> errors = new ArrayList<>();
        if (CollUtil.isNotEmpty(voList)) {
            for (AssetsImportVo assetsVo : voList) {
                try {
                    ValidatorUtils.validate(assetsVo);

                    Assets assets = BeanUtil.toBean(assetsVo, Assets.class);
                    assets.setAccountSetsId(accountSetsId);
                    assets.setEntryDate(localDate);
                    Assert.notEmpty(assetsVo.getDeptCode(), "部门编码不能为空~");
                    Assert.isTrue(deptMap.containsKey(assetsVo.getDeptCode()), "部门编码" + assetsVo.getDeptCode() + "系统中不存在~");
                    assets.setDeptId(deptMap.get(assetsVo.getDeptCode()));
                    assets.setDeptName(deptNameMap.get(assetsVo.getDeptCode()));

                    if (typeMap.containsKey(assetsVo.getAssetsType())) {
                        assets.setAssetsTypeId(typeMap.get(assetsVo.getAssetsType()));
                    }
                    switch (assetsVo.getStatusVal()) {
                        case "正常":
                            assets.setStatus(1);
                            break;
                        case "停用":
                            assets.setStatus(2);
                            break;
                        case "出租":
                            assets.setStatus(3);
                            break;
                    }
                    switch (assetsVo.getAddTypeVal()) {
                        case "购入":
                            assets.setAddType(1L);
                            break;
                        case "在建工程转入":
                            assets.setAddType(2L);
                            break;
                        case "其他":
                            assets.setAddType(3L);
                            break;
                    }
                    switch (assetsVo.getDepreciationMethod()) {
                        case "平均年限法":
                            assets.setDepreciationMethod("0");
                            break;
                        case "不折旧":
                            assets.setDepreciationMethod("1");
                            break;
                        case "其他":
                    }
                    assets.setRemainingUseMonth(NumberUtil.sub(assets.getExpectUseMonth(), assets.getUseMonth()).intValue());
                    Assert.isTrue(assets.getRemainingUseMonth() >= 0, assets.getCode() + assets.getName() + "资产剩余使用月份必须大于等于0~");
                    assetsService.save(assets);
                } catch (Exception e) {
                    assetsVo.setError(e.getMessage());
                    errors.add(assetsVo);
                    log.error("资产导入失败~{}", assetsVo, e);
                }
            }
        }
        return JsonResult.successful(errors);
    }
}
