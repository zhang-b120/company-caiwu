package cn.gson.financial.sync.service;

import cn.gson.financial.kernel.common.PinYinUtil;
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
import cn.gson.financial.sync.dto.SubjectImportMappingDto;
import cn.gson.financial.sync.dto.SubjectKdzwyImportVo;
import cn.gson.financial.sync.dto.VoucherKdzwyImportVo;
import cn.gson.financial.sync.dto.VoucherTagVo;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import com.alibaba.fastjson.JSONArray;
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
 * <li>Description : cn.gson.financial.sync.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年11月13日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Service
@Slf4j
public class SyncKdZwyService extends AbsImportService {
    private final BalanceService balanceService;

    private final BalanceInitialService balanceInitialService;

    private final VoucherService voucherService;

    private final QSubject qSubject = QSubject.subject;

    private final SubjectMapper subjectMapper;

    private final SubjectService subjectService;

    public SyncKdZwyService(AccountSetsService accountSetsService, VoucherService voucherService,
                            CheckoutService checkoutService, AccountingCategoryService categoryService,
                            CurrencyService currencyService, BalanceService balanceService, BalanceInitialService balanceInitialService,
                            CheckoutRepository checkoutRepository, SubjectMapper subjectMapper, SubjectService subjectService) {
        super(accountSetsService, checkoutService, categoryService, currencyService, checkoutRepository);
        this.subjectMapper = subjectMapper;
        this.balanceService = balanceService;
        this.balanceInitialService = balanceInitialService;
        this.voucherService = voucherService;
        this.subjectService = subjectService;
    }


    private Map<String, SubjectVo> createSubject(List<SubjectKdzwyImportVo> subjectList, AccountSets ast, List<SubjectImportMappingDto> subjectMappings) {
        int encLen = ast.getEncoding().stream().reduce(Integer::sum).orElse(0);
        //原有科目结构
        Map<String, Subject> subjectMap = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(ast.getId()))
                .fetch().stream().collect(Collectors.toMap(Subject::getCode, s -> s));

        //导入科目结构
        Map<String, SubjectKdzwyImportVo> importMap = subjectList.stream().collect(Collectors.toMap(SubjectKdzwyImportVo::getCode, s -> s));

        Map<String, SubjectVo> subjectVoMap = new HashMap<>();
        Map<String, SubjectVo> subjectVoSysMap = new HashMap<>();

        List<SubjectVo> subjectVos = new ArrayList<>();

        for (SubjectImportMappingDto mapping : subjectMappings) {
            if (StrUtil.isNotEmpty(mapping.getMatchedCode())) {
                SubjectKdzwyImportVo item = importMap.get(mapping.getMatchedCode());
                SubjectVo vo;
                if (mapping.getIsNew()) {
                    // 获取导入科目信息
                    vo = new SubjectVo();
                    vo.setId(snowflakeId.generate());
                    vo.setType(this.jdyType(StrUtil.trim(item.getType())));
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
                if (StrUtil.isNotBlank(item.getCurrencyAccounting())) {
                    vo.setCurrencyAccountingStr(JSON.toJSONString(this.getCurrency(ast.getId(), StrUtil.trim(item.getCurrencyAccounting()))));
                }
                vo.setAdjustments(item.getAdjustments());
                subjectVos.add(vo);
                subjectVoMap.put(mapping.getMatchedCode(), vo);
                subjectVoSysMap.put(mapping.getCode(), vo);
            }
        }

        genFullName(subjectVos);
        lazyDao.batchUpdate("batchInsertSubject", subjectVos);
        List<String> leafCodes = subjectList.parallelStream().filter(vo -> subjectList.parallelStream()
                        .noneMatch(v -> !v.getCode().equals(vo.getCode()) && v.getCode().startsWith(vo.getCode())))
                .map(SubjectKdzwyImportVo::getCode).toList();
        this.updateSubjectLeafStatus(leafCodes, ast, subjectMap);
        return subjectVoMap;
    }


    private String jdyType(String type) {
        return switch (type) {
            case "流动资产", "非流动资产" -> "资产";
            case "流动负债", "非流动负债" -> "负债";
            case "所有者权益" -> "权益";
            case "成本" -> "成本";
            default -> "损益";
        };
    }

    private void initialBalance(List<List<Object>> balanceInitialList, AccountSets ast, Map<String, SubjectVo> subjectVoMap) {
        //数量核算
        boolean isUnit = subjectVoMap.values().stream().anyMatch(s -> StrUtil.isNotEmpty(s.getUnit()));
        //外币核算
        boolean isCurrency = subjectVoMap.values().stream().anyMatch(s -> StrUtil.isNotEmpty(s.getCurrencyAccountingStr()));

        Map<Long, AccountingCategoryDto> accountingCategoryMap = categoryService.listByAccountSetsId(ast.getId()).stream().collect(Collectors.toMap(AccountingCategoryDto::getId, b -> b));

        List<BalanceInitialDto> details = new ArrayList<>();
        CurrencyDto localCurrency = currencyService.fetchLocal(ast.getId());
        for (List<Object> list : balanceInitialList) {
            JSONArray row = new JSONArray(list);
            //辅助核算项目
            BalanceInitialDto balance = new BalanceInitialDto();
            balance.setAccountSetsId(ast.getId());
            balance.setCurrencyId(localCurrency.getId());
            String subjectCode = row.getString(0);
            String subjectName = row.getString(1);
            String[] codeSp = subjectCode.split("_", 2);
            String[] nameSp = subjectName.split("_", 2);

            subjectCode = codeSp[0];
            SubjectVo vo = subjectVoMap.get(subjectCode);
            Assert.notNull(vo, subjectCode + "科目不存在,请检查~");
            balance.setSubjectId(vo.getId());
            if (codeSp.length > 1) { //存在 辅助项
                String[] codes = codeSp[1].split("_");
                String[] names = nameSp[1].split("_");

                //创建 辅助项 明细
                BalanceAuxiliaryForm baf = new BalanceAuxiliaryForm();
                baf.setSubjectId(vo.getId());
                baf.setCurrencyId(balance.getCurrencyId());
                List<AuxiliaryDetail.Detail> des = new ArrayList<>();
                for (int i = 0; i < codes.length; i++) {
                    Long aaId = vo.getAuxiliaryAccounting().get(i);
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
                        acDetails.setName(names[i]);
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
            if (isUnit) {
                BigDecimal beginQuantity = BigDecimal.ZERO, beginBalance = BigDecimal.ZERO;
                //期初余额  数量 金额
                if (StrUtil.isNotEmpty(row.getString(3).trim())) {
                    beginQuantity = row.getBigDecimal(3);
                }
                if (StrUtil.isNotEmpty(row.getString(4).trim())) {
                    beginBalance = row.getBigDecimal(4);
                }
                String balanceDirection = row.getString(2);
                if (!vo.getBalanceDirection().equals(balanceDirection)) {
                    balance.setBeginBalance(NumberUtil.sub(BigDecimal.ZERO, beginBalance));
                } else {
                    balance.setBeginBalance(beginBalance);
                }
                balance.setBeginQuantity(beginQuantity);
                balance.setYearTotalQuantity(beginQuantity);
                balance.setYearTotal(beginBalance);
            } else {
                BigDecimal beginBalance = BigDecimal.ZERO;
                //期初余额  数量 金额
                if (StrUtil.isNotEmpty(row.getString(3).trim())) {
                    beginBalance = row.getBigDecimal(3);
                }
                String balanceDirection = row.getString(2);
                if (!vo.getBalanceDirection().equals(balanceDirection)) {
                    balance.setBeginBalance(NumberUtil.sub(BigDecimal.ZERO, beginBalance));
                } else {
                    balance.setBeginBalance(beginBalance);
                }
                balance.setYearTotal(beginBalance);
            }

            details.add(balance);
        }
        balanceInitialService.updateInitBalance(details, ast, subjectVoMap);
    }

    private void createVoucher(List<VoucherKdzwyImportVo> voucherList, AccountSets ast, Map<String, SubjectVo> subjectVoMap, Long userId) {
        Map<VoucherTagVo, List<VoucherKdzwyImportVo>> voucherListMap = voucherList.stream().collect(Collectors.groupingBy(v -> new VoucherTagVo(v.getDate(), v.getWord(), v.getCode())));

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

            for (VoucherKdzwyImportVo d : v) {
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

                vd.setExchangeRate(d.getExchangeRate());
                vd.setOriginalAmount(d.getOriginalAmount());
                if (d.getOriginalAmount() != null) {
                    if (d.getDebitAmount() != null) {
                        vd.setDebitAmountFor(d.getOriginalAmount());
                    } else {
                        vd.setCreditAmountFor(d.getOriginalAmount());
                    }
                }

                if (CollUtil.isNotEmpty(d.getAccountingCategoryDetails())) {
                    List<AcDetails> auxiliary = new ArrayList<>();
                    for (int i = 0; i < subjectVo.getAuxiliaryAccounting().size(); i++) {
                        Long aaId = subjectVo.getAuxiliaryAccounting().get(i);
                        AccountingCategoryDto category = accountingCategoryMap.get(aaId);
                        AuxiliaryDataService dataService = SpringUtil.getBean(category.getCategoryType().dataService);

                        AcDetails acDetails = dataService.loadByCode(ast.getId(), aaId, d.getAccountingCategoryDetails().get(i).getCode());
                        if (acDetails == null) {
                            acDetails = new AcDetails();
                            acDetails.setId(snowflakeId.generate());
                            acDetails.setAccountSetsId(ast.getId());
                            acDetails.setCategoryType(category.getCategoryType());
                            acDetails.setCategoryId(category.getId());
                            acDetails.setCode(d.getAccountingCategoryDetails().get(i).getCode());
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

                if (vd.getSummary().contains("结转本期损益") || vd.getSummary().contains("结转本年利润")) {
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
        ExcelReader reader = ExcelUtil.getReader(fileKm.getInputStream());
        List<SubjectKdzwyImportVo> subjectRows = reader.readAll(SubjectKdzwyImportVo.class);
        Assert.isFalse(subjectRows.isEmpty(), "导入科目数据为空");

        //数量核算
        boolean isUnit = subjectRows.stream().anyMatch(s -> StrUtil.isNotEmpty(s.getUnit()));

        //读取凭证Excel数据
        ExcelReader pzReader = ExcelUtil.getReader(filePz.getInputStream());
        List<VoucherKdzwyImportVo> voucherList = pzReader.readAll(VoucherKdzwyImportVo.class);
        Assert.isFalse(voucherList.isEmpty(), "导入凭证数据为空");

        List<LocalDate> localDates = voucherList.stream().map(v -> v.getDate().withDayOfMonth(1))
                .distinct().toList();

        //获取账套启用日期
        LocalDate minDate = CollUtil.min(localDates);
        LocalDate maxDate = CollUtil.max(localDates);

        //检查最大的编码是否符合 编码设置
        Optional<String> maxCode = subjectRows.stream().map(SubjectKdzwyImportVo::getCode).toList().stream().max(Comparator.comparingInt(String::length));
        SubjectUtil.checkCode(maxCode.get(), encodings);

        //1、创建账套
        AccountSets ast = this.createImportAst(companyName, vatType, accountStandard, encodings, userId, minDate, soft, astId);

        //2、科目
        Map<String, SubjectVo> subjectVos = this.createSubject(subjectRows, ast, subjectMappings);

        if (fileQc != null) {
            //读取期初Excel数据
            ExcelReader qcReader = ExcelUtil.getReader(fileQc.getInputStream(), 0);
            List<List<Object>> balanceInitialList = qcReader.read(isUnit ? 2 : 1, Integer.MAX_VALUE);
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
