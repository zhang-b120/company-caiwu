package cn.gson.financial.ua.controller;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.AccountingCategoryDto;
import cn.gson.financial.kernel.model.dto.CashierIoTypeDto;
import cn.gson.financial.kernel.model.dto.CashierJournalDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.mapper.CashierIoTypeMapper;
import cn.gson.financial.kernel.model.vo.JournalImportVo;
import cn.gson.financial.kernel.service.*;
import cn.gson.financial.kernel.tool.ValidatorUtils;
import cn.gson.financial.kernel.model.ua.request.GetCashierJournalListReq;
import cn.gson.financial.kernel.model.ua.request.ImportCashierJournalReq;
import cn.gson.financial.kernel.model.ua.resp.GetUserInfoResp;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import cn.hutool.poi.excel.style.StyleUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFTextBox;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static cn.hutool.core.date.DatePattern.PURE_DATETIME_PATTERN;

/**
 * @Author zhb
 * @Date 2025-05-19
 * @Description
 */

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/cashier/journal")
public class UaCashierJournalController {

    private final CashierIoTypeMapper cashierIoTypeMapper;

    private final CashierJournalService cashierJournalService;
    private final CashierIoTypeService cashierIoTypeService;
    private final CheckoutService checkoutService;
    private final SubjectService subjectService;
    private final AccountingCategoryService accountingCategoryService;

    private final UserService userService;

    /**
     * 获取记账信息
     * @param req
     * @param accountId
     * @return
     */
    @SaIgnore
    @PostMapping("uaGetCashierJournalList/{accountId:\\d+}")
    public JsonResult uaGetCashierJournalList(@RequestBody GetCashierJournalListReq req, @PathVariable Long accountId) {
        LocalDate enableDate = userService.uaGetUserInfo(req.getAccountSetsId()).getEnableDate();
        CashierJournalDto begin = cashierJournalService.beginRow(req.getAccountSetsId(), accountId, req.getQuery().start, enableDate);
        PageResults<CashierJournalDto> pageResults = cashierJournalService.list(req.getAccountSetsId(), accountId, req.getPage(), req.getQuery());
        pageResults.getResults().add(begin);
        return JsonResult.successful(pageResults);
    }

    /**
     * 获取导入银行流水模板
     * @param accountSetsId
     * @return
     * @throws UnsupportedEncodingException
     */
    @SaIgnore
    @GetMapping("/uaTemplate")
    public ResponseEntity<byte[]> uaTemplate(Long accountSetsId) throws UnsupportedEncodingException {
        ExcelWriter writer = ExcelUtil.getWriterWithSheet("日记账导入模板");
        Workbook workbook = writer.getWorkbook();
        Font font = StyleUtil.createFont(workbook, Font.COLOR_RED, (short) 11, null);
        CellStyle cellStyle = StyleUtil.cloneCellStyle(workbook, writer.getStyleSet().getHeadCellStyle());
        cellStyle.setFont(font);
        List<AccountingCategoryDto> categories = accountingCategoryService.listByAccountSetsId(accountSetsId);
        writer.writeHeadRow(getCategoryCols(categories));
        for (int i = 0; i < 7; i++) {
            writer.setStyle(cellStyle, i, 0);
        }
        writer.autoSizeColumnAll();

        // 画图的顶级管理器对象HSSFPatriarch, 一个sheet只能获取一个
        XSSFDrawing hssfPatriarch = (XSSFDrawing) writer.getSheet().createDrawingPatriarch();
        // 形状在sheet中的锚点位置
        XSSFClientAnchor anchor = new XSSFClientAnchor(0, 0, 0, 0, (short) 2, 13, (short) 9, 24);
        XSSFTextBox textbox = hssfPatriarch.createTextbox(anchor);
        textbox.setFillColor(161, 186, 102);
        textbox.setText("1、系统默认以年月为准顺序列示\n" +
                "2、日期格式为\"20xx/xx/xx”或\"20xx-xx-xx”，其他格式或空值将无法导入；日期应在账套启用期间之后，并且导入日记账的所属期间没有结账，否则将无法导入\n" +
                "3、系统默认以编码为准导入系统\n" +
                "4、日期、摘要、收支类别、收支类别编码、收支类别名称是必填项，其他选填\n" +
                "5、收支类型为固定值：'收' 或 '支'\n" +
                "6、收支类别参考表和辅助核算项目参考表是下载模版时系统实时更新的，如需修改，请先在系统完善对应的信息，再下载模版填写");

        writer.setSheet("收支类别参考表");
        List<CashierIoTypeDto> cashierIoTypes = cashierIoTypeService.list(accountSetsId, null);
        writer.addHeaderAlias("code", "编码");
        writer.addHeaderAlias("name", "名称");
        writer.addHeaderAlias("type", "收支类型");
        writer.setOnlyAlias(true);
        writer.write(cashierIoTypes);
        writer.autoSizeColumnAll();

        writer.setSheet("科目参考表");
        List<Subject> subjects = subjectService.selectData(accountSetsId);
        writer.clearHeaderAlias();
        writer.addHeaderAlias("code", "科目编码");
        writer.addHeaderAlias("fullName", "科目名称");
        writer.setOnlyAlias(true);
        writer.write(subjects);
        writer.autoSizeColumnAll();

        categories.forEach(c -> {
            if (!c.getCategoryType().equals(AccountingCategoryType.CASH_FLOW)) {
                List<? extends AcDetails> acDetails = SpringUtil.getBean(c.getCategoryType().dataService).loadData(c.getAccountSetsId(), c.getId());
                writer.setSheet("参考" + c.getName());
                writer.addHeaderAlias("code", "编码");
                writer.addHeaderAlias("name", "名称");
                writer.setOnlyAlias(true);
                writer.write(acDetails);
                writer.autoSizeColumnAll();
            }
        });

        ByteArrayOutputStream byteOutputStream = new ByteArrayOutputStream();
        writer.flush(byteOutputStream);
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.set(HttpHeaders.CONTENT_TYPE, ExcelUtil.XLSX_CONTENT_TYPE);
        httpHeaders.setContentDisposition(ContentDisposition.builder("attachment")
                .filename("日记账导入模板" + DateUtil.date().toString(PURE_DATETIME_PATTERN) + ".xlsx", StandardCharsets.UTF_8).build());
        return new ResponseEntity<>(byteOutputStream.toByteArray(),
                httpHeaders,
                HttpStatus.OK);
    }

    /**
     * 导入银行流水数据
     * @param req
     * @param accountSets
     * @return
     * @throws IOException
     */
    @SaIgnore
    @PostMapping("/uaImport")
    public JsonResult uaImport(@RequestBody ImportCashierJournalReq req, AccountSets accountSets) throws IOException {
        Long accountSetsId = req.getAccountSetsId();

        Long userId = null;
        if(ObjectUtils.isNotEmpty(accountSetsId)){
            GetUserInfoResp getUserInfoResp = userService.uaGetUserInfo(accountSetsId);
            userId = getUserInfoResp.getUserId();
        }
        // 辅助核算类别列表
        List<AccountingCategoryDto> categories = accountingCategoryService.listByAccountSetsId(accountSetsId);
        // 最后账期
        LocalDate lastCheckDate = checkoutService.lastCompletedCheckDate(accountSetsId);
        // 收支类别列表
        List<CashierIoTypeDto> ioTypeDtos = cashierIoTypeService.list(accountSetsId, null);
        // 配置了智能关键字匹配的收支类别列表
        List<CashierIoTypeDto> matchWordsIoTypes = ioTypeDtos.stream().filter(it -> CollUtil.isNotEmpty(it.getMatchWords())).toList();
        // 已经匹配到的收支编码
        Map<String, CashierIoType> ioTypeIdMap = new HashMap<>();
        // 科目字典
        Map<String, Long> subjectMap = subjectService.selectData(accountSetsId).stream().collect(Collectors.toMap(Subject::getCode, Subject::getId));

        List<String> errors = new ArrayList<>();
        for (int i = 0; i < req.getCashierJournalVo().size(); i++) {
            try {
                JournalImportVo vo = req.getCashierJournalVo().get(i);
                ValidatorUtils.validate(vo);
                Assert.isTrue(vo.getDebit() != null || vo.getCredit() != null, "第" + (i + 2) + "行，收入（借方）或 支出（贷方）必须有一方有金额~");

                if (lastCheckDate != null) {
                    Assert.isTrue(lastCheckDate.withDayOfMonth(lastCheckDate.lengthOfMonth()).isBefore(vo.getBillDate()), "第" + (i + 2) + "行，" + vo.getBillDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "已结账期间不允许记账，请检查数据~");
                }

                if (StrUtil.isNotEmpty(vo.getSubjectCode())) {
                    Assert.isTrue(subjectMap.containsKey(vo.getSubjectCode()), "第" + (i + 2) + "行，科目编码：" + vo.getSubjectCode() + "错误，请核对~");
                }

                if (vo.getIoTypeCode() == null) {
                    if (StrUtil.isNotEmpty(vo.getDigest())) {
                        // 关键字匹配
                        for (CashierIoTypeDto wordsIoType : matchWordsIoTypes) {
                            if (ReUtil.isMatch(CollUtil.join(wordsIoType.getMatchWords(), "|"), vo.getDigest())) {
                                // 匹配到关键字自动设置收支类别
                                vo.setIoTypeCode(wordsIoType.getCode());
                                vo.setType(wordsIoType.getType().name());
                                break;
                            }
                        }
                    }
                    // 匹配不到给个默认的
                    if (vo.getIoTypeCode() == null) {
                        vo.setIoTypeCode("1");
                    }
                }

                CashierJournal journal = new CashierJournal();
                journal.setSubjectId(subjectMap.get(vo.getSubjectCode()));

                CashierIoType ioType = ioTypeIdMap.get(vo.getIoTypeCode() + vo.getType());
                if (ioType == null) {
                    try {
                        CashierIoType.Type.valueOf(vo.getType());
                    } catch (Exception e) {
                        return JsonResult.failure("第" + (i + 2) + "行，收支类型值必须是'收'或'支'");
                    }
                    Optional<CashierIoTypeDto> dtoOptional = ioTypeDtos.stream()
                            .filter(it -> it.getCode().equals(vo.getIoTypeCode()) && it.getType().equals(CashierIoType.Type.valueOf(vo.getType())))
                            .findFirst();
                    Assert.isTrue(dtoOptional.isPresent(), "第" + (i + 2) + "行，类型：{}，编码：{}未在系统定义，请检查", vo.getType(), vo.getIoTypeCode());
                    ioTypeIdMap.put(vo.getIoTypeCode() + vo.getType(), cashierIoTypeMapper.toEntity(dtoOptional.get()));
                    ioType = ioTypeIdMap.get(vo.getIoTypeCode() + vo.getType());
                }

                //已经查询到的辅助明细
                Map<String, Object> exitAcd = new HashMap<>();

                for (AccountingCategoryDto c : categories) {
                    String code = vo.getSubjectCode();
                    if (StrUtil.isNotEmpty(code)) {
                        Object acd = exitAcd.get(c.getCategoryType().name() + ":" + code);
                        if (acd == null) {
                            acd = SpringUtil.getBean(c.getCategoryType().dataService).loadByCode(accountSetsId, c.getId(), code);
                            Assert.notNull(acd, "第" + (i + 2) + "行，" + c.getCategoryType().display + "辅助项,编码：" + code + "，系统不存在，请先增加辅助明细项目～");
                            exitAcd.put(c.getCategoryType().name() + ":" + code, acd);
                        }
                        if (journal.getAuxiliary() == null) {
                            journal.setAuxiliary(new ArrayList<>());
                        }
                        journal.getAuxiliary().add((AcDetails) acd);
                    }
                }

                journal.setCredit(vo.getCredit());
                journal.setDebit(vo.getDebit());
                journal.setType(req.getType());
                journal.setDigest(vo.getDigest());
                journal.setBillNo(vo.getBillNo());
                journal.setBillDate(vo.getBillDate());
                journal.setAccountSetsId(accountSetsId);
                journal.setAccountId(req.getAccountId());
                journal.setIoTypeId(ioType.getId());
                journal.setInit(false);
                journal.setCreatorId(userId);
                journal.setRemark(vo.getRemark());

                journal.setOppositeAccountName(vo.getOppositeAccountName());
                journal.setTransactionPostscript(vo.getTransactionPostscript());
                cashierJournalService.save(journal, accountSets.getEnableDate());
            } catch (Exception e) {
                errors.add(e.getMessage());
            }
        }
        return JsonResult.successful(errors);
    }

    /**
     * 获取导入表头
     *
     * @param categories
     * @return
     */
    private List<String> getCategoryCols(List<AccountingCategoryDto> categories) {
        List<String> categoryCols = new ArrayList<>();
        String[] cols = new String[]{"日期", "摘要", "收支类型", "收支类别编码", "收支类别名称", "收入（借方）", "支出（贷方）", "对方科目"};
        String[] cols2 = new String[]{"往来账号", "票据号", "备注"};
        categoryCols.addAll(Arrays.asList(cols));
        categories.forEach(c -> {
            if (!c.getCategoryType().equals(AccountingCategoryType.CASH_FLOW)) {
                categoryCols.add(c.getName() + "编码");
                categoryCols.add(c.getName() + "名称");
            }
        });
        categoryCols.addAll(Arrays.asList(cols2));
        return categoryCols;
    }


}