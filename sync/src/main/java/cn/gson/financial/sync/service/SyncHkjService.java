package cn.gson.financial.sync.service;

import cn.gson.financial.kernel.common.PinYinUtil;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.*;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountStandard;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.mapper.SubjectMapper;
import cn.gson.financial.kernel.model.repo.CheckoutRepository;
import cn.gson.financial.kernel.model.vo.BalanceAuxiliaryForm;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.service.*;
import cn.gson.financial.kernel.tool.SubjectUtil;
import cn.gson.financial.sync.AbsImportService;
import cn.gson.financial.sync.SyncType;
import cn.gson.financial.sync.dto.*;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 好会计导入</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年08月28日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Service
@Slf4j
public class SyncHkjService extends AbsImportService {
    private final BalanceService balanceService;

    private final BalanceInitialService balanceInitialService;

    private final VoucherService voucherService;

    private final QSubject qSubject = QSubject.subject;

    private final SubjectMapper subjectMapper;

    public SyncHkjService(AccountSetsService accountSetsService, VoucherService voucherService,
                          CheckoutService checkoutService, AccountingCategoryService categoryService,
                          CurrencyService currencyService, BalanceService balanceService, BalanceInitialService balanceInitialService,
                          CheckoutRepository checkoutRepository, SubjectMapper subjectMapper) {
        super(accountSetsService, checkoutService, categoryService, currencyService, checkoutRepository);
        this.subjectMapper = subjectMapper;
        this.balanceService = balanceService;
        this.balanceInitialService = balanceInitialService;
        this.voucherService = voucherService;
    }


    private Map<String, SubjectVo> createSubject(List<SubjectHkjImportVo> subjectList, AccountSets ast, List<SubjectImportMappingDto> subjectMappings) {
        int encLen = ast.getEncoding().stream().reduce(Integer::sum).orElse(0);
        //原有科目结构
        Map<String, Subject> subjectMap = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(ast.getId()))
                .fetch().stream().collect(Collectors.toMap(Subject::getCode, s -> s));

        //导入科目结构
        Map<String, SubjectHkjImportVo> importMap = subjectList.stream().collect(Collectors.toMap(SubjectHkjImportVo::getCode, s -> s));

        Map<String, SubjectVo> subjectVoMap = new HashMap<>();
        Map<String, SubjectVo> subjectVoSysMap = new HashMap<>();

        List<SubjectVo> subjectVos = new ArrayList<>();

        for (SubjectImportMappingDto mapping : subjectMappings) {
            if (StrUtil.isNotEmpty(mapping.getMatchedCode())) {
                SubjectHkjImportVo item = importMap.get(mapping.getMatchedCode());
                SubjectVo vo;
                if (mapping.getIsNew()) {
                    // 获取导入科目信息
                    vo = new SubjectVo();
                    vo.setId(snowflakeId.generate());
                    vo.setType(this.jdyType(ast, StrUtil.trim(item.getCode())));
                    vo.setCode(mapping.getCode());
                    vo.setName(StrUtil.trim(item.getName()));
                    vo.setMnemonicCode(PinYinUtil.getFirstLettersLo(vo.getName()));
                    vo.setBalanceDirection(StrUtil.trim(item.getBalanceDirection()));
                    vo.setFullName(item.getName());
                    vo.setLeaf(true);
                    vo.setSystemDefault(false);
                    vo.setQueryCode(StrUtil.padAfter(vo.getCode(), encLen, "0"));
                    vo.setAccountSetsId(ast.getId());
                    vo.setParentId(0L);
                    List<String> codePath = SubjectUtil.checkCode(vo.getCode(), ast.getEncoding());
                    vo.setLevel(codePath.size());
                    if (codePath.size() > 1) {
                        String parentCode = CollUtil.join(codePath.subList(0, codePath.size() - 1), "");
                        vo.setParentId(subjectMap.get(parentCode).getId());
                        vo.setCash(subjectMap.get(parentCode).getCash());
                        subjectVoSysMap.get(parentCode).setLeaf(false);
                    }
                    subjectMap.put(vo.getCode(), vo);
                } else {
                    vo = subjectMapper.toVo(subjectMap.get(mapping.getCode()));
                }
                vo.setStatus(true);

                if (StrUtil.isNotEmpty(item.getUnit())) {
                    vo.setUnit(item.getUnit());
                }

                if (StrUtil.isNotEmpty(item.getAuxiliaryAccounting())) {
                    List<Long> auxiliaryAccountings = this.getCategory(ast.getId(), StrUtil.trim(item.getAuxiliaryAccounting()));
                    vo.setAuxiliaryAccountingStr(JSON.toJSONString(auxiliaryAccountings));
                    vo.setAuxiliaryAccounting(auxiliaryAccountings);
                }
                if (StrUtil.isNotEmpty(item.getCurrencyAccounting())) {
                    vo.setCurrencyAccountingStr(JSON.toJSONString(this.getCurrency(ast.getId(), StrUtil.trim(item.getCurrencyAccounting()))));
                }
                vo.setAdjustments(false);
                subjectVos.add(vo);
                subjectVoMap.put(mapping.getMatchedCode(), vo);
                subjectVoSysMap.put(mapping.getCode(), vo);
            }
        }

        genFullName(subjectVos);
        lazyDao.batchUpdate("batchInsertSubject", subjectVos);
        List<String> leafCodes = subjectList.parallelStream().filter(vo -> subjectList.parallelStream()
                        .noneMatch(v -> !v.getCode().equals(vo.getCode()) && v.getCode().startsWith(vo.getCode())))
                .map(SubjectHkjImportVo::getCode).toList();
        this.updateSubjectLeafStatus(leafCodes, ast, subjectMap);
        return subjectVoMap;
    }

    private String jdyType(AccountSets ast, String code) {
        String str = null;
        switch (ast.getAccountStandard()) {
            case BUSINESS20191, BUSINESS20192, NONGOVERNMENTAL, FARMER, SMALL_BUSINESS -> {
                if (code.startsWith("1")) {
                    str = "资产";
                } else if (code.startsWith("2")) {
                    str = "负债";
                } else if (code.startsWith("3")) {
                    str = "权益";
                } else if (code.startsWith("4")) {
                    str = "成本";
                } else if (code.startsWith("5")) {
                    str = "损益";
                } else {
                    throw new ServiceException(code + "科目类型无法确认~");
                }
            }
            case BUSINESS -> {
                if (code.startsWith("1")) {
                    str = "资产";
                } else if (code.startsWith("2")) {
                    str = "负债";
                } else if (code.startsWith("3")) {
                    str = "共同";
                } else if (code.startsWith("4")) {
                    str = "权益";
                } else if (code.startsWith("5")) {
                    str = "成本";
                } else if (code.startsWith("6")) {
                    str = "损益";
                } else {
                    throw new ServiceException(code + "科目类型无法确认~");
                }
            }
        }
        Assert.notEmpty(str, code + "科目类型无法确认~");
        return str;
    }

    public void initialBalance(List<BalanceInitiaHkjImportVo> balanceInitialList, AccountSets ast, Map<String, SubjectVo> subjectVoMap) {
        Map<Long, AccountingCategoryDto> accountingCategoryMap = categoryService.listByAccountSetsId(ast.getId()).stream().collect(Collectors.toMap(AccountingCategoryDto::getId, b -> b));

        List<BalanceInitialDto> details = new ArrayList<>();
        CurrencyDto localCurrency = currencyService.fetchLocal(ast.getId());
        for (BalanceInitiaHkjImportVo row : balanceInitialList) {
            //辅助核算项目
            BalanceInitialDto balance = new BalanceInitialDto();
            balance.setAccountSetsId(ast.getId());
            balance.setCurrencyId(localCurrency.getId());
            SubjectVo subjectVo = subjectVoMap.get(row.getCode());
            Assert.notNull(subjectVo, row.getCode() + "科目不存在,请检查~");
            balance.setSubjectId(subjectVo.getId());
            balance.setLeaf(subjectVo.getLeaf());
            balance.setLevel(subjectVo.getLevel());
            String[] codes = row.getCodeSp();
            List<String> names = row.getNameSp();
            if (ArrayUtil.isNotEmpty(codes)) { //存在 辅助项
                //创建 辅助项 明细
                BalanceAuxiliaryForm baf = new BalanceAuxiliaryForm();
                baf.setSubjectId(subjectVo.getId());
                baf.setCurrencyId(balance.getCurrencyId());
                List<AuxiliaryDetail.Detail> des = new ArrayList<>();
                for (int i = 0; i < codes.length; i++) {
                    Long aaId = subjectVo.getAuxiliaryAccounting().get(i);
                    AccountingCategoryDto category = accountingCategoryMap.get(aaId);
                    AuxiliaryDataService dataService = SpringUtil.getBean(category.getCategoryType().dataService);

                    AcDetails acDetails = dataService.loadByCode(ast.getId(), aaId, codes[i]);
                    if (acDetails == null) {
                        acDetails = new AcDetails();
                        acDetails.setId(snowflakeId.generate());
                        acDetails.setAccountSetsId(ast.getId());
                        acDetails.setCategoryType(category.getCategoryType());
                        acDetails.setCategoryId(category.getId());
                        acDetails.setCode(codes[i]);
                        acDetails.setName(names.get(i));
                        acDetails.setEnable(true);
                        dataService.save(BeanUtil.toBean(acDetails, category.getCategoryType().entity));
                    }

                    AuxiliaryDetail.Detail detail = new AuxiliaryDetail.Detail();
                    detail.setCategoryId(aaId);
                    detail.setCategoryType(category.getCategoryType());
                    detail.setValue(acDetails.getId());
                    des.add(detail);
                }
                //保存辅助项目
                Long detailId = balanceService.getDetailId(ast, des);
                balance.setAuxiliaryDetailId(detailId);
            }
            if (!row.getBalanceDirection().equals(subjectVo.getBalanceDirection())) {
                balance.setBeginBalance(NumberUtil.sub(BigDecimal.ZERO, row.getBeginBalance()));
            } else {
                balance.setBeginBalance(row.getBeginBalance());
            }
            balance.setBeginQuantity(row.getBeginQuantity());
            balance.setYearTotalDebitQuantity(row.getYearTotalDebitQuantity());
            balance.setYearTotalDebit(row.getYearTotalDebit());
            balance.setYearTotalCreditQuantity(row.getYearTotalCreditQuantity());
            balance.setYearTotalCredit(row.getYearTotalCredit());
            balance.setYearTotalQuantity(row.getYearTotalCreditQuantity());
            balance.setYearTotal(row.getYearTotal());
            details.add(balance);
        }
        balanceInitialService.updateInitBalance(details, ast, subjectVoMap);
    }

    private void createVoucher(List<VoucherHkjImportVo> voucherList, AccountSets ast, Map<String, SubjectVo> subjectVoMap, Long userId) {
        Map<VoucherTagVo, List<VoucherHkjImportVo>> voucherListMap = voucherList.stream().collect(Collectors.groupingBy(v -> new VoucherTagVo(v.getDate(), v.getWord(), v.getCode())));

        Map<Long, AccountingCategoryDto> accountingCategoryMap = categoryService.listByAccountSetsId(ast.getId()).stream().collect(Collectors.toMap(AccountingCategoryDto::getId, b -> b));
        CurrencyDto localCurrency = currencyService.fetchLocal(ast.getId());
        List<VoucherDto> voucherDtos = new ArrayList<>();
        voucherListMap.forEach((tag, v) -> {
            VoucherDto voucherDto = new VoucherDto();
            voucherDto.setWord(tag.getWord());
            voucherDto.setCode(tag.getCode());
            voucherDto.setCreateMember(userId);
            voucherDto.setCreateDate(LocalDateTime.now());

            voucherDto.setAccountSetsId(ast.getId());
            voucherDto.setYear(tag.getDate().getYear());
            voucherDto.setMonth(tag.getDate().getMonthValue());
            voucherDto.setVoucherDate(tag.getDate());
            voucherDto.setCarryForward(v.get(0).getDigest().startsWith("结转"));

            voucherDto.setYearMonth(tag.getDate());
            voucherDto.setIsTemplate(false);
            voucherDto.setLocked(false);
            voucherDto.setId(snowflakeId.generate());

            BigDecimal debitAmount = BigDecimal.ZERO;
            BigDecimal creditAmount = BigDecimal.ZERO;

            List<VoucherDetailsDto> details = new ArrayList<>();

            for (VoucherHkjImportVo d : v) {
                debitAmount = NumberUtil.add(debitAmount, d.getDebitAmount());
                creditAmount = NumberUtil.add(creditAmount, d.getCreditAmount());

                VoucherDetailsDto vd = new VoucherDetailsDto();
                vd.setSummary(d.getDigest());
                vd.setAccountSetsId(ast.getId());
                vd.setVoucherId(voucherDto.getId());
                vd.setYear(voucherDto.getYear());
                vd.setMonth(voucherDto.getMonth());
                vd.setVoucherDate(voucherDto.getVoucherDate());
                vd.setDebitAmount(d.getDebitAmount());
                vd.setCreditAmount(d.getCreditAmount());
                vd.setNum(d.getNumber());
                vd.setPrice(d.getPrice());
                vd.setCarryForward(voucherDto.getCarryForward());
                vd.setOriginalAmount(Optional.ofNullable(d.getDebitAmount()).orElse(d.getCreditAmount()));

                SubjectVo subjectVo = getSubjectVo(ast, subjectVoMap, d.getSubjectCode(), subjectVoMap.get(d.getSubjectCode()));

                vd.setSubjectId(subjectVo.getId());
                vd.setCurrencyId(localCurrency.getId());

//                vd.setExchangeRate();
//                vd.setDebitAmountFor();
//                vd.setCreditAmountFor();

                if (CollUtil.isNotEmpty(d.getAccountingCategoryDetails())) {
                    List<AcDetails> auxiliary = new ArrayList<>();
                    for (int i = 0; i < subjectVo.getAuxiliaryAccounting().size(); i++) {
                        Long aaId = subjectVo.getAuxiliaryAccounting().get(i);
                        AccountingCategoryDto category = accountingCategoryMap.get(aaId);
                        AuxiliaryDataService dataService = SpringUtil.getBean(category.getCategoryType().dataService);

                        AcDetails acDetails = dataService.loadByName(ast.getId(), aaId, d.getAccountingCategoryDetails().get(i).getName());
                        if (acDetails == null) {
                            acDetails = new AcDetails();
                            acDetails.setId(snowflakeId.generate());
                            acDetails.setAccountSetsId(ast.getId());
                            acDetails.setCategoryType(category.getCategoryType());
                            acDetails.setCategoryId(category.getId());
                            acDetails.setCode(RandomUtil.randomNumbers(4));
                            acDetails.setName(d.getAccountingCategoryDetails().get(i).getName());
                            acDetails.setEnable(true);
                            dataService.save(BeanUtil.toBean(acDetails, category.getCategoryType().entity));
                        }
                        auxiliary.add(acDetails);
                    }
                    vd.setAuxiliary(auxiliary);
                }

                switch (subjectVo.getBalanceDirection()) {
                    case "借" -> {
                        vd.setDebitNum(d.getNumber());
                    }
                    case "贷" -> {
                        vd.setCreditNum(d.getNumber());
                    }
                }
                if (vd.getSummary().startsWith("结转")) {
                    vd.setCarryForward(true);
                    voucherDto.setCarryForward(true);
                }
                details.add(vd);
            }

            voucherDto.setDetails(details);
            voucherDto.setDebitAmount(debitAmount);
            voucherDto.setCreditAmount(creditAmount);
            voucherDtos.add(voucherDto);
        });

        voucherService.importVoucher(voucherDtos, ast);
    }

    @Override
    public AccountSets analysisData(MultipartFile fileKm, MultipartFile fileQc, MultipartFile filePz, String companyName, List<Integer> encodings, List<SubjectImportMappingDto> subjectMappings, VatType vatType, AccountStandard accountStandard, Long userId, Long astId, SyncType soft) throws IOException {
        //读取科目Excel数据
        ExcelReader reader = ExcelUtil.getReader(fileKm.getInputStream(), 0);
        List<SubjectHkjImportVo> subjectRows = reader.readAll(SubjectHkjImportVo.class);
        Assert.isFalse(subjectRows.isEmpty(), "导入科目数据为空");

        //读取凭证Excel数据
        ExcelReader pzReader = ExcelUtil.getReader(filePz.getInputStream(), 0);
        List<VoucherHkjImportVo> voucherList = pzReader.readAll(VoucherHkjImportVo.class);
        Assert.isFalse(voucherList.isEmpty(), "导入凭证数据为空");

        List<LocalDate> localDates = voucherList.stream().map(v -> v.getDate().withDayOfMonth(1)).distinct().toList();

        //获取账套启用日期
        LocalDate minDate = CollUtil.min(localDates);
        LocalDate maxDate = CollUtil.max(localDates);

        //检查最大的编码是否符合 编码设置
        Optional<String> maxCode = subjectRows.stream().map(SubjectHkjImportVo::getCode).toList().stream().max(Comparator.comparingInt(String::length));
        SubjectUtil.checkCode(maxCode.get(), encodings);

        //1、创建账套
        AccountSets ast = this.createImportAst(companyName, vatType, accountStandard, encodings, userId, minDate, soft, astId);

        //2、科目
        Map<String, SubjectVo> subjectVos = this.createSubject(subjectRows, ast, subjectMappings);

        if (fileQc != null) {
            //读取期初Excel数据
            ExcelReader qcReader = ExcelUtil.getReader(fileQc.getInputStream(), 0);
            List<BalanceInitiaHkjImportVo> balanceInitialList = qcReader.readAll(BalanceInitiaHkjImportVo.class);

            //4、期初余额
            this.initialBalance(balanceInitialList, ast, subjectVos);
        }

        //4、凭证
        this.createVoucher(voucherList, ast, subjectVos, userId);

        //3、结账周期
        this.createPeriods(ast, this.checkDateList(minDate, maxDate));

        return ast;
    }
}
