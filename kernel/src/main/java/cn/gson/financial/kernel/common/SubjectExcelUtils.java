package cn.gson.financial.kernel.common;

import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.entity.AccountingCategory;
import cn.gson.financial.kernel.model.entity.Currency;
import cn.gson.financial.kernel.model.enums.SubjectCategory;
import cn.gson.financial.kernel.model.mapper.AccountSetsTemplateMapper;
import cn.gson.financial.kernel.model.vo.SubjectImportVo;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.service.AccountingCategoryService;
import cn.gson.financial.kernel.service.CurrencyService;
import cn.gson.financial.kernel.tool.ImportVoUtil;
import cn.gson.financial.kernel.tool.SubjectUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.common</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年10月18日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class SubjectExcelUtils {
    private final AccountSetsTemplateMapper subjectTemplateMapper;
    private final AccountingCategoryService categoryService;
    private final CurrencyService currencyService;

    /**
     * 读取excel数据并转化
     *
     * @param is
     * @param encoding
     * @param subjectCategory
     * @param accountSetsId
     * @return
     * @throws IOException
     */
    public List<SubjectVo> readExcel(InputStream is, List<Integer> encoding, List<SubjectCategory> subjectCategory, Long accountSetsId) {
        List<String> subjectCategoryDisplay = subjectCategory.stream().map(it -> it.display).toList();
        ExcelReader reader = ExcelUtil.getReader(is);
        ImportVoUtil.setHeaderAlias(SubjectImportVo.class, reader);
        List<SubjectImportVo> rows = reader.readAll(SubjectImportVo.class);
        if (!rows.isEmpty()) {
            List<SubjectVo> list = new ArrayList<>();
            Map<String, SubjectVo> subjectVoMap = new HashMap<>();
            rows.forEach(item -> {
                SubjectVo subject = new SubjectVo();
                subject.setSystemDefault(true);
                subject.setChildren(new ArrayList<>());
                subject.setType(StrUtil.replaceLast(item.getType(), "类", ""));
                subject.setCode(StrUtil.trim(item.getCode()));
                subject.setName(StrUtil.trim(item.getName()));
                subject.setBalanceDirection(StrUtil.trim(item.getBalanceDirection()));
                subject.setStatus(true);
                subject.setLeaf(true);
                subject.setFullName(subject.getName());
                subject.setMnemonicCode(PinYinUtil.getFirstLettersLo(subject.getName()));
                subject.setUnit(StrUtil.trim(item.getUnit()));
                subject.setAccountSetsId(accountSetsId);
                subject.setAuxiliaryAccounting(this.getCategory(accountSetsId, StrUtil.trim(item.getAuxiliaryAccounting())));
                subject.setCurrencyAccounting(this.getCurrency(accountSetsId, StrUtil.trim(item.getCurrencyAccounting())));
                subject.setMnemonicCode(PinYinUtil.getFirstLettersLo(subject.getName()));
                Assert.notEmpty(subject.getType(), "科目类型错误,请确认类型列列头为'科目类别'");
                if (!subjectCategoryDisplay.contains(subject.getType())) {
                    throw new ServiceException("只允许导入科目类别：" + CollUtil.join(subjectCategoryDisplay, "，") + "，当前类型[" + subject.getType() + "]");
                }

                if (!"借".equals(subject.getBalanceDirection()) && !"贷".equals(subject.getBalanceDirection())) {
                    throw new ServiceException("编码[" + subject.getCode() + "]的余额方向错误。正确值为：借、贷");
                }

                String code = subject.getCode();
                //编码格式检查
                List<String> codePath = SubjectUtil.checkCode(code, encoding);
                subject.setLevel(codePath.size());
                subjectVoMap.put(subject.getCode(), subject);

                //顶级科目和子集科目分开存储
                if (codePath.size() > 1) {
                    String parentCode = CollUtil.join(codePath.subList(0, codePath.size() - 1), "");
                    SubjectVo parent = subjectVoMap.get(parentCode);
                    System.out.println(parentCode);
                    parent.setLeaf(false);
                    subject.setFullName(parent.getFullName() + "_" + subject.getName());
                    subject.setMnemonicCode(PinYinUtil.getFirstLettersLo(subject.getFullName()));
                    parent.getChildren().add(subject);
                } else {
                    list.add(subject);
                }
            });

            return list;
        } else {
            throw new ServiceException("导入数据为空~");
        }
    }

    private List<Long> getCurrency(Long accountSetsId, String value) {
        if (StringUtils.isEmpty(value)) {
            return null;
        }
        List<Currency> list = currencyService.listByName(accountSetsId, StrUtil.split(value, "/"));
        if (list.isEmpty()) {
            throw new ServiceException("外币类型：[" + value + "]在账套中不存在！");
        }
        return list.stream().map(Currency::getId).collect(Collectors.toList());
    }

    private List<Long> getCategory(Long accountSetsId, String ca) {
        if (StringUtils.isEmpty(ca)) {
            return null;
        }
        List<AccountingCategory> list = categoryService.listByName(accountSetsId, StrUtil.split(ca, "/"));
        if (list.isEmpty()) {
            throw new ServiceException("辅助项：[" + ca + "]在账套中不存在！");
        }
        return list.stream().map(AccountingCategory::getId).collect(Collectors.toList());
    }
}
