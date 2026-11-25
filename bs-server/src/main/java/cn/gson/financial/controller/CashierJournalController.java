package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.gson.financial.kernel.annotation.DueCheck;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.AccountingCategoryDto;
import cn.gson.financial.kernel.model.dto.CashierIoTypeDto;
import cn.gson.financial.kernel.model.dto.CashierJournalDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.enums.AttachmentType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.CashierIoTypeMapper;
import cn.gson.financial.kernel.model.vo.JournalImportVo;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.*;
import cn.gson.financial.kernel.tool.ValidatorUtils;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import cn.hutool.poi.excel.style.StyleUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFTextBox;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static cn.hutool.core.date.DatePattern.PURE_DATETIME_PATTERN;


/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (日记账)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-10-27 08:26:39</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/cashier/journal")
public class CashierJournalController {
    private final CashierIoTypeMapper cashierIoTypeMapper;

    private final CashierJournalService cashierJournalService;
    private final BalanceCalcService balanceCalcService;
    private final CashierAccountService cashierAccountService;
    private final CashierIoTypeService cashierIoTypeService;
    private final CheckoutService checkoutService;
    private final SubjectService subjectService;
    private final AttachmentService attachmentService;
    private final SettlementTypeService settlementTypeService;
    private final AccountingCategoryService accountingCategoryService;

    @PostMapping("list/{accountId:\\d+}")
    @SaCheckPermission("cash-cashjournal-canview")
    public JsonResult list(Page page, @PathVariable Long accountId, @SessionUser UserVo userVo,
                           @RequestBody CashierJournalService.CashierJournalQuery query,
                           @SessionAccountSet AccountSets accountSets) {
        Long accountSetsId = accountSets.getId();
        CashierJournalDto begin = cashierJournalService.beginRow(accountSetsId, accountId, query.start, userVo.getAccountSets().getEnableDate());
        PageResults<CashierJournalDto> pageResults = cashierJournalService.list(accountSetsId, accountId, page, query);
        pageResults.getResults().add(begin);
        return JsonResult.successful(pageResults);
    }

    @PostMapping("multiple/list")
    @SaCheckPermission("cash-cashjournal-canview")
    public JsonResult multipleList(Page page, @SessionUser UserVo userVo,
                                   @RequestBody CashierJournalService.CashierJournalMultipleQuery query,
                                   @SessionAccountSet AccountSets accountSets) {
        PageResults<CashierJournalDto> pageResults = cashierJournalService.multipleList(accountSets.getId(), page, query);
        return JsonResult.successful(pageResults);
    }

    /**
     * 账户合计金额
     *
     * @param accountId
     * @param userVo
     * @param query
     * @param accountSets
     * @return
     */
    @PostMapping("total/{accountId:\\d+}")
    @SaCheckPermission("cash-cashjournal-canview")
    public JsonResult total(@PathVariable Long accountId, @SessionUser UserVo userVo,
                            @RequestBody CashierJournalService.CashierJournalQuery query,
                            @SessionAccountSet AccountSets accountSets) {
        Long accountSetsId = accountSets.getId();
        CashierJournalDto begin = cashierJournalService.beginRow(accountSetsId, accountId, query.start, userVo.getAccountSets().getEnableDate());

        CashierJournalDto total = cashierJournalService.total(accountSetsId, accountId, query);
        total.setBalance(NumberUtil.add(begin.getBalance(), total.getBalance()));

        return JsonResult.successful(total);
    }

    @PostMapping
    @SaCheckPermission("cash-cashjournal-canedit")
    public JsonResult create(@RequestBody CashierJournal cashierJournal,
                             @SessionAccountSet AccountSets accountSets,
                             @SessionUser UserVo userVo) {
        cashierJournal.setAccountSetsId(accountSets.getId());
        cashierJournal.setCreatorId(userVo.getId());
        if (!cashierJournal.getInit()) {
            Assert.notNull(cashierJournal.getBillDate(), "记账日期不能为空");
        } else {
            cashierJournal.setBillDate(accountSets.getEnableDate());
        }
        Assert.isTrue(accountSets.getEnableDate().isEqual(cashierJournal.getBillDate()) || accountSets.getEnableDate().isBefore(cashierJournal.getBillDate()), "记账日期不能小于账套启用日期～");
        CashierJournal journal = cashierJournalService.save(cashierJournal, accountSets.getEnableDate());
        attachmentService.relateSource(accountSets.getId(), journal.getBillList(), journal.getId());
        return JsonResult.successful();
    }

    @PostMapping("generate/voucher")
    @SaCheckPermission("cash-cashjournal-cancreatevoucher")
    public JsonResult generateVoucher(@RequestBody List<VoucherDto> voucherDtos, @SessionUser UserVo userDto, @SessionAccountSet AccountSets accountSets) {
        voucherDtos.parallelStream().forEach(voucherDto -> {
            voucherDto.setCreateMember(userDto.getId());
            voucherDto.setAccountSetsId(accountSets.getId());
            Assert.notEmpty(voucherDto.getDetails(), voucherDto.getWord() + "-" + voucherDto.getCode() + "，缺少凭证明细，请检查~");
            cashierJournalService.generateVoucher(voucherDto, accountSets);
        });
        Optional<LocalDate> min = voucherDtos.stream().map(VoucherDto::getVoucherDate).min(LocalDate::compareTo);
        min.ifPresent(d -> balanceCalcService.calc(accountSets.getId(), YearMonth.of(d.getYear(), d.getMonthValue())));
        return JsonResult.successful();
    }

    @GetMapping("{cashierJournalId:\\d+}")
    @SaCheckPermission("cash-cashjournal-canview")
    public JsonResult loadItem(@PathVariable Long cashierJournalId, @SessionAccountSet AccountSets accountSets) {
        CashierJournal cashierJournal = cashierJournalService.loadItem(new PrimaryKey(cashierJournalId, accountSets.getId()));
        CashierAccount account = cashierAccountService.loadItem(new PrimaryKey(cashierJournal.getAccountId(), accountSets.getId()));
        return JsonResult.successful()
                .data("journal", cashierJournal)
                .data("account", account);
    }

    @PutMapping
    @SaCheckPermission("cash-cashjournal-canedit")
    public JsonResult update(@RequestBody CashierJournal cashierJournal,
                             @SessionAccountSet AccountSets accountSets) {
        Long accountSetsId = accountSets.getId();
        cashierJournal.setAccountSetsId(accountSetsId);
        Assert.isTrue(accountSets.getEnableDate().isEqual(cashierJournal.getBillDate()) || accountSets.getEnableDate().isBefore(cashierJournal.getBillDate()), "记账日期不能小于账套启用日期～");
        CashierJournal journal = cashierJournalService.save(cashierJournal, accountSets.getEnableDate());
        attachmentService.relateSource(accountSetsId, journal.getBillList(), journal.getId());
        return JsonResult.successful(journal);
    }

    @DeleteMapping("{cashierJournalId:\\d+}")
    @SaCheckPermission("cash-cashjournal-candelete")
    public JsonResult delete(@PathVariable Long cashierJournalId, @SessionAccountSet AccountSets accountSets) {
        Long accountSetsId = accountSets.getId();
        cashierJournalService.delete(cashierJournalId, accountSetsId, accountSets.getEnableDate());
        //解除附件关联
        attachmentService.relieveSource(accountSetsId, AttachmentType.journal, CollUtil.newArrayList(cashierJournalId));
        return JsonResult.successful();
    }

    @DeleteMapping("batch")
    @SaCheckPermission("cash-cashjournal-candelete")
    public JsonResult batchDelete(Long[] id, @SessionAccountSet AccountSets accountSets) {
        Long accountSetsId = accountSets.getId();
        for (Long integer : id) {
            cashierJournalService.delete(integer, accountSetsId, accountSets.getEnableDate());
        }
        attachmentService.relieveSource(accountSetsId, AttachmentType.journal, CollUtil.newArrayList(id));
        return JsonResult.successful();
    }

    @PutMapping("batch")
    @SaCheckPermission("cash-cashjournal-canedit")
    public JsonResult batchUpdate(String type, @RequestParam(name = "id") Long[] ids,
                                  @RequestBody CashierJournal cashierJournal,
                                  @SessionAccountSet AccountSets accountSets) {
        Long accountSetsId = accountSets.getId();
        if (cashierJournal.getIoTypeId() != null) {
            List<CashierJournal> needUpdateBalance = cashierJournalService.batchUpdateIoType(accountSetsId, ids, cashierJournal.getIoTypeId());
            if (CollUtil.isNotEmpty(needUpdateBalance)) {
                for (CashierJournal journal : needUpdateBalance) {
                    //更新余额信息
                    cashierJournalService.updateBalance(accountSetsId, journal.getAccountId(), journal.getBillDate(), accountSets.getEnableDate());
                }
            }
        } else {
            for (Long id : ids) {
                CashierJournal journal = BeanUtil.toBean(cashierJournal, CashierJournal.class);
                journal.setId(id);
                journal.setAccountSetsId(accountSetsId);
                cashierJournalService.batchUpdate(type, journal);
            }
        }
        return JsonResult.successful();
    }

    @PostMapping("download/{accountId:\\d+}")
    @SaCheckPermission("cash-cashjournal-canexport")
    public ResponseEntity<byte[]> download(Page page,
                                           @PathVariable Long accountId,
                                           @SessionUser UserVo userVo,
                                           @RequestBody CashierJournalService.CashierJournalQuery query) throws UnsupportedEncodingException {
        page.setPageSize(100000);
        PageResults<CashierJournalDto> pageResults = cashierJournalService.list(userVo.getAccountSets().getId(), accountId, page, query);
        if (pageResults.getTotal() > 0) {
            CashierJournalDto[] dtos = pageResults.getResults().toArray(new CashierJournalDto[]{});
            CashierJournalDto dto = dtos[0];
            ExcelWriter writer = ExcelUtil.getWriter(true);
            writer.getSheet().setDefaultColumnWidth(16);
            writer.merge(8, "现金日记账", false);
            writer.setRowHeight(0, 60);

            //表头样式
            Font font = writer.createFont();
            CellStyle cellStyle = writer.createCellStyle();
            cellStyle.setAlignment(HorizontalAlignment.CENTER);
            cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);
            font.setBold(true);
            font.setFontHeightInPoints((short) 20);
            cellStyle.setFont(font);
            writer.setStyle(cellStyle, 0, 0);

            writer.setCurrentRow(1);
            writer.merge(3, "账户：" + dto.getAccountCode() + dto.getAccountName(), false);
            writer.merge(1, 1, 4, 6, query.start.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")) + "至" + query.end.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日")), false);
            writer.merge(1, 1, 7, 8, "单位：元", false);

            writer.addHeaderAlias("serialNumber", "单据编号");
            writer.addHeaderAlias("billDate", "日期");
            writer.addHeaderAlias("digest", "摘要");
            writer.addHeaderAlias("ioTypeValue", "收支类别");
            writer.addHeaderAlias("accountingCategoryDetailsValue", "往来单位");
            writer.addHeaderAlias("debit", "收入(借方)");
            writer.addHeaderAlias("credit", "支出(贷方)");
            writer.addHeaderAlias("balance", "余额");
            writer.addHeaderAlias("voucherCode", "关联凭证");
            writer.writeHeadRow(CollUtil.newArrayList("单据编号", "日期", "摘要", "收支类别", "往来单位", "收入(借方)", "支出(贷方)", "余额", "关联凭证"));

            double debit = 0d;
            double credit = 0d;
            double total = 0d;
            for (int i = 0; i < dtos.length; i++) {
                CashierJournalDto dt = dtos[i];
                writer.writeRow(dt, false);
                Cell cell = writer.getCell(1, writer.getCurrentRow() - 1);
                DataFormat dataFormat = writer.getWorkbook().createDataFormat();
                CellStyle style = cell.getCellStyle();
                style.setDataFormat(dataFormat.getFormat("yyyy-MM-dd"));

                debit += Optional.ofNullable(dt.getDebit()).orElse(BigDecimal.ZERO).doubleValue();
                credit += Optional.ofNullable(dt.getCredit()).orElse(BigDecimal.ZERO).doubleValue();

                if (i == dtos.length - 1) {
                    total = Optional.ofNullable(dt.getBalance()).orElse(BigDecimal.ZERO).doubleValue();
                }
            }
            writer.writeRow(Dict.create().set("digest", "合计").set("debit", debit).set("credit", credit).set("balance", total), false);
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            writer.flush(outputStream);
            writer.close();
            HttpHeaders httpHeaders = new HttpHeaders();
            String fileName = "日记账(" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ").xlsx";
            httpHeaders.setContentType(MediaType.valueOf(ExcelUtil.XLSX_CONTENT_TYPE));
            httpHeaders.set(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8));
            return new ResponseEntity<>(outputStream.toByteArray(),
                    httpHeaders,
                    HttpStatus.OK);
        }
        return null;
    }

    /**
     * 下载模板
     */
    @GetMapping("/template")
    @SaCheckPermission("cash-cashjournal-canimport")
    public ResponseEntity<byte[]> template(@SessionAccountSet AccountSets accountSets) throws UnsupportedEncodingException {
        Long accountSetsId = accountSets.getId();
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

    @PostMapping("/import")
    @DueCheck
    @SaCheckPermission("cash-cashjournal-canimport")
    public JsonResult importVoucher(@RequestParam Long accountId, @RequestParam("iotype") CashierAccount.CashierAccountType type,
                                    @RequestBody List<JournalImportVo> cashierJournalVo,
                                    @SessionUser UserVo userVo,
                                    @SessionAccountSet AccountSets accountSets) throws IOException {
        Long accountSetsId = accountSets.getId();
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
        for (int i = 0; i < cashierJournalVo.size(); i++) {
            try {
                JournalImportVo vo = cashierJournalVo.get(i);
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
                journal.setType(type);
                journal.setDigest(vo.getDigest());
                journal.setBillNo(vo.getBillNo());
                journal.setBillDate(vo.getBillDate());
                journal.setAccountSetsId(accountSetsId);
                journal.setAccountId(accountId);
                journal.setIoTypeId(ioType.getId());
                journal.setInit(false);
                journal.setCreatorId(userVo.getId());
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
     * @param accountSets
     * @return
     */
    @GetMapping("settlementType")
    public JsonResult settlementType(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(settlementTypeService.list(accountSets.getId()));
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

