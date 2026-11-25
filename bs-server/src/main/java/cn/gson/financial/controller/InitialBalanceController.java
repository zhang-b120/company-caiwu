package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.kernel.annotation.DueCheck;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.BalanceDto;
import cn.gson.financial.kernel.model.dto.BalanceInitialDto;
import cn.gson.financial.kernel.model.dto.CurrencyDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.vo.BalanceAuxiliaryForm;
import cn.gson.financial.kernel.model.vo.BalanceImportVo;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.*;
import cn.gson.financial.rpa.ExcelBalanceUpdater;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import cn.hutool.poi.excel.style.StyleUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ooxml.POIXMLProperties;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年08月05日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@RestController
@RequestMapping("/initial-balance")
@Slf4j
@RequiredArgsConstructor
public class InitialBalanceController {

    private final BalanceService balanceService;

    private final BalanceCalcService balanceCalcService;

    private final BalanceInitialService balanceInitialService;

    private final VoucherDetailsService detailsService;

    private final SubjectService subjectService;
    private final ExcelBalanceUpdater excelBalanceUpdater;
    private final AccountSetsRepository accountSetsRepository;

    @GetMapping
    @SaCheckPermission("setting-initialbalance-canview")
    public JsonResult list(BalanceService.Query query,
                           @SessionAccountSet AccountSets accountSets,
                           @SessionUser UserVo userVo) {
        query.setAccountSets(accountSets);
        query.setLocalCurrencyId(userVo.getLocalCurrencyId());
        List<BalanceDto> balanceDtoList = balanceInitialService.list(accountSets.getId(), query);
        Set<Long> subjectCodeList = balanceDtoList.stream()
                .filter(d -> d.getAuxiliaryDetailId() != null && d.getAuxiliaryDetailId() == 0L)
                .map(BalanceDto::getSubjectId).collect(Collectors.toSet());
        List<Long> codeUsed = detailsService.codeDataCheck(subjectCodeList, accountSets.getId());
        return JsonResult.successful()
                .data("balances", balanceDtoList)
                .data("codeUsed", codeUsed);
    }

    /**
     * 按账套ID查询期初列表（首页弹窗用），需用户有该账套权限。
     */
    @GetMapping("/by-account-set")
    @SaCheckPermission("setting-initialbalance-canview")
    public JsonResult listByAccountSet(@RequestParam Long accountSetsId,
                                       BalanceService.Query query,
                                       @SessionUser UserVo userVo) {
        Assert.isTrue(userVo.checkAstHasPermission(accountSetsId), "无该账套权限");
        AccountSets accountSets = accountSetsRepository.findById(accountSetsId).orElseThrow(() -> new ServiceException("账套不存在"));
        query.setAccountSets(accountSets);
        query.setLocalCurrencyId(userVo.getLocalCurrencyId());
        List<BalanceDto> balanceDtoList = balanceInitialService.list(accountSetsId, query);
        return JsonResult.successful().data("balances", balanceDtoList);
    }

    /**
     * 科目类别下的外币列表
     *
     * @param type
     * @param accountSets
     * @return
     */
    @GetMapping("currency")
    public JsonResult currency(String type, @SessionAccountSet AccountSets accountSets) {
        List<CurrencyDto> currencyList = balanceService.fetchCurrencyWithLocal(type, accountSets.getId());
        return JsonResult.successful(currencyList);
    }

    @PostMapping
    @DueCheck
    @SaCheckPermission("setting-initialbalance-canedit")
    public JsonResult save(@RequestBody List<BalanceInitialDto> balanceList, @SessionAccountSet AccountSets accountSets) {
        balanceInitialService.saveInitBalance(balanceList, accountSets);
        balanceCalcService.calcSynchronization(accountSets.getId(), YearMonth.from(accountSets.getEnableDate()));
        return JsonResult.successful();
    }

    /**
     * 按账套ID保存期初（首页弹窗用），需用户有该账套权限。
     */
    @PostMapping("/save-for-account-set")
    @DueCheck
    @SaCheckPermission("setting-initialbalance-canedit")
    public JsonResult saveForAccountSet(@RequestBody java.util.Map<String, Object> body, @SessionUser UserVo userVo) {
        Object idObj = body.get("accountSetsId");
        Object listObj = body.get("balanceList");
        Assert.notNull(idObj, "accountSetsId 不能为空");
        Long accountSetsId = idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(idObj.toString());
        Assert.isTrue(userVo.checkAstHasPermission(accountSetsId), "无该账套权限");
        AccountSets accountSets = accountSetsRepository.findById(accountSetsId).orElseThrow(() -> new ServiceException("账套不存在"));
        List<BalanceInitialDto> balanceList = listObj != null ? com.alibaba.fastjson.JSON.parseArray(com.alibaba.fastjson.JSON.toJSONString(listObj), BalanceInitialDto.class) : Collections.emptyList();
        balanceInitialService.saveInitBalance(balanceList, accountSets);
        balanceCalcService.calcSynchronization(accountSetsId, YearMonth.from(accountSets.getEnableDate()));
        return JsonResult.successful();
    }

    @PostMapping("/auxiliary")
    @SaCheckPermission("setting-initialbalance-canedit")
    public JsonResult auxiliary(@SessionAccountSet AccountSets accountSets, @RequestBody BalanceAuxiliaryForm auxiliaryForm) {
        balanceService.saveAuxiliary(accountSets, auxiliaryForm);
        return JsonResult.successful();
    }

    /**
     * 删除辅助核算期初
     *
     * @param accountSets
     * @param id
     * @param auxiliaryId
     * @return
     */
    @DeleteMapping("/auxiliary/{id}/{auxiliaryId}")
    @SaCheckPermission("setting-initialbalance-canedit")
    public JsonResult delAuxiliary(@SessionAccountSet AccountSets accountSets, @PathVariable Long id, @PathVariable Long auxiliaryId) {
        balanceService.delAuxiliary(accountSets, id, auxiliaryId);
        balanceCalcService.calcSynchronization(accountSets.getId(), YearMonth.from(accountSets.getEnableDate()));
        return JsonResult.successful();
    }

    /**
     * 试算平衡
     *
     * @param accountSets
     * @return
     */
    @GetMapping("/trialBalance")
    public JsonResult trialBalance(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(balanceInitialService.trialBalance(accountSets.getId()));
    }

    /**
     * 按账套ID试算平衡（首页弹窗用），需用户有该账套权限。
     */
    @GetMapping("/trialBalance/by-account-set")
    @SaCheckPermission("setting-initialbalance-canview")
    public JsonResult trialBalanceByAccountSet(@RequestParam Long accountSetsId,
                                              @SessionUser UserVo userVo) {
        Assert.isTrue(userVo.checkAstHasPermission(accountSetsId), "无该账套权限");
        AccountSets accountSets = accountSetsRepository.findById(accountSetsId).orElseThrow(() -> new ServiceException("账套不存在"));
        return JsonResult.successful(balanceInitialService.trialBalance(accountSetsId));
    }

    /**
     * 导入
     *
     * @param multipartFile
     * @return
     */
    @PostMapping("/import")
    @DueCheck
    @SaCheckPermission("setting-initialbalance-canimport")
    public JsonResult importVoucher(@RequestParam("file") MultipartFile multipartFile, @SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets, @RequestParam(defaultValue = "false") Boolean cashFlow) {
        Long accountSetsId = accountSets.getId();
        try {
            ZipSecureFile.setMinInflateRatio(0.005);
            ExcelReader reader = ExcelUtil.getReader(multipartFile.getInputStream());

            // 处理一下带公式的单元格，直接计算出结果填写上去
            FormulaEvaluator evaluator = reader.getWorkbook().getCreationHelper().createFormulaEvaluator();
            reader.read((cell, o) -> {
                if (cell != null && cell.getCellType() == CellType.FORMULA) {
                    CellValue evaluate = evaluator.evaluate(cell);
                    cell.setCellValue(evaluate.getNumberValue());
                }
            });

            List<BalanceInitialDto> details = new ArrayList<>();
            List<BalanceImportVo> balanceImportVos = new ArrayList<>();
            List<Object> objects = reader.readRow(0);
            if (reader.readRow(1).stream().anyMatch(s -> "数量".equalsIgnoreCase(s.toString()))) {
                //带数量核算
                for (int i = 0; i < objects.size(); i++) {
                    Cell cell = reader.getOrCreateCell(i, 1);
                    if (cell != null) {
                        String value = cell.getStringCellValue();
                        if (!objects.get(i).equals(value)) {
                            cell.setCellValue(objects.get(i) + value);
                        } else if (objects.get(i) != null && StrUtil.isEmpty(objects.get(i).toString())) {
                            cell.setCellValue(objects.get(i).toString());
                        }
                    }
                }
                balanceImportVos = reader.read(1, 2, reader.getRowCount(), BalanceImportVo.class);
            } else {
                balanceImportVos = reader.readAll(BalanceImportVo.class);
            }

            Map<String, SubjectVo> subjectVoMap = subjectService.list(accountSetsId).stream().collect(Collectors.toMap(SubjectVo::getCode, s -> s));
            for (BalanceImportVo importVo : balanceImportVos) {
                BalanceInitialDto balance = new BalanceInitialDto();
                balance.setAccountSetsId(accountSetsId);
                balance.setCurrencyId(importVo.getCurrencyId());
                if (balance.getCurrencyId() == null || balance.getCurrencyId() == 0L) {
                    balance.setCurrencyId(userVo.getLocalCurrencyId());
                }
                Assert.isTrue(subjectVoMap.containsKey(importVo.getCode()), importVo.getCode() + "在账套中不存在，请先创建科目~");
                SubjectVo subjectVo = subjectVoMap.get(importVo.getCode());
                balance.setSubjectId(subjectVo.getId());
                balance.setBalanceDirection(subjectVo.getBalanceDirection());
                balance.setAuxiliaryDetailId(importVo.getAuxiliaryDetailId());
                balance.setSubjectCode(importVo.getCode());
                balance.setTreeId(subjectVo.getId());
                balance.setParentTreeId(subjectVo.getParentId());
                balance.setLeaf(subjectVo.getLeaf());

                if (balance.getLeaf()) {
                    balance.setBeginBalance(importVo.getBeginBalance());
                    balance.setYearTotalDebit(importVo.getYearTotalDebit());
                    balance.setYearTotalCredit(importVo.getYearTotalCredit());
                    balance.setYearTotal(importVo.getYearTotal());
                    balance.setBeginQuantity(importVo.getBeginQuantity());
                    balance.setYearTotalCreditQuantity(importVo.getYearTotalCreditQuantity());
                    balance.setYearTotalDebitQuantity(importVo.getYearTotalDebitQuantity());
                    balance.setYearTotalQuantity(importVo.getYearTotalQuantity());
                }

                balance.calculateYearTotal();
                details.add(balance);
            }

            Set<Long> auxiliaryDetailIds = details.stream().filter(d -> d.getAuxiliaryDetailId() != null && d.getAuxiliaryDetailId() != 0)
                    .map(BalanceInitialDto::getAuxiliaryDetailId).collect(Collectors.toSet());

            if (!auxiliaryDetailIds.isEmpty()) {
                Assert.isTrue(balanceInitialService.checkAuxiliaryDetailIds(auxiliaryDetailIds, accountSetsId),
                        "期初模板错误，请模板导入~");
            }

            // 所有科目编码
            Set<String> allCodeSet = details.stream().map(BalanceInitialDto::getUniCode).collect(Collectors.toSet());
            // 根节点科目数据行
            List<BalanceInitialDto> leafBalance = details.stream().filter(b -> allCodeSet.stream().filter(c -> !c.equals(b.getUniCode()))
                    .noneMatch(c -> c.startsWith(b.getUniCode()))).toList();
            // 跟节点科目编码
            Set<String> leafCodeSet = leafBalance.stream().map(BalanceInitialDto::getUniCode).collect(Collectors.toSet());

            // 过滤出非根节点，然后计算累计
            List<BalanceInitialDto> notLeafBalance = details.stream().filter(b -> !leafCodeSet.contains(b.getUniCode())).map(d -> {
                // 复制一个新对象
                BalanceInitialDto newDto = new BalanceInitialDto();
                newDto.setSubjectId(d.getSubjectId());
                newDto.setBalanceDirection(d.getBalanceDirection());
                newDto.setAccountSetsId(accountSetsId);
                newDto.setAuxiliaryDetailId(d.getAuxiliaryDetailId());
                newDto.setCurrencyId(d.getCurrencyId());
                leafBalance.stream().filter(b -> b.getUniCode().startsWith(d.getUniCode())).forEach(od -> {
                    accumulatedCalculation(newDto, od);
                });
                return newDto;
            }).toList();

            details = CollUtil.unionAll(leafBalance, notLeafBalance);
            //过滤掉没有值的行
            details = details.stream().filter(BalanceInitialDto::isUsed).collect(Collectors.toList());
            balanceInitialService.updateInitBalance(details, userVo.getAccountSets());
            balanceCalcService.calc(userVo.getAccountSets().getId(), YearMonth.from(userVo.getAccountSets().getEnableDate()));
            return JsonResult.successful(details);
        } catch (ServiceException | IllegalArgumentException e) {
            log.error("导入失败", e);
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("导入失败", e);
            return JsonResult.failure("导入失败~" + e.getMessage());
        }
    }

    /**
     * 计算累计
     *
     * @param balanceDto
     * @param d
     */
    private void accumulatedCalculation(BalanceInitialDto balanceDto, BalanceInitialDto d) {
        if (balanceDto.getBalanceDirection().equals(d.getBalanceDirection())) {
            balanceDto.setBeginBalance(NumberUtil.add(d.getBeginBalance(), balanceDto.getBeginBalance()));
            balanceDto.setBeginBalanceFor(NumberUtil.add(d.getBeginBalanceFor(), balanceDto.getBeginBalanceFor()));
            balanceDto.setBeginQuantity(NumberUtil.add(d.getBeginQuantity(), balanceDto.getBeginQuantity()));
            balanceDto.setYearTotal(NumberUtil.add(d.getYearTotal(), balanceDto.getYearTotal()));
            balanceDto.setYearTotalFor(NumberUtil.add(d.getYearTotalFor(), balanceDto.getYearTotalFor()));
        } else {
            balanceDto.setBeginBalance(NumberUtil.sub(balanceDto.getBeginBalance(), d.getBeginBalance()));
            balanceDto.setBeginBalanceFor(NumberUtil.sub(balanceDto.getBeginBalanceFor(), d.getBeginBalanceFor()));
            balanceDto.setBeginQuantity(NumberUtil.sub(balanceDto.getBeginQuantity(), d.getBeginQuantity()));
            balanceDto.setYearTotal(NumberUtil.sub(balanceDto.getYearTotal(), d.getYearTotal()));
            balanceDto.setYearTotalFor(NumberUtil.sub(balanceDto.getYearTotalFor(), d.getYearTotalFor()));
        }
        balanceDto.setYearTotalCredit(NumberUtil.add(d.getYearTotalCredit(), balanceDto.getYearTotalCredit()));
        balanceDto.setYearTotalDebit(NumberUtil.add(d.getYearTotalDebit(), balanceDto.getYearTotalDebit()));
        balanceDto.setYearTotalCreditFor(NumberUtil.add(d.getYearTotalCreditFor(), balanceDto.getYearTotalCreditFor()));
        balanceDto.setYearTotalDebitFor(NumberUtil.add(d.getYearTotalDebitFor(), balanceDto.getYearTotalDebitFor()));
        balanceDto.setYearTotalCreditQuantity(NumberUtil.add(d.getYearTotalCreditQuantity(), balanceDto.getYearTotalCreditQuantity()));
        balanceDto.setYearTotalDebitQuantity(NumberUtil.add(d.getYearTotalDebitQuantity(), balanceDto.getYearTotalDebitQuantity()));

    }

    /**
     * 导出
     *
     * @param userVo
     * @return
     */
    @GetMapping("/download")
    @SaCheckPermission("setting-initialbalance-canexport")
    public void downloadVoucher(@SessionUser UserVo userVo, HttpServletResponse response) throws IOException {
        this.downTemplate(response, userVo, true);
    }

    /**
     * 下载模板
     *
     * @param response
     * @param userVo
     * @param download
     * @throws IOException
     */
    @GetMapping("template")
    @SaCheckPermission("setting-initialbalance-canview")
    public void downTemplate(HttpServletResponse response, @SessionUser UserVo userVo, boolean download) throws IOException {
        BalanceService.Query query = new BalanceService.Query();
        query.setAccountSets(userVo.getAccountSets());
        query.setLocalCurrencyId(userVo.getLocalCurrencyId());
        List<BalanceDto> balanceDtoList = balanceInitialService.list(userVo.getAccountSets().getId(), query);
        ExcelWriter writer;
        boolean isUnit = balanceDtoList.stream().anyMatch(v -> StrUtil.isNotEmpty(v.getUnit()));
        if (isUnit) {
            writer = withUnit(balanceDtoList);
        } else {
            writer = withoutUnit(balanceDtoList);
        }
        XSSFWorkbook workbook = (XSSFWorkbook) writer.getWorkbook();
        POIXMLProperties properties = workbook.getProperties();
        properties.getCoreProperties().setTitle(userVo.getAccountSets().getCompanyName() + "-财务初始余额导入模板");
        properties.getCoreProperties().setCreator(userVo.getRealName());
        properties.getCoreProperties().setKeywords(isUnit ? "带单位" : "不带单位");
        properties.commit();
        String fileName = userVo.getAccountSets().getCompanyName() + "-财务初始余额" + (download ? "" : "导入模板") + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx";
        response.setContentType(ExcelUtil.XLSX_CONTENT_TYPE);
        response.setHeader("Content-disposition", "attachment; filename=" + new String(fileName.getBytes(StandardCharsets.UTF_8), "ISO8859-1"));
        writer.flush(response.getOutputStream());
        writer.close();
    }
@GetMapping("/downList")
    public void downList(HttpServletResponse response, @SessionUser UserVo userVo, boolean download) throws IOException {
        List<AccountSets> list = userVo.getAccountSetsList();
        for(int i=0;i<list.size();i++){
            AccountSets accountSets = list.get(i);
            BalanceService.Query query = new BalanceService.Query();
            query.setAccountSets(accountSets);
            query.setLocalCurrencyId(userVo.getLocalCurrencyId());
            List<BalanceDto> balanceDtoList = balanceInitialService.list(accountSets.getId(), query);
            ExcelWriter writer;
            boolean isUnit = balanceDtoList.stream().anyMatch(v -> StrUtil.isNotEmpty(v.getUnit()));
            if (isUnit) {
                writer = withUnit(balanceDtoList);
            } else {
                writer = withoutUnit(balanceDtoList);
            }
            XSSFWorkbook workbook = (XSSFWorkbook) writer.getWorkbook();
            POIXMLProperties properties = workbook.getProperties();
            properties.getCoreProperties().setTitle(accountSets.getCompanyName() + "-财务初始余额导入模板");
            properties.getCoreProperties().setCreator(userVo.getRealName());
            properties.getCoreProperties().setKeywords(isUnit ? "带单位" : "不带单位");
            properties.commit();
            String fileName = accountSets.getCompanyName() + "-财务初始余额" + (download ? "" : "导入模板") + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx";
            response.setContentType(ExcelUtil.XLSX_CONTENT_TYPE);
            response.setHeader("Content-disposition", "attachment; filename=" + new String(fileName.getBytes(StandardCharsets.UTF_8), "ISO8859-1"));
            writer.flush(response.getOutputStream());
            writer.close();
        }
    }

    /**
     * 无单位核算的期初导入
     *
     * @param balanceVos
     * @return
     */
    private ExcelWriter withoutUnit(List<BalanceDto> balanceVos) {
        List<String> columns = StrUtil.split("科目编码,科目名称,方向,辅助核算项目,辅助核算编码,辅助核算名称,期初余额,借方累计,贷方累计,年初余额,ID,币别ID,科目ID,辅助ID,树ID,父科目,Leaf", ",");
        ExcelWriter writer = ExcelUtil.getWriter(true);
        CellStyle defaultCellStyle = writer.getCellStyle();
        writer.setColumnWidth(-1, 10);
        writer.setColumnWidth(0, 14);
        writer.setColumnWidth(1, 32);
        writer.setColumnWidth(2, 6);
        writer.setColumnWidth(3, 16);
        writer.setColumnWidth(4, 16);
        writer.setColumnWidth(5, 16);
        writer.setRowHeight(-1, 20);
        StyleUtil.setAlign(defaultCellStyle, HorizontalAlignment.LEFT, VerticalAlignment.CENTER);

        writer.renameSheet("财务初始余额表");
        writer.writeHeadRow(columns);

        XSSFSheet sheet = (XSSFSheet) writer.getSheet();
        sheet.protectSheet("891123");
        sheet.setColumnHidden(10, true);
        sheet.setColumnHidden(11, true);
        sheet.setColumnHidden(12, true);
        sheet.setColumnHidden(13, true);
        sheet.setColumnHidden(14, true);
        sheet.setColumnHidden(15, true);
        sheet.setColumnHidden(16, true);
        sheet.lockFormatColumns(false);
        writer.setOnlyAlias(true);

        CellStyle lockCellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
        lockCellStyle.setLocked(true);
        StyleUtil.setColor(lockCellStyle, IndexedColors.GREY_25_PERCENT, FillPatternType.SOLID_FOREGROUND);

        Short format = StyleUtil.getFormat(writer.getWorkbook(), "#,##0.00_);[Red](#,##0.00)");
        CellStyle unLockCellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
        unLockCellStyle.setLocked(false);
        unLockCellStyle.setDataFormat(format);
        StyleUtil.setAlign(unLockCellStyle, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER);

        for (BalanceDto vo : balanceVos) {
            List<Object> row = new ArrayList<>();
            row.add(StrUtil.repeat("  ", vo.getLevel() - 1) + vo.getSubjectCode());
            row.add(StrUtil.repeat("  ", vo.getLevel() - 1) + vo.getSubjectName());
            row.add(vo.getBalanceDirection());

            row.add(vo.isAd() ? "是" : "否");
            row.add(vo.getAuxiliaryCode());
            row.add(vo.getAuxiliaryName());
            row.add(nullZeroToEmptyStr(vo.getBeginBalance()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalDebit()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalCredit()));
            row.add("");
            row.add(String.valueOf(vo.getId()));
            row.add(String.valueOf(vo.getCurrencyId()));
            row.add(String.valueOf(vo.getSubjectId()));
            row.add(String.valueOf(vo.getAuxiliaryDetailId()));
            row.add(String.valueOf(vo.getTreeId()));
            row.add(String.valueOf(vo.getParentTreeId()));
            row.add(String.valueOf(vo.getLeaf()));

            int currentRow = writer.getCurrentRow();
            writer.writeRow(row);
            Row r = writer.getOrCreateRow(currentRow);
            if (!vo.getLeaf() || (vo.isAd() && vo.getParentTreeId() == null)) {
                for (int i = 0; i < 10; i++) {
                    r.getCell(i).setCellStyle(lockCellStyle);
                }

                for (int i = 6; i < 10; i++) {
                    CellStyle cellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), r.getCell(i).getCellStyle());
                    cellStyle.setAlignment(HorizontalAlignment.RIGHT);
                    r.getCell(i).setCellStyle(cellStyle);
                }
            } else {
                for (int i = 6; i < 9; i++) {
                    r.getCell(i).setCellStyle(unLockCellStyle);
                }
            }
            Cell cell = r.getCell(9);
            CellStyle cellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), cell.getCellStyle());
            cellStyle.setDataFormat(format);
            StyleUtil.setAlign(cellStyle, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER);
            cell.setCellStyle(cellStyle);
            cell.setCellFormula("IF(ISERROR(IF($C:$C=\"贷\",IF(ISNUMBER($G:$G),$G:$G,0)+IF(ISNUMBER($H:$H),$H:$H,0)-IF(ISNUMBER($I:$I),$I:$I,0),IF(ISNUMBER($G:$G),$G:$G,0)-IF(ISNUMBER($H:$H),$H:$H,0)+IF(ISNUMBER($I:$I),$I:$I,0))),\"\",IF($C:$C=\"贷\",IF(ISNUMBER($G:$G),$G:$G,0)+IF(ISNUMBER($H:$H),$H:$H,0)-IF(ISNUMBER($I:$I),$I:$I,0),IF(ISNUMBER($G:$G),$G:$G,0)-IF(ISNUMBER($H:$H),$H:$H,0)+IF(ISNUMBER($I:$I),$I:$I,0)))");
        }
        return writer;
    }

    /**
     * 有单位核算的期初导入
     *
     * @param balanceVos
     * @return
     * @throws IOException
     */
    private ExcelWriter withUnit(List<BalanceDto> balanceVos) {
        List<String> columns = StrUtil.split("科目编码,科目名称,方向,辅助核算项目,辅助核算编码,辅助核算名称,期初余额,,借方累计,,贷方累计,,年初余额,,ID,币别ID,科目ID,辅助ID,树ID,父科目,Leaf", ",");
        List<String> columns2 = StrUtil.split(",,,,,,数量,金额,数量,金额,数量,金额,数量,金额", ",");
        ExcelWriter writer = ExcelUtil.getWriter(true);
        CellStyle defaultCellStyle = writer.getCellStyle();
        writer.setColumnWidth(-1, 10);
        writer.setColumnWidth(0, 14);
        writer.setColumnWidth(1, 32);
        writer.setColumnWidth(2, 6);
        writer.setColumnWidth(3, 16);
        writer.setColumnWidth(4, 16);
        writer.setColumnWidth(5, 16);
        writer.setRowHeight(-1, 20);
        StyleUtil.setAlign(defaultCellStyle, HorizontalAlignment.LEFT, VerticalAlignment.CENTER);

        writer.renameSheet("财务初始余额表");
        writer.writeHeadRow(columns);
        writer.writeHeadRow(columns2);

        writer.merge(0, 1, 0, 0, "科目编码", writer.getHeadCellStyle());
        writer.merge(0, 1, 1, 1, "科目名称", writer.getHeadCellStyle());
        writer.merge(0, 1, 2, 2, "方向", writer.getHeadCellStyle());
        writer.merge(0, 1, 3, 3, "辅助核算项目", writer.getHeadCellStyle());
        writer.merge(0, 1, 4, 4, "辅助核算编码", writer.getHeadCellStyle());
        writer.merge(0, 1, 5, 5, "辅助核算名称", writer.getHeadCellStyle());
        writer.merge(0, 0, 6, 7, "期初余额", writer.getHeadCellStyle());
        writer.merge(0, 0, 8, 9, "借方累计", writer.getHeadCellStyle());
        writer.merge(0, 0, 10, 11, "贷方累计", writer.getHeadCellStyle());
        writer.merge(0, 0, 12, 13, "年初余额", writer.getHeadCellStyle());

        XSSFSheet sheet = (XSSFSheet) writer.getSheet();
        sheet.protectSheet("891123");
        sheet.setColumnHidden(14, true);
        sheet.setColumnHidden(15, true);
        sheet.setColumnHidden(16, true);
        sheet.setColumnHidden(17, true);
        sheet.setColumnHidden(18, true);
        sheet.setColumnHidden(19, true);
        sheet.setColumnHidden(20, true);
        sheet.lockFormatColumns(false);
        writer.setOnlyAlias(true);

        CellStyle lockCellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
        lockCellStyle.setLocked(true);
        StyleUtil.setColor(lockCellStyle, IndexedColors.GREY_25_PERCENT, FillPatternType.SOLID_FOREGROUND);

        Short format = StyleUtil.getFormat(writer.getWorkbook(), "#,##0.00_);[Red](#,##0.00)");
        Short slFormat = StyleUtil.getFormat(writer.getWorkbook(), "0.0000;[Red](0.0000)");

        CellStyle unLockCellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), writer.getCellStyle());
        unLockCellStyle.setLocked(false);
        unLockCellStyle.setDataFormat(format);
        StyleUtil.setAlign(unLockCellStyle, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER);

        for (BalanceDto vo : balanceVos) {
            List<Object> row = new ArrayList<>();
            row.add(StrUtil.repeat("  ", vo.getLevel() - 1) + vo.getSubjectCode());
            row.add(StrUtil.repeat("  ", vo.getLevel() - 1) + vo.getSubjectName());
            row.add(vo.getBalanceDirection());
            row.add(vo.isAd() ? "是" : "否");
            //辅助核算的名称
            row.add(vo.getAuxiliaryName());
            row.add(vo.getAuxiliaryCode());
            row.add(nullZeroToEmptyStr(vo.getBeginQuantity()));
            row.add(nullZeroToEmptyStr(vo.getBeginBalance()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalDebitQuantity()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalDebit()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalCreditQuantity()));
            row.add(nullZeroToEmptyStr(vo.getYearTotalCredit()));
            row.add("");
            row.add("");
            row.add(String.valueOf(vo.getId()));
            row.add(String.valueOf(vo.getCurrencyId()));
            row.add(String.valueOf(vo.getSubjectId()));
            row.add(String.valueOf(vo.getAuxiliaryDetailId()));
            row.add(String.valueOf(vo.getTreeId()));
            row.add(String.valueOf(vo.getParentTreeId()));
            row.add(String.valueOf(vo.getLeaf()));
            int currentRow = writer.getCurrentRow();

            writer.writeRow(row);

            Row r = writer.getOrCreateRow(currentRow);

            if (!vo.getLeaf() || (vo.isAd() && vo.getParentTreeId() == null)) {
                for (int i = 0; i < 14; i++) {
                    r.getCell(i).setCellStyle(lockCellStyle);
                }
                for (int i = 6; i < 14; i++) {
                    CellStyle cellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), r.getCell(i).getCellStyle());
                    cellStyle.setAlignment(HorizontalAlignment.RIGHT);
                    r.getCell(i).setCellStyle(cellStyle);
                }
            } else {
                for (int i = 6; i < 12; i++) {
                    r.getCell(i).setCellStyle(unLockCellStyle);
                }
            }

            for (int i = 6; i < 12; i++) {
                Cell cell = r.getCell(i);
                CellStyle cellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), cell.getCellStyle());
                if (i % 2 == 0) {
                    cellStyle.setDataFormat(slFormat);
                    //如果科目未设置数量核算，则需要关闭数量单元编辑
                    if (StrUtil.isEmpty(vo.getUnit())) {
                        cellStyle.setLocked(true);
                    }
                } else {
                    cellStyle.setDataFormat(format);
                }
                cell.setCellStyle(cellStyle);
            }

            Cell slCell = r.getCell(12);
            CellStyle slCellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), slCell.getCellStyle());
            slCellStyle.setDataFormat(slFormat);
            StyleUtil.setAlign(slCellStyle, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER);
            slCell.setCellStyle(slCellStyle);
            slCell.setCellFormula("IF(ISERROR(IF($C:$C=\"贷\",IF(ISNUMBER($G:$G),$G:$G,0)+IF(ISNUMBER($I:$I),$I:$I,0)-IF(ISNUMBER($K:$K),$K:$K,0),IF(ISNUMBER($G:$G),$G:$G,0)-IF(ISNUMBER($I:$I),$I:$I,0)+IF(ISNUMBER($K:$K),$K:$K,0))),\"\",IF($C:$C=\"贷\",IF(ISNUMBER($G:$G),$G:$G,0)+IF(ISNUMBER($I:$I),$I:$I,0)-IF(ISNUMBER($K:$K),$K:$K,0),IF(ISNUMBER($G:$G),$G:$G,0)-IF(ISNUMBER($I:$I),$I:$I,0)+IF(ISNUMBER($K:$K),$K:$K,0)))");

            Cell jeCell = r.getCell(13);
            CellStyle cellStyle = StyleUtil.cloneCellStyle(writer.getWorkbook(), jeCell.getCellStyle());
            cellStyle.setDataFormat(format);
            StyleUtil.setAlign(cellStyle, HorizontalAlignment.RIGHT, VerticalAlignment.CENTER);
            jeCell.setCellStyle(cellStyle);
            jeCell.setCellFormula("IF(ISERROR(IF($C:$C=\"贷\",IF(ISNUMBER($H:$H),$H:$H,0)+IF(ISNUMBER($J:$J),$J:$J,0)-IF(ISNUMBER($L:$L),$L:$L,0),IF(ISNUMBER($H:$H),$H:$H,0)-IF(ISNUMBER($J:$J),$J:$J,0)+IF(ISNUMBER($L:$L),$L:$L,0))),\"\",IF($C:$C=\"贷\",IF(ISNUMBER($H:$H),$H:$H,0)+IF(ISNUMBER($J:$J),$J:$J,0)-IF(ISNUMBER($L:$L),$L:$L,0),IF(ISNUMBER($H:$H),$H:$H,0)-IF(ISNUMBER($J:$J),$J:$J,0)+IF(ISNUMBER($L:$L),$L:$L,0)))");
        }

        return writer;
    }

    private Object nullZeroToEmptyStr(Number vo) {
        return vo == null || vo.longValue() == 0L ? "" : vo;
    }
    @PostMapping("/doTask")
    public JsonResult doTask(@SessionUser UserVo userVo) {
        excelBalanceUpdater.doTask(userVo);

        return null;
    }
}
