package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.annotation.DueCheck;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.*;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AttachmentType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.model.vo.VoucherImportVo;
import cn.gson.financial.kernel.service.*;
import cn.gson.financial.rpa.ExcelReaderUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import cn.hutool.poi.excel.ExcelWriter;
import com.alibaba.fastjson.JSONObject;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import com.querydsl.core.types.dsl.BooleanExpression;

import static cn.gson.financial.kernel.common.Constants.S_USER_KEY;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequestMapping("/voucher")
@RequiredArgsConstructor
public class VoucherController {

    String[] defaultSummary = new String[]{"提现", "利息收入", "利息收入", "支付银行手续费", "报销销售人员的业务招待费", "购入固定资产", "支付货款"};

    private final UserService userService;

    private final VoucherService voucherService;

    private final BalanceCalcService balanceCalcService;

    private final SubjectService subjectService;

    private final AccountingCategoryService accountingCategoryService;

    private final CurrencyService currencyService;

    private final VoucherWordService voucherWordService;

    private final CheckoutService checkoutService;

    private final VoucherChangeLogService changeLogService;


    private final VoucherTemplateService voucherTemplateService;

    private final AttachmentService attachmentService;
    private final ExcelReaderUtil excelReaderUtil;

    // --- 电子凭证册所需 ---
    private final cn.gson.financial.kernel.model.repo.VoucherRepository voucherRepository;
    private final cn.gson.financial.kernel.model.repo.VoucherDetailsRepository voucherDetailsRepository;
    private final cn.gson.financial.kernel.model.repo.InvoiceRepository invoiceRepository;
    private final cn.gson.financial.kernel.model.repo.InvoiceItemsRepository invoiceItemsRepository;
    private final cn.gson.financial.kernel.model.repo.AssetsChangeRepository assetsChangeRepository;
    private final cn.gson.financial.kernel.model.repo.AssetsDepreciationRepository assetsDepreciationRepository;
    private final cn.gson.financial.kernel.model.repo.AssetsRepository assetsRepository;
    private final cn.gson.financial.kernel.model.repo.CashierJournalRepository cashierJournalRepository;
    private final cn.gson.financial.kernel.model.repo.BankStatementDetailRepository bankStatementDetailRepository;
    private final cn.gson.financial.kernel.model.repo.SubjectRepository subjectRepository;
    private final cn.gson.financial.kernel.model.repo.AccountSetsRepository accountSetsRepository;

    @PostMapping("list")
    @SaCheckPermission("voucher-vouchermanage-canview")
    public JsonResult list(@RequestBody VoucherService.VoucherQuery query, Page page, @SessionAccountSet AccountSets accountSets) {
        query.setAccountSetsId(accountSets.getId());
        return JsonResult.successful(this.voucherService.page(query, page));
    }

    /**
     * 最后一次凭证时间
     *
     * @param accountSetsId
     * @return
     */
    @GetMapping("lastDate")
    public JsonResult lastDate(@SessionAccountSet AccountSets accountSets) {
        LocalDate localDate = this.voucherService.lastDate(accountSets.getId()).orElse(accountSets.getCurrentAccountDate());
        if (localDate.isBefore(accountSets.getCurrentAccountDate())) {
            localDate = accountSets.getCurrentAccountDate();
        }
        return JsonResult.successful(localDate);
    }

    @PostMapping("download")
    @SaCheckPermission("voucher-vouchermanage-canexport")
    public ResponseEntity<byte[]> download(@RequestBody VoucherService.VoucherQuery query, Page page, @SessionAccountSet AccountSets accountSets) throws UnsupportedEncodingException {
        Long accountSetsId = accountSets.getId();
        query.setAccountSetsId(accountSetsId);
        List<AccountingCategoryDto> categories = accountingCategoryService.listByAccountSetsId(accountSetsId);
        List<Map<String, Object>> voucherList = new ArrayList<>();
        voucherService.list(query, page).forEach(voucher -> {
            for (VoucherDetailsDto d : voucher.getDetails()) {
                Map<String, Object> mapData;
                VoucherImportVo vo = new VoucherImportVo();
                String voucherDate = voucher.getVoucherDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                vo.setDate(voucherDate);
                vo.setVoucherNumber(voucher.getWord() + "-" + voucher.getCode());
                vo.setDigest(d.getSummary());
                //待处理
                vo.setSubjectCode(d.getSubjectCode());
                vo.setSubjectTitle(d.getSubjectName().replaceFirst(d.getSubjectCode() + " ", "").replace("_", "-"));
                vo.setForeignCurrencyCode(d.getCurrencyName());
                vo.setDebitForeignCurrency(null);
                vo.setDebitCurrency(d.getDebitAmount());
                vo.setDebitNumber(d.getDebitNum());
                vo.setCreditForeignCurrency(null);
                vo.setCreditCurrency(d.getCreditAmount());
                vo.setCreditNumber(d.getCreditNum());
                vo.setMakingPeople(voucher.getCreateMemberName());
                vo.setAuditor(voucher.getAuditMemberName());
                vo.setAttachmentNum(voucher.getReceiptNum());
                vo.setRemark(voucher.getRemark());

                mapData = BeanUtil.beanToMap(vo);
                mapData.put("date", voucherDate);
                for (AccountingCategoryDto c : categories) {
                    mapData.put("code" + c.getId(), "");
                    mapData.put("name" + c.getId(), "");
                }

                if (CollUtil.isNotEmpty(d.getAuxiliary())) {
                    for (AcDetails acd : d.getAuxiliary()) {
                        mapData.put("code" + acd.getCategoryId(), acd.getCode());
                        mapData.put("name" + acd.getCategoryId(), acd.getName());
                    }
                }
                voucherList.add(mapData);
            }
        });

        ByteArrayOutputStream os = new ByteArrayOutputStream();
        ExcelWriter writer = ExcelUtil.getWriter(true);
        writer.addHeaderAlias("date", "日期");
        writer.addHeaderAlias("voucherNumber", "凭证号");
        writer.addHeaderAlias("digest", "摘要");
        writer.addHeaderAlias("subjectCode", "科目编码");
        writer.addHeaderAlias("subjectTitle", "科目名称");
        writer.addHeaderAlias("foreignCurrencyCode", "外币代码");
        writer.addHeaderAlias("debitNumber", "借方数量");
        writer.addHeaderAlias("debitForeignCurrency", "借方外币");
        writer.addHeaderAlias("debitCurrency", "借方本币");
        writer.addHeaderAlias("creditNumber", "贷方数量");
        writer.addHeaderAlias("creditForeignCurrency", "贷方外币");
        writer.addHeaderAlias("creditCurrency", "贷方本币");
        for (AccountingCategoryDto c : categories) {
            writer.addHeaderAlias("code" + c.getId(), c.getName() + "编码");
            writer.addHeaderAlias("name" + c.getId(), c.getName() + "名称");
        }
        writer.addHeaderAlias("makingPeople", "制单人");
        writer.addHeaderAlias("auditor", "审核人");
        writer.addHeaderAlias("attachmentNum", "附件数");
        writer.addHeaderAlias("remark", "备注");
        writer.setOnlyAlias(true);
        writer.write(voucherList);
        writer.flush(os);
        writer.close();

        HttpHeaders httpHeaders = new HttpHeaders();
        String fileName = "记账凭证" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx";
        httpHeaders.setContentType(MediaType.valueOf(ExcelUtil.XLSX_CONTENT_TYPE));
        httpHeaders.set(HttpHeaders.CONTENT_DISPOSITION, "inline;filename=" + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        return new ResponseEntity<>(os.toByteArray(),
                httpHeaders,
                HttpStatus.OK);
    }

    @GetMapping("template")
    @SaCheckPermission("voucher-vouchermanage-canexport")
    public void downTemplate(HttpServletResponse response, @SessionAccountSet AccountSets accountSets) throws IOException {
        Long accountSetsId = accountSets.getId();
        List<AccountingCategoryDto> categories = accountingCategoryService.listByAccountSetsId(accountSetsId);
        String collect = categories.stream().map(a -> a.getName() + "编码," + a.getName() + "名称").collect(Collectors.joining(","));
        List<String> columns = StrUtil.split("日期,凭证号,摘要,科目编码,科目名称,外币代码,借方数量,借方外币,借方本币,贷方数量,贷方外币,贷方本币," + collect, ",");
        ExcelWriter writer = ExcelUtil.getWriter(true);
        writer.renameSheet("待导入凭证数据");
        writer.writeHeadRow(columns);

        List<Subject> subjectVos = subjectService.selectLeafData(accountSetsId);
        writer.setSheet("参考科目");
        writer.addHeaderAlias("code", "编码");
        writer.addHeaderAlias("fullName", "名称");
        writer.setOnlyAlias(true);
        writer.write(subjectVos);

        List<CurrencyDto> currencies = currencyService.list(accountSetsId);
        writer.setSheet("参考币别");
        writer.addHeaderAlias("code", "编码");
        writer.addHeaderAlias("name", "名称");
        writer.setOnlyAlias(true);
        writer.write(currencies);

        List<VoucherWord> voucherWords = voucherWordService.list(accountSetsId);
        writer.setSheet("参考凭证字");
        writer.addHeaderAlias("word", "名称");
        writer.setOnlyAlias(true);
        writer.write(voucherWords);

        categories.forEach(c -> {
            List<? extends AcDetails> acDetails = SpringUtil.getBean(c.getCategoryType().dataService).loadData(c.getAccountSetsId(), c.getId());
            writer.setSheet("参考" + c.getName());
            writer.addHeaderAlias("code", "编码");
            writer.addHeaderAlias("name", "名称");
            writer.setOnlyAlias(true);
            writer.write(acDetails);
        });

        String fileName = "凭证导入模板" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx";
        response.setContentType(ExcelUtil.XLSX_CONTENT_TYPE);
        response.setHeader("Content-disposition", "attachment; filename=" + new String(fileName.getBytes(StandardCharsets.UTF_8), "ISO8859-1"));
        writer.flush(response.getOutputStream());
        writer.close();
    }

    @GetMapping("{voucherId:\\d+}")
    @SaCheckPermission("voucher-vouchermanage-canview")
    public JsonResult loadVoucher(@SessionAccountSet AccountSets accountSets, @PathVariable Long voucherId) {
        return JsonResult.successful(this.voucherService.getOne(accountSets.getId(), voucherId));
    }

    @GetMapping("batch/load")
    @SaCheckPermission("voucher-vouchermanage-canview")
    public JsonResult batchLoadVoucher(@SessionAccountSet AccountSets accountSets, VoucherService.VoucherQuery query) {
        Long accountSetsId = accountSets.getId();
        query.setAccountSetsId(accountSetsId);
        return JsonResult.successful(this.voucherService.batchLoad(accountSetsId, query));
    }

    @GetMapping("code")
    public JsonResult loadCode(String word, LocalDate currentAccountDate, @SessionAccountSet AccountSets accountSets) {
        int code = this.voucherService.loadCode(accountSets.getId(), word, currentAccountDate);
        return JsonResult.successful(code);
    }

    /**
     * 断号整理
     *
     * @param year  期间年
     * @param month 期间月
     * @return
     */
    @GetMapping("finishingOffNo")
    @SaCheckPermission("voucher-vouchermanage-canclean")
    public JsonResult finishingOffNo(@RequestParam Integer year,
                                     @RequestParam Integer month,
                                     @RequestParam(defaultValue = "false") boolean isCheck,
                                     @SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(this.voucherService.finishingOffNo(accountSets.getId(), year, month, isCheck));
    }

    /**
     * 批量审核
     *
     * @param checked 凭证ID
     * @return
     */
    @PostMapping("audit")
    @DueCheck
    @SaCheckPermission("voucher-vouchermanage-audit")
    public JsonResult audit(Long[] checked, @SessionAccountSet AccountSets accountSets, @SessionUser UserVo userVo) {
        List<Long> voucherIds = Arrays.asList(checked);
        if (CollUtil.isNotEmpty(voucherIds)) {
            this.voucherService.audit(accountSets.getId(), voucherIds, userVo);
        }
        return JsonResult.successful();
    }

    /**
     * 批量全部审核
     *
     * @return
     */
    @GetMapping("batch/audit")
    @DueCheck
    @SaCheckPermission("voucher-vouchermanage-audit")
    public JsonResult batchAudit(@RequestParam Integer year, @RequestParam Integer month, @SessionAccountSet AccountSets accountSets, @SessionUser UserVo userVo) {
        this.voucherService.batchAudit(accountSets.getId(), userVo, year, month);
        return JsonResult.successful();
    }

    /**
     * 批量反审核
     *
     * @param checked 凭证ID
     * @return
     */
    @PostMapping("cancelAudit")
    @DueCheck
    @SaCheckPermission("voucher-vouchermanage-audit")
    public JsonResult cancelAudit(Long[] checked, @RequestParam Integer year, @RequestParam Integer month, @SessionAccountSet AccountSets accountSets) {
        this.voucherService.cancelAudit(accountSets.getId(), checked, year, month);
        return JsonResult.successful();
    }

    /**
     * 上一条
     *
     * @param code        凭证号
     * @param voucherDate 凭证日期
     * @return
     */
    @GetMapping("beforeId")
    public JsonResult beforeId(Integer code, LocalDate voucherDate, String word, @SessionAccountSet AccountSets accountSets) {
        Long id = this.voucherService.getBeforeId(accountSets.getId(), code, voucherDate, word);
        return JsonResult.successful(id);
    }

    /**
     * 下一条
     *
     * @param code        凭证号
     * @param voucherDate 凭证日期
     * @return
     */
    @GetMapping("nextId")
    public JsonResult nextId(Integer code, LocalDate voucherDate, String word, @SessionAccountSet AccountSets accountSets) {
        Long id = this.voucherService.getNextId(accountSets.getId(), code, voucherDate, word);
        return JsonResult.successful(id);
    }

    @PostMapping
    @DueCheck
    @SaCheckPermission("voucher-vouchermanage-canedit")
    public JsonResult save(@RequestBody VoucherDto entity, @SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets) {
        Assert.isNull(entity.getId(), "Id不为空，创建请求被禁止~");
        entity.setAccountSetsId(accountSets.getId());
        entity.setCreateMember(userVo.getId());
        // 更新最后修改人信息
        entity.setLastUpdateMember(userVo.getId());
        entity.setLastUpdateDate(LocalDateTime.now());
        entity = voucherService.save(entity, accountSets, false);
        //计算余额表
        balanceCalcService.calc(accountSets.getId(), YearMonth.from(entity.getVoucherDate()));
        //处理附件关联
        attachmentService.relateSource(accountSets.getId(), entity.getBillList(), entity.getId());
        return JsonResult.successful(entity);
    }

    /**
     * 保存凭证模板
     *
     * @param voucherTemplateDto
     * @param accountSetsId
     * @return
     */
    @PostMapping("tpl")
    @DueCheck
    public JsonResult saveTpl(@RequestBody VoucherTemplateDto voucherTemplateDto, @SessionAccountSet AccountSets accountSets) {
        voucherTemplateDto.setAccountSetsId(accountSets.getId());
        voucherTemplateService.save(voucherTemplateDto);
        return JsonResult.successful();
    }

    /**
     * 获取凭证模板
     *
     * @param accountSetsId
     * @return
     */
    @GetMapping("tpl")
    @DueCheck
    public JsonResult tplList(@SessionAccountSet AccountSets accountSets) {
        return JsonResult.successful(voucherTemplateService.list(accountSets.getId()));
    }

    /**
     * 凭证变更记录
     *
     * @param accountSetsId
     * @return
     */
    @GetMapping("changelog/{voucherId}")
    @DueCheck
    public JsonResult changeLog(@SessionAccountSet AccountSets accountSets, @PathVariable Long voucherId) {
        return JsonResult.successful(changeLogService.changeLog(accountSets.getId(), voucherId));
    }

    /**
     * 删除模板
     *
     * @param accountSetsId
     * @param tplId
     * @return
     */
    @DeleteMapping("tpl/{tplId:\\d+}")
    @DueCheck
    public JsonResult delTpl(@SessionAccountSet AccountSets accountSets, @PathVariable Long tplId) {
        voucherTemplateService.delete(new PrimaryKey(tplId, accountSets.getId()));
        return JsonResult.successful();
    }

    @PutMapping
    @DueCheck
    @SaCheckPermission("voucher-vouchermanage-canedit")
    public JsonResult update(@RequestBody VoucherDto entity, @SessionAccountSet AccountSets accountSets, @SessionUser UserVo userVo) {
        Assert.notNull(entity.getId(), "Id为空，更新请求被禁止~");
        entity.setAccountSetsId(accountSets.getId());
        // 更新最后修改人信息
        entity.setLastUpdateMember(userVo.getId());
        entity.setLastUpdateDate(LocalDateTime.now());
        VoucherDto voucherDto = voucherService.getOne(accountSets.getId(), entity.getId());
        Optional<Voucher> update = voucherService.update(entity, accountSets);

        checkoutService.reCreateCheckout(accountSets);

        //判断是时间变更
        LocalDate dateOrg = voucherDto.getVoucherDate().withDayOfMonth(1);
        LocalDate dateNew = entity.getVoucherDate().withDayOfMonth(1);
        YearMonth period = YearMonth.from(entity.getVoucherDate());
        if (!dateOrg.isEqual(dateNew) && dateOrg.isBefore(dateNew)) {
            period = YearMonth.from(dateOrg);
        }
        balanceCalcService.calc(accountSets.getId(), period);
        //处理附件关联
        attachmentService.relateSource(accountSets.getId(), update.get().getBillList(), entity.getId());
        return JsonResult.successful(update.get());
    }

    @PostMapping("delete")
    @DueCheck
    @SaCheckPermission("voucher-vouchermanage-candelete")
    public JsonResult delete(@RequestBody List<Voucher> vouchers, @SessionAccountSet AccountSets accountSets, @SessionUser UserVo userVo) {
        List<Long> voucherIds = vouchers.stream().map(Voucher::getId).toList();

        List<LocalDate> delVoucherDates = voucherService.batchDelete(accountSets, voucherIds);
        StpUtil.getSession().set(S_USER_KEY, this.userService.getUserVo(userVo.getId()));
        //更新科目余额
        Optional<LocalDate> min = delVoucherDates.stream().min(Comparator.comparing(d -> d));
        min.ifPresent(d -> balanceCalcService.calc(accountSets.getId(), YearMonth.from(d)));
        //解除附件关联
        attachmentService.relieveSource(accountSets.getId(), AttachmentType.voucher, voucherIds);
        return JsonResult.successful();
    }

    @PostMapping("/testDelete")
    @SaIgnore
    public JsonResult test() {
        return JsonResult.successful(voucherService.test());
    }
    @PostMapping("deleteAll")
    public JsonResult deleteALl(@SessionUser UserVo userVo){
        List<AccountSets> accountSetsList = userVo.getAccountSetsList();
        for(AccountSets accountSets : accountSetsList){
            VoucherService.VoucherQuery query = new VoucherService.VoucherQuery();
            query.setAccountSetsId(accountSets.getId());
            List<VoucherDto> voucherList = voucherService.list(query);
            List<Long> voucherIds = voucherList.stream().map(VoucherDto::getId).toList();
            List<LocalDate> delVoucherDates = voucherService.batchDelete(accountSets, voucherIds);
            StpUtil.getSession().set(S_USER_KEY, this.userService.getUserVo(userVo.getId()));
            //更新科目余额
            Optional<LocalDate> min = delVoucherDates.stream().min(Comparator.comparing(d -> d));
            min.ifPresent(d -> balanceCalcService.calc(accountSets.getId(), YearMonth.from(d)));
            //解除附件关联
            attachmentService.relieveSource(accountSets.getId(), AttachmentType.voucher, voucherIds);
        }
        return JsonResult.successful();
    }


    @GetMapping("summary")
    public JsonResult summary(@SessionAccountSet AccountSets accountSets) {
        Long accountSetsId = accountSets.getId();
        List<String> summary = this.voucherService.getTopSummary(accountSetsId);
        summary = summary.stream().map(String::trim).collect(Collectors.toList());
        for (String s : defaultSummary) {
            if (!summary.contains(s)) {
                summary.add(s);
            }
        }
        return JsonResult.successful(summary);
    }


    /**
     * 现金流标记
     *
     * @param accountSetsId
     * @return
     */
    @GetMapping("cash/tag")
    public JsonResult cashFlowTag(@SessionAccountSet AccountSets accountSets) {
        voucherService.cashFlowTag(accountSets.getId());
        return JsonResult.successful();
    }

    @PostMapping("/import")
    @DueCheck
    @SaCheckPermission("voucher-vouchermanage-canimport")
    public JsonResult importVoucher(@RequestParam("file") MultipartFile multipartFile, @SessionUser UserVo userVo, @SessionAccountSet AccountSets accountSets) {
        try {
            Long accountSetsId = accountSets.getId();
            // 最后结账日期
            LocalDate lastChecked = checkoutService.last(accountSets);
            CurrencyDto localCurrency = currencyService.fetchLocal(accountSetsId);
            List<AccountingCategoryDto> categories = accountingCategoryService.listByAccountSetsId(accountSetsId);
            //待插入的辅助项
            List<Object> newAcDetails = new ArrayList<>();
            ExcelReader reader = ExcelUtil.getReader(multipartFile.getInputStream());
            reader.addHeaderAlias("日期", "date");
            reader.addHeaderAlias("凭证号", "voucherNumber");
            reader.addHeaderAlias("摘要", "digest");
            reader.addHeaderAlias("科目编码", "subjectCode");
            reader.addHeaderAlias("科目名称", "subjectTitle");
            reader.addHeaderAlias("外币代码", "foreignCurrencyCode");
            reader.addHeaderAlias("借方数量", "debitNumber");
            reader.addHeaderAlias("借方外币", "debitForeignCurrency");
            reader.addHeaderAlias("借方本币", "debitCurrency");
            reader.addHeaderAlias("贷方数量", "creditNumber");
            reader.addHeaderAlias("贷方外币", "creditForeignCurrency");
            reader.addHeaderAlias("贷方本币", "creditCurrency");
            for (AccountingCategoryDto c : categories) {
                reader.addHeaderAlias(c.getName() + "编码", "code" + c.getId());
                reader.addHeaderAlias(c.getName() + "名称", "name" + c.getId());
            }

            List<Map<String, Object>> maps = reader.readAll();
            reader.clearHeaderAlias();
            reader.addHeaderAlias("编码", "code");
            reader.addHeaderAlias("名称", "name");
            //导入的参考辅助数据
            Map<AccountingCategoryDto, Map<String, AcDetails>> acMap = new HashMap<>();
            for (AccountingCategoryDto c : categories) {
                reader.setSheet("参考" + c.getName());
                List<AcDetails> acDetails = reader.readAll(AcDetails.class);
                try {
                    Map<String, AcDetails> detailsMap = acDetails.stream().collect(Collectors.toMap(AcDetails::getCode, a -> a));
                    acMap.put(c, detailsMap);
                } catch (IllegalStateException e) {
                    return JsonResult.failure("参考" + c.getName() + "，编码有重复，请检查数据~");
                }
            }

            //已经查询到的辅助明细
            Map<String, Object> exitAcd = new HashMap<>();
            //已查询到的科目
            Map<String, Subject> exitSubject = new HashMap<>();

            List<VoucherImportVo> importVos = new ArrayList<>();
            for (int i = 0, mapsSize = maps.size(); i < mapsSize; i++) {
                Map<String, Object> map = maps.get(i);
                JSONObject row = new JSONObject(map);
                VoucherImportVo voucherImportVo = BeanUtil.toBean(row, VoucherImportVo.class, CopyOptions.create().ignoreNullValue());
                for (AccountingCategoryDto c : categories) {
                    String code = row.getString("code" + c.getId());
                    String name = row.getString("name" + c.getId());
                    if (StrUtil.isNotEmpty(code)) {
                        Map<String, AcDetails> detailsMap = acMap.get(c);
                        if (detailsMap != null) {
                            AcDetails acDetails = detailsMap.get(code);
                            if (acDetails != null) {
                                name = acDetails.getName();
                            }
                        }
                        Assert.notEmpty(name, "第" + (i + 2) + "行，辅助项" + c.getName() + "名称未设置，请处理～");
                        Object acd = exitAcd.get(c.getCategoryType().name() + ":" + code);
                        if (acd == null) {
                            acd = SpringUtil.getBean(c.getCategoryType().dataService).loadByCode(accountSetsId, c.getId(), code);
                            if (acd == null) {
                                AcDetails acd2 = ReflectUtil.newInstance(c.getCategoryType().entity);
                                acd2.setCategoryType(c.getCategoryType());
                                acd2.setCategoryId(c.getId());
                                acd2.setCode(code);
                                acd2.setName(name);
                                acd2.setAccountSetsId(accountSetsId);
                                acd2.setEnable(true);
                                acd2.setId(voucherService.generateId());
                                newAcDetails.add(acd2);
                                acd = acd2;
                            }
                            exitAcd.put(c.getCategoryType().name() + ":" + code, acd);
                        }
                        voucherImportVo.getAccountingCategoryDetails().add((AcDetails) acd);
                    }
                }
                importVos.add(voucherImportVo);
            }

            Map<String, List<VoucherImportVo>> voucherMap = importVos.stream().collect(Collectors.groupingBy(d -> d.getDate() + d.getVoucherNumber()));
            List<VoucherDto> voucherList = new ArrayList<>();
            voucherMap.forEach((key, list) -> {
                VoucherImportVo vo = list.get(0);
                String tips = vo.getDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                List<String> wordCode = StrUtil.split(vo.getVoucherNumber(), "-");
                Assert.isTrue(wordCode.size() == 2, tips + "【" + vo.getVoucherNumber() + "】凭证号，格式错误。格式例：记-1");
                tips += "【" + vo.getVoucherNumber() + "】凭证号，";

                VoucherDto voucher = new VoucherDto();
                voucher.setWord(wordCode.get(0));
                voucher.setCode(Integer.valueOf(wordCode.get(1)));
                voucher.setRemark(vo.getRemark());
                voucher.setCreateMember(userVo.getId());
                voucher.setCreateDate(LocalDateTime.now());
                voucher.setDebitAmount(vo.getDebitCurrency());
                voucher.setCreditAmount(vo.getCreditCurrency());
                voucher.setAccountSetsId(accountSetsId);

                voucher.setVoucherDate(vo.getDate());
                voucher.setYear(voucher.getVoucherDate().getYear());
                voucher.setMonth(voucher.getVoucherDate().getMonthValue());
                voucher.setIsTemplate(false);
                voucher.setYearMonth(voucher.getVoucherDate().withDayOfMonth(1));

                List<VoucherDetailsDto> details = new ArrayList<>();
                BigDecimal debitAmount = BigDecimal.ZERO, creditAmount = BigDecimal.ZERO;
                for (VoucherImportVo vi : list) {
                    if (StrUtil.isEmpty(vi.getSubjectCode())) {
                        throw new ServiceException(tips + "科目编码不能为空,请核对数据~");
                    }
                    VoucherDetailsDto vd = new VoucherDetailsDto();
                    Subject subject = exitSubject.get(vi.getSubjectCode());
                    if (subject == null) {
                        subject = subjectService.listByCode(accountSetsId, vi.getSubjectCode());
                        if (subject == null) {
                            throw new ServiceException(tips + "科目编码【" + vi.getSubjectCode() + "】不存在,请先导入科目~");
                        }
                        exitSubject.put(vi.getSubjectCode(), subject);
                    }
                    vd.setSubjectId(subject.getId());
                    vd.setSummary(vi.getDigest());
                    vd.setCurrencyId(localCurrency.getId());
                    vd.setVoucherDate(vi.getDate());
                    vd.setDebitAmount(vi.getDebitCurrency());
                    vd.setCreditAmount(vi.getCreditCurrency());
                    vd.setCreditNum(vi.getCreditNumber());
                    vd.setDebitNum(vi.getDebitNumber());
                    debitAmount = NumberUtil.add(debitAmount, vi.getDebitCurrency());
                    creditAmount = NumberUtil.add(creditAmount, vi.getCreditCurrency());
                    vd.setAccountSetsId(accountSetsId);
                    vd.setAuxiliary(vi.getAccountingCategoryDetails());

                    if (vi.getCreditNumber() != null && !NumberUtil.equals(vi.getCreditNumber(), BigDecimal.ZERO)) {
                        vd.setNum(vi.getCreditNumber());
                    } else {
                        vd.setNum(vi.getDebitNumber());
                    }

                    if (vd.getNum() != null && !NumberUtil.equals(vd.getNum(), BigDecimal.ZERO)) {
                        vd.setPrice(NumberUtil.div(vd.getAmount(), vd.getNum()));
                    }
                    if (StrUtil.isNotEmpty(vi.getDigest()) && vi.getDigest().contains("结转损益")) {
                        vd.setCarryForward(true);
                        voucher.setCarryForward(true);
                    }
                    details.add(vd);
                }
                if (!NumberUtil.equals(debitAmount, creditAmount)) {
                    throw new ServiceException(tips + "借贷不平衡,借方金额：" + debitAmount + ",贷方金额：" + creditAmount + ",请核对数据~");
                }
                voucher.setDetails(details);
                voucher.setDebitAmount(debitAmount);
                voucher.setCreditAmount(creditAmount);
                voucherList.add(voucher);
            });

            // 检查导入凭证日期，必须是当前未结转账期之前的数据
            Optional<LocalDate> minVoucherDate = voucherList.stream().map(VoucherDto::getVoucherDate).min(LocalDate::compareTo);
            if (minVoucherDate.isPresent()) {
                Assert.isTrue(minVoucherDate.get().isAfter(lastChecked.plusDays(-1)), "请导入" + lastChecked.format(DateTimeFormatter.ofPattern("yyyy年MM期")) + "之后的数据");
                lastChecked = minVoucherDate.get();
            }

            accountingCategoryService.saveAll(newAcDetails);
            this.voucherService.importVoucher(voucherList, userVo.getAccountSets());

            balanceCalcService.calcSynchronization(accountSetsId, YearMonth.from(lastChecked));

            StpUtil.getSession().set(S_USER_KEY, this.userService.getUserVo(userVo.getId()));
            return JsonResult.successful();
        } catch (ServiceException | IllegalArgumentException e) {
            log.error("导入失败", e);
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("导入失败", e);
            return JsonResult.failure("导入失败，请检查数据~");
        }
    }
    @PostMapping("/importVoucher")
    public JsonResult importVoucher(@SessionUser UserVo userVo){
        String result = null;
        result = excelReaderUtil.importVoucher(userVo);
        return JsonResult.successful(result);
    }

    /**
     * 电子凭证册：返回指定期间内所有凭证及其关联附件（发票、银行流水、固定资产）
     */
    @GetMapping("/electronic-book")
    @SaIgnore
    public JsonResult electronicBook(
            @RequestParam String period,
            @RequestParam String creditCode) {

        // 通过税号查账套
        cn.gson.financial.kernel.model.entity.AccountSets accountSets =
                accountSetsRepository.findByCreditCode(creditCode).orElse(null);
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + creditCode);
        }
        Long accountSetsId = accountSets.getId();

        // 解析期间 "2026-03" → year=2026, month=3
        String[] parts = period.split("-");
        int year = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]);

        // 1. 查凭证，按日期+编号排序
        com.querydsl.core.types.dsl.BooleanExpression voucherPred =
                cn.gson.financial.kernel.model.entity.QVoucher.voucher.accountSetsId.eq(accountSetsId)
                .and(cn.gson.financial.kernel.model.entity.QVoucher.voucher.year.eq(year))
                .and(cn.gson.financial.kernel.model.entity.QVoucher.voucher.month.eq(month));
        List<Voucher> vouchers = new ArrayList<>();
        voucherRepository.findAll(voucherPred,
                cn.gson.financial.kernel.model.entity.QVoucher.voucher.voucherDate.asc(),
                cn.gson.financial.kernel.model.entity.QVoucher.voucher.code.asc())
                .forEach(vouchers::add);

        if (vouchers.isEmpty()) {
            return JsonResult.successful(Collections.emptyList());
        }

        List<Long> voucherIds = vouchers.stream().map(Voucher::getId).collect(Collectors.toList());

        // 2. 凭证明细
        List<cn.gson.financial.kernel.model.entity.VoucherDetails> allDetails =
                voucherDetailsRepository.findByVoucherIdIn(voucherIds);

        // 3. 科目（本账套）
        Map<Long, cn.gson.financial.kernel.model.entity.Subject> subjectMap =
                subjectRepository.selectByAccountSetsId(accountSetsId).stream()
                        .collect(Collectors.toMap(
                                cn.gson.financial.kernel.model.entity.Subject::getId,
                                s -> s));

        // 按 voucherId 分组明细
        Map<Long, List<cn.gson.financial.kernel.model.entity.VoucherDetails>> detailsByVoucherId =
                allDetails.stream().collect(Collectors.groupingBy(
                        cn.gson.financial.kernel.model.entity.VoucherDetails::getVoucherId));

        // 4. 发票
        List<cn.gson.financial.kernel.model.entity.Invoice> allInvoices =
                invoiceRepository.findByVoucherIdIn(voucherIds);
        List<Long> invoiceIds = allInvoices.stream()
                .map(cn.gson.financial.kernel.model.entity.Invoice::getId).collect(Collectors.toList());
        Map<Long, List<cn.gson.financial.kernel.model.entity.InvoiceItems>> itemsByInvoiceId =
                invoiceItemsRepository.findByInvoiceIdIn(invoiceIds).stream()
                        .collect(Collectors.groupingBy(
                                cn.gson.financial.kernel.model.entity.InvoiceItems::getInvoiceId));
        Map<Long, List<cn.gson.financial.kernel.model.entity.Invoice>> invoicesByVoucherId =
                allInvoices.stream().collect(Collectors.groupingBy(
                        cn.gson.financial.kernel.model.entity.Invoice::getVoucherId));

        // 5. 固定资产变更
        List<cn.gson.financial.kernel.model.entity.AssetsChange> allChanges =
                assetsChangeRepository.findByVoucherIdIn(voucherIds);
        Map<Long, List<cn.gson.financial.kernel.model.entity.AssetsChange>> changesByVoucherId =
                allChanges.stream().collect(Collectors.groupingBy(
                        cn.gson.financial.kernel.model.entity.AssetsChange::getVoucherId));

        // 6. 折旧
        List<cn.gson.financial.kernel.model.entity.AssetsDepreciation> allDepreciations =
                assetsDepreciationRepository.findByVoucherIdIn(voucherIds);
        Map<Long, List<cn.gson.financial.kernel.model.entity.AssetsDepreciation>> depByVoucherId =
                allDepreciations.stream().collect(Collectors.groupingBy(
                        cn.gson.financial.kernel.model.entity.AssetsDepreciation::getVoucherId));

        // 7. 资产信息（用于变更附件展示名称）
        List<Long> assetsIds = allChanges.stream()
                .map(cn.gson.financial.kernel.model.entity.AssetsChange::getAssetsId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList());
        Map<Long, cn.gson.financial.kernel.model.entity.Assets> assetsMap = assetsIds.isEmpty()
                ? Collections.emptyMap()
                : assetsRepository.findByIdInAndAccountSetsId(assetsIds, accountSetsId).stream()
                        .collect(Collectors.toMap(cn.gson.financial.kernel.model.entity.Assets::getId, a -> a));

        // 8. 银行流水（通过 CashierJournal 中转）
        List<cn.gson.financial.kernel.model.entity.CashierJournal> journals =
                cashierJournalRepository.findByVoucherIdIn(voucherIds);
        Map<Long, Long> voucherIdByJournalId = journals.stream()
                .collect(Collectors.toMap(
                        cn.gson.financial.kernel.model.entity.CashierJournal::getId,
                        cn.gson.financial.kernel.model.entity.CashierJournal::getVoucherId));
        List<Long> journalIds = new ArrayList<>(voucherIdByJournalId.keySet());
        Map<Long, List<cn.gson.financial.kernel.model.entity.BankStatementDetail>> bankByVoucherId = new HashMap<>();
        if (!journalIds.isEmpty()) {
            bankStatementDetailRepository.findByCashierJournalIdInAndIsDelete(journalIds, 0)
                    .forEach(detail -> {
                        Long vid = voucherIdByJournalId.get(detail.getCashierJournalId());
                        if (vid != null) {
                            bankByVoucherId.computeIfAbsent(vid, k -> new ArrayList<>()).add(detail);
                        }
                    });
        }

        // 9. 组装 VO
        List<cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo> result = new ArrayList<>();
        for (Voucher v : vouchers) {
            cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo vo =
                    new cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo();
            vo.setVoucherId(v.getId());
            vo.setWord(v.getWord());
            vo.setCode(v.getCode());
            vo.setVoucherDate(v.getVoucherDate());
            vo.setTotalDebit(v.getDebitAmount());
            vo.setTotalCredit(v.getCreditAmount());
            vo.setReceiptNum(v.getReceiptNum());

            // 明细行
            List<cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo.DetailItem> details = new ArrayList<>();
            for (cn.gson.financial.kernel.model.entity.VoucherDetails d :
                    detailsByVoucherId.getOrDefault(v.getId(), Collections.emptyList())) {
                cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo.DetailItem di =
                        new cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo.DetailItem();
                di.setSubjectId(d.getSubjectId());
                di.setSummary(d.getSummary());
                di.setDebitAmount(d.getDebitAmount());
                di.setCreditAmount(d.getCreditAmount());
                if (d.getSubjectId() != null && subjectMap.containsKey(d.getSubjectId())) {
                    cn.gson.financial.kernel.model.entity.Subject sub = subjectMap.get(d.getSubjectId());
                    di.setSubjectCode(sub.getCode());
                    di.setSubjectName(sub.getFullName());
                }
                details.add(di);
            }
            vo.setDetails(details);

            // 附件
            List<cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo.AttachmentItem> attachments = new ArrayList<>();

            // 发票附件
            for (cn.gson.financial.kernel.model.entity.Invoice inv :
                    invoicesByVoucherId.getOrDefault(v.getId(), Collections.emptyList())) {
                cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo.AttachmentItem ai =
                        new cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo.AttachmentItem();
                ai.setType("invoice");
                ai.setId(inv.getId());
                ai.setInvoiceNo(inv.getInvoiceNumber());
                ai.setCounterparty(inv.getCustomerName());
                ai.setTradeDate(inv.getTradeDate());
                ai.setAmount(inv.getAmount());
                ai.setTaxAmount(inv.getTaxAmount());
                ai.setTaxAmountExcluded(inv.getTaxAmountExcluded());
                ai.setNewCategory(inv.getNewCategory());
                ai.setInvoiceDirection(inv.getType() != null ? inv.getType().name() : null);
                // 发票明细行
                List<cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo.InvoiceItemDetail> items = new ArrayList<>();
                for (cn.gson.financial.kernel.model.entity.InvoiceItems it :
                        itemsByInvoiceId.getOrDefault(inv.getId(), Collections.emptyList())) {
                    cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo.InvoiceItemDetail id2 =
                            new cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo.InvoiceItemDetail();
                    id2.setGoodsName(it.getGoodsName());
                    id2.setSpecification(it.getSpecification());
                    id2.setUnit(it.getUnit());
                    id2.setQuantity(it.getQuantity());
                    id2.setAmount(it.getAmount());
                    id2.setTaxRate(it.getTaxRate());
                    id2.setTax(it.getTax());
                    items.add(id2);
                }
                ai.setInvoiceItems(items);
                attachments.add(ai);
            }

            // 银行流水附件
            for (cn.gson.financial.kernel.model.entity.BankStatementDetail bd :
                    bankByVoucherId.getOrDefault(v.getId(), Collections.emptyList())) {
                cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo.AttachmentItem ai =
                        new cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo.AttachmentItem();
                ai.setType("bank");
                ai.setId(bd.getId());
                ai.setTransactionType(bd.getTransactionType());
                ai.setBankTradeDate(bd.getTradeDate());
                ai.setTradeAmount(bd.getTradeAmount());
                ai.setBalance(bd.getBalance());
                ai.setPayName(bd.getPayName());
                ai.setReceiveName(bd.getReceiveName());
                ai.setBankRemarks(bd.getRemarks());
                attachments.add(ai);
            }

            // 资产变更附件
            for (cn.gson.financial.kernel.model.entity.AssetsChange ac :
                    changesByVoucherId.getOrDefault(v.getId(), Collections.emptyList())) {
                cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo.AttachmentItem ai =
                        new cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo.AttachmentItem();
                ai.setType("asset_change");
                ai.setId(ac.getId());
                ai.setChangeType(ac.getChangeType() != null ? ac.getChangeType().name() : null);
                cn.gson.financial.kernel.model.entity.Assets asset = assetsMap.get(ac.getAssetsId());
                if (asset != null) {
                    ai.setAssetName(asset.getName());
                    ai.setAssetCode(asset.getCode());
                }
                attachments.add(ai);
            }

            // 折旧附件
            for (cn.gson.financial.kernel.model.entity.AssetsDepreciation dep :
                    depByVoucherId.getOrDefault(v.getId(), Collections.emptyList())) {
                cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo.AttachmentItem ai =
                        new cn.gson.financial.kernel.model.vo.VoucherElectronicBookVo.AttachmentItem();
                ai.setType("asset_depreciation");
                ai.setId(dep.getId());
                ai.setChangeType("折旧");
                attachments.add(ai);
            }

            vo.setAttachments(attachments);
            result.add(vo);
        }

        return JsonResult.successful(result);
    }
}
