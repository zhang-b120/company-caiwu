package cn.gson.financial.sync.dto;

import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.hutool.core.annotation.Alias;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月03日</li>
 * <li>@author     : 开发者</li>
 * </ul>凭证类别	凭证号	凭证日期	附单据数	摘要
 * 科目编码	科目名称	借方金额	贷方金额
 * 客户	供应商	部门	员工	存货	项目
 * 数量	 计量单位	单价	外币金额	币种	汇率	制单人	审核人
 * 
 */
@Data
public class VoucherHkjImportVo {
    @Alias("凭证类别")
    private String word;
    @Alias("凭证号")
    private int code;
    @Alias("凭证日期")
    private String date;
    @Alias("摘要")
    private String digest;
    @Alias("科目编码")
    private String subjectCode;
    @Alias("科目名称")
    private String subjectName;
    @Alias("借方金额")
    private BigDecimal debitAmount;
    @Alias("贷方金额")
    private BigDecimal creditAmount;
    @Alias("部门")
    private String department;
    @Alias("员工")
    private String employee;
    @Alias("项目")
    private String project;
    @Alias("供应商")
    private String vendor;
    @Alias("客户")
    private String customer;
    @Alias("存货")
    private String stocks;
    @Alias("数量")
    private BigDecimal number;
    @Alias("计量单位")
    private String unit;
    @Alias("单价")
    private BigDecimal price;
    @Alias("外币金额")
    private BigDecimal foreignCurrencyAmount;
    @Alias("币种")
    private String foreignCurrencyCode;
    @Alias("汇率")
    private BigDecimal rate;
    @Alias("制单人")
    private String makingPeople;
    @Alias("审核人")
    private String auditor;

    public LocalDate getDate() {
        if (StrUtil.isNotEmpty(this.date)) {
            return DateUtil.parseDate(this.date).toLocalDateTime().toLocalDate();
        }
        throw new ServiceException("凭证：[" + digest + "]," + this.code + ",科目：" + this.subjectName + "，日期必填~");
    }

    private List<AcDetails> accountingCategoryDetails = new ArrayList<>();

    public List<AcDetails> getAccountingCategoryDetails() {
        List<AcDetails> auxs = new ArrayList<>();

        if (StrUtil.isNotEmpty(department)) {
            auxs.add(new AcDetails(StrUtil.split(department, '_', 2).get(1), AccountingCategoryType.DEPARTMENT));
        }
        if (StrUtil.isNotEmpty(employee)) {
            auxs.add(new AcDetails(StrUtil.split(employee, '_', 2).get(1), AccountingCategoryType.EMPLOYEE));
        }
        if (StrUtil.isNotEmpty(project)) {
            auxs.add(new AcDetails(StrUtil.split(project, '_', 2).get(1), AccountingCategoryType.PROJECT));
        }
        if (StrUtil.isNotEmpty(vendor)) {
            auxs.add(new AcDetails(StrUtil.split(vendor, '_', 2).get(1), AccountingCategoryType.VENDOR));
        }
        if (StrUtil.isNotEmpty(customer)) {
            auxs.add(new AcDetails(StrUtil.split(customer, '_', 2).get(1), AccountingCategoryType.CUSTOMER));
        }
        if (StrUtil.isNotEmpty(stocks)) {
            auxs.add(new AcDetails(StrUtil.split(stocks, '_', 2).get(1), AccountingCategoryType.STOCKS));
        }
        return auxs;
    }
}
