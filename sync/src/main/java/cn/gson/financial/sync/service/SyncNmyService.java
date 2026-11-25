package cn.gson.financial.sync.service;

import cn.gson.financial.kernel.common.PinYinUtil;
import cn.gson.financial.kernel.model.dto.*;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountStandard;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.mapper.SubjectMapper;
import cn.gson.financial.kernel.model.repo.CheckoutRepository;
import cn.gson.financial.kernel.model.vo.BalanceImportVo;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.service.*;
import cn.gson.financial.kernel.tool.SubjectUtil;
import cn.gson.financial.sync.AbsImportService;
import cn.gson.financial.sync.SyncType;
import cn.gson.financial.sync.dto.SubjectImportMappingDto;
import cn.gson.financial.sync.dto.SubjectNmyImportVo;
import cn.gson.financial.sync.dto.VoucherNmyImportVo;
import cn.gson.financial.sync.dto.VoucherTagVo;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
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
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.sync.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年02月28日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
public class SyncNmyService extends AbsImportService {

    private final BalanceInitialService balanceInitialService;

    private final VoucherService voucherService;

    private final QSubject qSubject = QSubject.subject;

    private final QAccountingCategory qAccountingCategory = QAccountingCategory.accountingCategory;

    private final SubjectMapper subjectMapper;

    public SyncNmyService(AccountSetsService accountSetsService, VoucherService voucherService,
                          CheckoutService checkoutService, AccountingCategoryService categoryService,
                          CurrencyService currencyService, BalanceInitialService balanceInitialService,
                          CheckoutRepository checkoutRepository, SubjectMapper subjectMapper) {
        super(accountSetsService, checkoutService, categoryService, currencyService, checkoutRepository);
        this.subjectMapper = subjectMapper;
        this.balanceInitialService = balanceInitialService;
        this.voucherService = voucherService;
    }

    @Override
    public AccountSets analysisData(MultipartFile fileKm, MultipartFile fileQc, MultipartFile filePz, String companyName, List<Integer> encodings, List<SubjectImportMappingDto> subjectMappings, VatType vatType, AccountStandard accountStandard, Long userId, Long astId, SyncType soft) throws IOException {
        //读取科目Excel数据
        ExcelReader reader = ExcelUtil.getReader(fileKm.getInputStream(), 0);
        List<SubjectNmyImportVo> subjectRows = reader.readAll(SubjectNmyImportVo.class).stream().filter(s->StrUtil.isNotEmpty(s.getCode())).toList();
        Assert.isFalse(subjectRows.isEmpty(), "导入科目数据为空");
        LocalDate minDate = LocalDate.now(), maxDate = LocalDate.now();
        ExcelReader pzReader = null;
        List<VoucherNmyImportVo> voucherList = null;

        if (filePz != null) {
            //读取凭证Excel数据
            pzReader = ExcelUtil.getReader(filePz.getInputStream(), 0);
            voucherList = pzReader.readAll(VoucherNmyImportVo.class);
            Assert.isFalse(voucherList.isEmpty(), "导入凭证数据为空");
            List<LocalDate> localDates = voucherList.stream().map(v -> v.getDate().withDayOfMonth(1)).distinct().toList();
            //获取账套启用日期
            minDate = CollUtil.min(localDates);
            maxDate = CollUtil.max(localDates);
        }

        //1、创建账套
        AccountSets ast = this.createImportAst(companyName, vatType, accountStandard, encodings, userId, minDate, soft, astId);

        //2、科目
        Map<String, SubjectVo> subjectVos = this.createSubject(subjectRows, ast, subjectMappings);

        if (fileQc != null) {
            //读取期初Excel数据
            ExcelReader qcReader = ExcelUtil.getReader(fileQc.getInputStream());
            List<BalanceImportVo> balanceImportVos = new ArrayList<>();
            if (qcReader.getColumnCount() > 10) {
                //带数量核算
                List<Object> objects = qcReader.readRow(1);
                for (int i = 0; i < objects.size(); i++) {
                    String value = qcReader.getCell(i, 0).getStringCellValue();
                    if (!objects.get(i).equals(value)) {
                        qcReader.getCell(i, 1).setCellValue(value + objects.get(i));
                    }
                }
                balanceImportVos = qcReader.read(1, 2, qcReader.getRowCount(), BalanceImportVo.class);
            } else {
                balanceImportVos = qcReader.readAll(BalanceImportVo.class);
            }
            //4、期初余额
            this.initialBalance(balanceImportVos, ast, subjectVos);
        }

        // 自定义辅助核算
        List<AccountingCategory> cusCategories = bqf.selectFrom(qAccountingCategory)
                .where(qAccountingCategory.accountSetsId.eq(ast.getId())
                        .and(qAccountingCategory.categoryType.eq(AccountingCategoryType.CUSTOMIZATION)))
                .fetch();

        if (pzReader != null) {
            pzReader.clearHeaderAlias();
            if (CollUtil.isNotEmpty(cusCategories)) {
                for (AccountingCategory cusCategory : cusCategories) {
                    pzReader.addHeaderAlias(cusCategory.getName() + "编码", cusCategory.getName() + "code");
                    pzReader.addHeaderAlias(cusCategory.getName() + "名称", cusCategory.getName() + "name");
                }
            }

            List<Map<String, Object>> mapList = pzReader.readAll();
            for (int i = 0; i < mapList.size(); i++) {
                Map<String, Object> map = mapList.get(i);
                if (CollUtil.isNotEmpty(cusCategories)) {
                    for (AccountingCategory c : cusCategories) {
                        if (!StrUtil.isEmptyIfStr(map.get(c.getName() + "code"))) {
                            voucherList.get(i).addAccountingCategoryDetails(new AcDetails(map.get(c.getName() + "name").toString(), map.get(c.getName() + "code").toString(), AccountingCategoryType.CUSTOMIZATION));
                        }
                    }
                }
            }

            //4、凭证
            this.createVoucher(voucherList, ast, subjectVos, userId);
        }

        //3、结账周期
        this.createPeriods(ast, this.checkDateList(minDate, maxDate));

        return ast;
    }


    private Map<String, SubjectVo> createSubject(List<SubjectNmyImportVo> subjectList, AccountSets ast, List<SubjectImportMappingDto> subjectMappings) {
        int encLen = ast.getEncoding().stream().reduce(Integer::sum).orElse(0);
        //原有科目结构
        Map<String, Subject> subjectMap = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(ast.getId()))
                .fetch().stream().collect(Collectors.toMap(Subject::getCode, s -> s));

        //导入科目结构
        Map<String, SubjectNmyImportVo> importMap = subjectList.stream().collect(Collectors.toMap(SubjectNmyImportVo::getCode, s -> s));

        Map<String, SubjectVo> subjectVoMap = new HashMap<>();
        Map<String, SubjectVo> subjectVoSysMap = new HashMap<>();

        List<SubjectVo> subjectVos = new ArrayList<>();

        for (SubjectImportMappingDto mapping : subjectMappings) {
            if (StrUtil.isNotEmpty(mapping.getMatchedCode())) {
                SubjectNmyImportVo item = importMap.get(mapping.getMatchedCode());
                SubjectVo vo;
                if (mapping.getIsNew()) {
                    // 获取导入科目信息
                    vo = new SubjectVo();
                    vo.setId(snowflakeId.generate());
                    vo.setType(item.getType().replace("类", ""));
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
                        vo.setFullName(subjectMap.get(parentCode).getFullName() + "_" + vo.getName());
                        vo.setMnemonicCode(PinYinUtil.getFirstLettersLo(vo.getFullName()));
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
                .map(SubjectNmyImportVo::getCode).toList();
        this.updateSubjectLeafStatus(leafCodes, ast, subjectMap);
        return subjectVoMap;
    }

    public void initialBalance(List<BalanceImportVo> balanceImportVos, AccountSets ast, Map<String, SubjectVo> subjectVoMap) {
        List<BalanceInitialDto> details = new ArrayList<>();
        Map<String, AcDetails> acDetailsMap = new HashMap<>();
        CurrencyDto localCurrency = currencyService.fetchLocal(ast.getId());
        balanceImportVos = balanceImportVos.stream().filter(BalanceImportVo::isNotEmptyCode).toList();
        for (BalanceImportVo importVo : balanceImportVos) {
            BalanceInitialDto balance = new BalanceInitialDto();
            balance.setAccountSetsId(ast.getId());
            balance.setCurrencyId(localCurrency.getId());
            Assert.isTrue(subjectVoMap.containsKey(importVo.getCode()), importVo.getCode() + "在账套中不存在，请先创建科目~");
            SubjectVo subjectVo = subjectVoMap.get(importVo.getCode());
            balance.setSubjectId(subjectVo.getId());
            balance.setAuxiliaryDetailId(0L);
            balance.setTreeId(subjectVo.getId());
            balance.setParentTreeId(subjectVo.getParentId());
            balance.setLeaf(subjectVo.getLeaf());

            if ("是".equals(importVo.getAuxiliary()) && StrUtil.isNotEmpty(importVo.getAuxiliaryCode())) {
                Assert.notEmpty(subjectVo.getAuxiliaryAccounting(), subjectVo.getCode() + " " + subjectVo.getName() + "未设置辅助项,请检查科目~");
                List<AccountingCategoryDto> accountingCategoryDtos = categoryService
                        .listByIdsWidthData(ast.getId(), new HashSet<>(subjectVo.getAuxiliaryAccounting()));

                // 编码
                List<String> codeSplit = StrUtil.split(importVo.getAuxiliaryCode(), "_");
                List<String> nameSplit = StrUtil.split(importVo.getAuxiliaryName(), "_");
                List<AcDetails> auxiliary = new ArrayList<>();
                for (int i = 0; i < codeSplit.size(); i++) {
                    String code = codeSplit.get(i);
                    AccountingCategoryDto category = accountingCategoryDtos.get(i);

                    AcDetails acDetails = acDetailsMap.get(category.getId() + ":" + code);
                    if (acDetails == null) {
                        AuxiliaryDataService dataService = SpringUtil.getBean(category.getCategoryType().dataService);
                        acDetails = dataService.loadByCode(ast.getId(), category.getId(), code);
                        if (acDetails == null) {
                            acDetails = new AcDetails();
                            acDetails.setAccountSetsId(ast.getId());
                            acDetails.setCategoryId(category.getId());
                            acDetails.setCategoryType(category.getCategoryType());
                            acDetails.setName(nameSplit.get(i));
                            acDetails.setCode(code);
                            acDetails = dataService.save(BeanUtil.toBean(acDetails, category.getCategoryType().entity));
                        }
                        acDetailsMap.put(category.getId() + ":" + code, acDetails);
                    }

                    auxiliary.add(acDetails);
                }
                balance.setAuxiliaryDetailId(voucherService.getAuxiliaryDetailId(ast.getId(), auxiliary));
                balance.setTreeId(subjectVo.getId() + balance.getAuxiliaryDetailId());
                balance.setParentTreeId(subjectVo.getId());
                balance.setLeaf(true);
            }

            if (balance.getLeaf()) {
                if (!importVo.getBalanceDirection().equals(subjectVo.getBalanceDirection())) {
                    balance.setBeginBalance(NumberUtil.sub(BigDecimal.ZERO, importVo.getBeginBalance()));
                } else {
                    balance.setBeginBalance(importVo.getBeginBalance());
                }
                balance.setBeginBalance(importVo.getBeginBalance());
                balance.setYearTotalDebit(importVo.getYearTotalDebit());
                balance.setYearTotalCredit(importVo.getYearTotalCredit());
                balance.setYearTotal(importVo.getYearTotal());
                balance.setBeginQuantity(importVo.getBeginQuantity());
                balance.setYearTotalCreditQuantity(importVo.getYearTotalCreditQuantity());
                balance.setYearTotalDebitQuantity(importVo.getYearTotalDebitQuantity());
                balance.setYearTotalQuantity(importVo.getYearTotalQuantity());
            }

            details.add(balance);
        }

        Map<Long, BalanceInitialDto> balanceDtoMap = details.stream().collect(Collectors.toMap(BalanceInitialDto::getTreeId, d -> d));
        details.stream().filter(BalanceInitialDto::getLeaf).forEach(d -> {
            accumulatedCalculation(balanceDtoMap, d);
        });
        //过滤掉没有值的行
        details = details.stream().filter(BalanceInitialDto::isUsed).collect(Collectors.toList());
        balanceInitialService.updateInitBalance(details, ast);
    }

    private static void accumulatedCalculation(Map<Long, BalanceInitialDto> balanceDtoMap, BalanceInitialDto d) {
        BalanceInitialDto balanceDto = balanceDtoMap.get(d.getParentTreeId());
        if (balanceDto != null) {
            balanceDto.setBeginBalance(NumberUtil.add(d.getBeginBalance(), balanceDto.getBeginBalance()));
            balanceDto.setBeginBalanceFor(NumberUtil.add(d.getBeginBalanceFor(), balanceDto.getBeginBalanceFor()));
            balanceDto.setBeginQuantity(NumberUtil.add(d.getBeginQuantity(), balanceDto.getBeginQuantity()));
            balanceDto.setYearTotal(NumberUtil.add(d.getYearTotal(), balanceDto.getYearTotal()));
            balanceDto.setYearTotalFor(NumberUtil.add(d.getYearTotalFor(), balanceDto.getYearTotalFor()));
            balanceDto.setYearTotalCredit(NumberUtil.add(d.getYearTotalCredit(), balanceDto.getYearTotalCredit()));
            balanceDto.setYearTotalDebit(NumberUtil.add(d.getYearTotalDebit(), balanceDto.getYearTotalDebit()));
            balanceDto.setYearTotalCreditFor(NumberUtil.add(d.getYearTotalCreditFor(), balanceDto.getYearTotalCreditFor()));
            balanceDto.setYearTotalDebitFor(NumberUtil.add(d.getYearTotalDebitFor(), balanceDto.getYearTotalDebitFor()));
            balanceDto.setYearTotalCreditQuantity(NumberUtil.add(d.getYearTotalCreditQuantity(), balanceDto.getYearTotalCreditQuantity()));
            balanceDto.setYearTotalDebitQuantity(NumberUtil.add(d.getYearTotalDebitQuantity(), balanceDto.getYearTotalDebitQuantity()));
            accumulatedCalculation(balanceDtoMap, balanceDto);
        }
    }

    private Set<LocalDate> createVoucher(List<VoucherNmyImportVo> voucherList, AccountSets ast, Map<String, SubjectVo> subjectVoMap, Long userId) {
        Map<VoucherTagVo, List<VoucherNmyImportVo>> voucherListMap = voucherList.stream().collect(Collectors.groupingBy(v -> new VoucherTagVo(v.getDate(), v.getWord(), v.getCode())));
        Map<Long, AccountingCategoryDto> accountingCategoryMap = categoryService.listByAccountSetsId(ast.getId()).stream().collect(Collectors.toMap(AccountingCategoryDto::getId, b -> b));
        CurrencyDto localCurrency = currencyService.fetchLocal(ast.getId());
        List<VoucherDto> voucherDtos = new ArrayList<>();
        // 已结账月份
        Set<LocalDate> cfDates = new HashSet<>();

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
            voucherDto.setCarryForward(StrUtil.contains(v.get(0).getDigest(), "结转") || StrUtil.contains(v.get(0).getDigest(), "损益"));

            if (voucherDto.getCarryForward()) {
                cfDates.add(tag.getDate().withDayOfMonth(1));
            }

            voucherDto.setYearMonth(tag.getDate());
            voucherDto.setIsTemplate(false);
            voucherDto.setLocked(false);
            voucherDto.setId(snowflakeId.generate());

            BigDecimal debitAmount = BigDecimal.ZERO;
            BigDecimal creditAmount = BigDecimal.ZERO;

            List<VoucherDetailsDto> details = new ArrayList<>();

            for (VoucherNmyImportVo d : v) {
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
                vd.setCreditNum(d.getCreditNum());
                vd.setDebitNum(d.getDebitNum());
                vd.setNum(Optional.ofNullable(d.getDebitNum()).orElse(d.getCreditNum()));
                vd.setPrice(Optional.ofNullable(d.getDebitPrice()).orElse(d.getCreditPrice()));
                vd.setCarryForward(voucherDto.getCarryForward());
                vd.setOriginalAmount(Optional.ofNullable(d.getDebitAmount()).orElse(d.getCreditAmount()));

                SubjectVo subjectVo = getSubjectVo(ast, subjectVoMap, d.getSubjectCode(), subjectVoMap.get(d.getSubjectCode()));

                vd.setSubjectId(subjectVo.getId());
                vd.setCurrencyId(localCurrency.getId());

                if (CollUtil.isNotEmpty(d.getAccountingCategoryDetails())) {
                    List<AcDetails> auxiliary = new ArrayList<>();
                    for (int i = 0; i < d.getAccountingCategoryDetails().size(); i++) {
                        AcDetails ad = d.getAccountingCategoryDetails().get(i);
                        Long aaId = (long) ad.getCategoryType().ordinal();
                        AccountingCategoryDto category = accountingCategoryMap.get(aaId);
                        AuxiliaryDataService dataService = SpringUtil.getBean(category.getCategoryType().dataService);

                        AcDetails acDetails = dataService.loadByName(ast.getId(), aaId, ad.getName());
                        if (acDetails == null) {
                            acDetails = new AcDetails();
                            acDetails.setId(snowflakeId.generate());
                            acDetails.setAccountSetsId(ast.getId());
                            acDetails.setCategoryType(category.getCategoryType());
                            acDetails.setCategoryId(category.getId());
                            acDetails.setCode(RandomUtil.randomNumbers(4));
                            acDetails.setName(ad.getName());
                            acDetails.setEnable(true);
                            dataService.save(BeanUtil.toBean(acDetails, category.getCategoryType().entity));
                        }
                        auxiliary.add(acDetails);
                    }
                    vd.setAuxiliary(auxiliary);
                }
                if (vd.getSummary().contains("结转损益")) {
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

        return cfDates;
    }
}
