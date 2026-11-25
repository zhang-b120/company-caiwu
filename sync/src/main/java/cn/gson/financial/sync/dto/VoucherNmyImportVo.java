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
 * </ul>日期	凭证号	行号	摘要	科目编码	科目名称	外币代码	借方数量	借方单价
 * 借方外币	借方汇率	借方本币	贷方数量	贷方单价	贷方外币	贷方汇率	贷方本币
 * 客户编码	客户名称	供应商编码	供应商名称	职员编码	职员名称	部门编码
 * 部门名称	项目编码	项目名称	存货编码	存货名称	现金流编码	现金流名称
 * 制单人	审核人	附件数	备注
 * 
 */
@Data
public class VoucherNmyImportVo {
    @Alias("凭证号")
    private String word;
    private int lineNum;
    private int code;
    @Alias("日期")
    private String date;
    @Alias("摘要")
    private String digest;
    @Alias("科目编码")
    private String subjectCode;
    @Alias("科目名称")
    private String subjectName;
    @Alias("外币代码")
    private String foreignCurrencyCode;
    @Alias("借方本币")
    private BigDecimal debitAmount;
    @Alias("借方数量")
    private BigDecimal debitNum;
    @Alias("借方单价")
    private BigDecimal debitPrice;
    @Alias("贷方本币")
    private BigDecimal creditAmount;
    @Alias("贷方数量")
    private BigDecimal creditNum;
    @Alias("贷方单价")
    private BigDecimal creditPrice;
    @Alias("部门编码")
    private String department;
    @Alias("员工编码")
    private String employee;
    @Alias("职员编码")
    private String employee2;
    @Alias("项目编码")
    private String project;
    @Alias("供应商编码")
    private String vendor;
    @Alias("客户编码")
    private String customer;
    @Alias("存货编码")
    private String stocks;
    @Alias("现金流编码")
    private String cashFlow;
    @Alias("部门名称")
    private String departmentName;
    @Alias("员工名称")
    private String employeeName;
    @Alias("职员名称")
    private String employeeName2;
    @Alias("项目名称")
    private String projectName;
    @Alias("供应商名称")
    private String vendorName;
    @Alias("客户名称")
    private String customerName;
    @Alias("存货名称")
    private String stocksName;
    @Alias("现金流名称")
    private String cashFlowName;
    @Alias("制单人")
    private String makingPeople;
    @Alias("审核人")
    private String auditor;

    public LocalDate getDate() {
        if (StrUtil.isNotEmpty(this.date)) {
            return DateUtil.parseDate(this.date).toLocalDateTime().toLocalDate();
        }
        throw new ServiceException("凭证：[" + digest + "]," + this.word + ",科目：" + this.subjectName + "，日期必填~");
    }

    public String getWord() {
        return StrUtil.split(word, "-").get(0);
    }

    public int getCode() {
        return Integer.parseInt(StrUtil.split(word, "-").get(1));
    }

    private List<AcDetails> accountingCategoryDetails = new ArrayList<>();

    public List<AcDetails> getAccountingCategoryDetails() {
        List<AcDetails> auxs = new ArrayList<>(accountingCategoryDetails);

        if (StrUtil.isNotEmpty(department)) {
            auxs.add(new AcDetails(departmentName, department, AccountingCategoryType.DEPARTMENT));
        }
        if (StrUtil.isNotEmpty(employee)) {
            auxs.add(new AcDetails(employeeName, employee, AccountingCategoryType.EMPLOYEE));
        } else if (StrUtil.isNotEmpty(employee2)) {
            auxs.add(new AcDetails(employeeName2, employee2, AccountingCategoryType.EMPLOYEE));
        }
        if (StrUtil.isNotEmpty(project)) {
            auxs.add(new AcDetails(projectName, project, AccountingCategoryType.PROJECT));
        }
        if (StrUtil.isNotEmpty(vendor)) {
            auxs.add(new AcDetails(vendorName, vendor, AccountingCategoryType.VENDOR));
        }
        if (StrUtil.isNotEmpty(customer)) {
            auxs.add(new AcDetails(customerName, customer, AccountingCategoryType.CUSTOMER));
        }
        if (StrUtil.isNotEmpty(stocks)) {
            auxs.add(new AcDetails(stocksName, stocks, AccountingCategoryType.STOCKS));
        }
        if (StrUtil.isNotEmpty(cashFlow)) {
            auxs.add(new AcDetails(cashFlowName, cashFlow, AccountingCategoryType.CASH_FLOW));
        }
        return auxs;
    }

    public void addAccountingCategoryDetails(AcDetails acDetails) {
        accountingCategoryDetails.add(acDetails);
    }
}
