package cn.gson.financial.sync;

import cn.gson.financial.kernel.common.PinYinUtil;
import cn.gson.financial.kernel.model.dto.AccountingCategoryDto;
import cn.gson.financial.kernel.model.dto.CurrencyDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountStandard;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.repo.CheckoutRepository;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.service.*;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.tree.Tree;
import cn.hutool.core.lang.tree.TreeUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.sync</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年02月28日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@RequiredArgsConstructor
public abstract class AbsImportService extends AbsService implements ImportService {

    protected final AccountSetsService accountSetsService;

    protected final CheckoutService checkoutService;

    protected final AccountingCategoryService categoryService;

    protected final CurrencyService currencyService;

    protected final QSubject qSubject = QSubject.subject;

    protected final CheckoutRepository checkoutRepository;

    protected AccountSets createImportAst(String companyName, VatType vatType, AccountStandard accountStandard, List<Integer> encoding, Long userId, LocalDate minDate, SyncType syncType, Long astId) {
        AccountSets as = new AccountSets();
        as.setCompanyName(companyName);
        as.setAccountStandard(accountStandard);
        as.setVatType(vatType);
        as.setEffectiveDate(LocalDate.now().plusYears(1000));
        as.setEncoding(encoding);
        as.setCreatorId(userId);
        as.setSubjectCategory(new ArrayList<>());
        as.setVoucherReviewed(false);
        as.setImported(true);
        as.setSyncType(syncType.name());
        as.setEnableDate(minDate);
        as.setCurrentAccountDate(minDate);
        as.setAstId(astId);
        accountSetsService.save(as, true);

        return as;
    }

    protected void createPeriods(AccountSets ast, List<LocalDate> localDates) {
        checkoutService.clear(ast.getId());
        List<Checkout> checkouts = new ArrayList<>();
        for (LocalDate localDate : localDates) {
            Checkout checkout = new Checkout();
            checkout.setCheckDate(localDate);
            checkout.setCheckMonth(localDate.getMonthValue());
            checkout.setCheckYear(localDate.getYear());
            checkout.setAccountSetsId(ast.getId());
            checkout.setStatus(0);
            checkouts.add(checkout);
        }

        if (!checkouts.isEmpty()) {
            checkoutRepository.saveAllAndFlush(checkouts);
            ast.setCurrentAccountDate(localDates.stream().max(LocalDate::compareTo).get());
            accountSetsService.update(ast);
        }
    }


    protected List<Long> getCategory(Long accountSetsId, String ca) {
        if (StrUtil.isEmpty(ca)) {
            return null;
        }
        List<String> cas = StrUtil.split(ca, "/");
        List<Long> categoryIds = new ArrayList<>();
        List<AccountingCategoryDto> list = categoryService.listByAccountSetsId(accountSetsId);
        if (list.isEmpty()) {
            for (String s : cas) {
                createCategory(accountSetsId, categoryIds, s); //创建
            }
        } else {
            Map<String, Long> nameMaps = list.stream()
                    .collect(Collectors.toMap(AccountingCategoryDto::getName, AccountingCategoryDto::getId));
            for (String s : cas) {
                if (nameMaps.containsKey(s)) {
                    categoryIds.add(nameMaps.get(s));
                } else {
                    createCategory(accountSetsId, categoryIds, s); //创建
                }
            }
        }
        return categoryIds;
    }

    protected void createCategory(Long accountSetsId, List<Long> categoryIds, String s) {
        AccountingCategory category = new AccountingCategory();
        category.setCategoryType(AccountingCategoryType.CUSTOMIZATION);
        category.setName(s);
        category.setId(snowflakeId.generate());
        category.setAccountSetsId(accountSetsId);
        categoryService.save(category);
        categoryIds.add(category.getId());
    }

    protected List<Long> getCurrency(Long accountSetsId, String value) {
        if (StrUtil.isEmpty(value)) {
            return null;
        }
        List<CurrencyDto> list = currencyService.list(accountSetsId);
        String[] cas = StrUtil.split(value, "/").toArray(new String[0]);
        List<Long> currencyIds = new ArrayList<>();
        if (list.isEmpty()) {
            //创建
            for (String s : cas) {
                createCurrency(accountSetsId, currencyIds, s); //创建
            }

        } else {
            Map<String, Long> nameMaps = list.stream().collect(Collectors.toMap(CurrencyDto::getCode, CurrencyDto::getId));
            for (String s : cas) {
                if (nameMaps.containsKey(s)) {
                    currencyIds.add(nameMaps.get(s));
                } else {
                    createCurrency(accountSetsId, currencyIds, s); //创建
                }
            }
        }
        return currencyIds;
    }


    protected void createCurrency(Long accountSetsId, List<Long> currencyIds, String s) {
        Currency currency = new Currency();
        currency.setName(s);
        currency.setLocalCurrency(false);
        currency.setCode(s);
        currency.setAccountSetsId(accountSetsId);
        currencyService.save(currency);
        currencyIds.add(currency.getId());
    }

    protected void updateSubjectLeafStatus(List<String> leafCodes, AccountSets ast, Map<String, Subject> subjectMap) {
        subjectMap.keySet().forEach(code -> {
            if (leafCodes.contains(code)) {
                List<String> list = subjectMap.keySet().parallelStream().filter(v -> !v.equals(code) && v.startsWith(code))
                        .toList();
                if (CollUtil.isNotEmpty(list)) {
                    jqf.update(qSubject)
                            .set(qSubject.leaf, false)
                            .where(qSubject.accountSetsId.eq(ast.getId())
                                    .and(qSubject.code.in(list)))
                            .execute();
                    jqf.update(qSubject)
                            .set(qSubject.leaf, true)
                            .where(qSubject.accountSetsId.eq(ast.getId())
                                    .and(qSubject.code.eq(code)))
                            .execute();
                }
            }
        });
    }

    /**
     * 获取连续日期列表
     *
     * @param min
     * @param max
     * @return
     */
    protected List<LocalDate> checkDateList(LocalDate min, LocalDate max) {
        if (min.isEqual(max)) {
            return CollUtil.newArrayList(min);
        }
        ArrayList<LocalDate> localDates = CollUtil.newArrayList(min);
        LocalDate next = min;
        do {
            next = next.plusMonths(1);
            localDates.add(next);
        } while (!next.isEqual(max));

        return localDates;
    }

    /**
     * 判断是否是末级节点
     * @param ast
     * @param subjectVoMap
     * @param subjectCode 原科目编码
     * @param subjectVo
     * @return
     */
    protected SubjectVo getSubjectVo(AccountSets ast, Map<String, SubjectVo> subjectVoMap, String subjectCode, SubjectVo subjectVo) {
        if (!subjectVo.getLeaf()) {
            String code = bqf.selectFrom(qSubject)
                    .select(qSubject.code)
                    .where(qSubject.accountSetsId.eq(ast.getId())
                            .and(qSubject.parentId.eq(subjectVo.getId())))
                    .orderBy(qSubject.code.desc())
                    .fetchFirst();
            if (StrUtil.isNotEmpty(code)) {
                SubjectVo children = BeanUtil.toBean(subjectVo, SubjectVo.class);
                children.setId(snowflakeId.generate());
                children.setParentId(subjectVo.getId());
                children.setFullName(subjectVo.getFullName() + "_" + children.getName());
                children.setCode((Long.parseLong(code) + 1) + "");
                children.setLeaf(true);
                children.setLevel(subjectVo.getLevel() + 1);
                children.setMnemonicCode(PinYinUtil.getFirstLettersLo(children.getFullName()));
                lazyDao.batchUpdate("batchInsertSubject", Arrays.asList(children));
                subjectVoMap.put(subjectCode, children);
                subjectVo = children;
            } else {
                subjectVo.setLeaf(true);
            }
        }
        return subjectVo;
    }

    protected void genFullName(List<SubjectVo> subjects) {
        List<Tree<Long>> build = TreeUtil.build(subjects, 0L, (subject, tree) -> {
            tree.setId(subject.getId());
            tree.setName(subject.getName());
            tree.setParentId(subject.getParentId());
            tree.putExtra("subject", subject);
        });
        buildFullName(build);
    }

    private void buildFullName(List<Tree<Long>> trees) {
        for (Tree<Long> tree : trees) {
            SubjectVo s = (SubjectVo) tree.get("subject");
            if (s.getCode().startsWith("1001") ||
                s.getCode().startsWith("1002") ||
                s.getCode().startsWith("1012")) {
                s.setCash(true);
            }
            Tree<Long> parent = tree.getParent();
            if (parent != null && parent.getId() != 0L) {
                SubjectVo p = (SubjectVo) parent.get("subject");
                s.setFullName(p.getFullName() + "_" + s.getName());
            } else {
                s.setFullName(tree.getName().toString());
            }
            s.setMnemonicCode(PinYinUtil.getFirstLettersLo(s.getFullName()));
            if (CollUtil.isNotEmpty(tree.getChildren())) {
                buildFullName(tree.getChildren());
            }
        }
    }
}
