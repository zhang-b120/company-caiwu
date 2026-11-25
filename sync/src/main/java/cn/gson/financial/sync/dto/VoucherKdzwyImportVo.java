package cn.gson.financial.sync.dto;

import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.hutool.core.annotation.Alias;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

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
 * </ul>
 * 
 */
@Setter
@Getter
@Slf4j
@ToString
public class VoucherKdzwyImportVo {
    @Alias("日期")
    private String date;
    @Alias("凭证字")
    private String word;
    @Alias("凭证号")
    private int code;
    @Alias("摘要")
    private String digest;
    @Alias("科目名称")
    private String subjectTitle;
    @Alias("科目代码")
    private String subjectCode;
    @Alias("外币")
    private String foreignCurrencyCode;
    @Alias("部门")
    private String department;
    @Alias("职员")
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
    @Alias("单价")
    private BigDecimal price;
    @Alias("币别")
    private String coins;
    @Alias("自定义辅助核算类别")
    private String customizeAccType;
    @Alias("自定义辅助核算编码")
    private String customizeAccCode;

    @Alias("借方金额")
    private BigDecimal debitAmount;

    @Alias("贷方金额")
    private BigDecimal creditAmount;

    @Alias("原币金额")
    private BigDecimal originalAmount;

    @Alias("汇率")
    private BigDecimal exchangeRate;

    @Alias("附件数")
    private Integer receiptNum;

    @Alias("制单人")
    private String makingPeople;

    @Alias("审核人")
    private String auditor;

    public String getVoucherNumber() {
        return this.word + "-" + this.code;
    }

    public LocalDate getDate() {
        if (StrUtil.isNotEmpty(this.date)) {
            return DateUtil.parseDate(this.date).toLocalDateTime().toLocalDate();
        }
        log.error("\n金蝶云代账凭证导入行信息：\n{}", this);
        throw new ServiceException(this.getVoucherNumber() + ",科目：" + this.subjectTitle + "，日期必填~");
    }

    public List<AcDetails> getAccountingCategoryDetails() {
        List<AcDetails> auxs = new ArrayList<>();

        List<String> nameSp = StrUtil.split(this.subjectTitle, "_");

        if (StrUtil.isNotEmpty(customer)) {
            auxs.add(new AcDetails(null, customer, AccountingCategoryType.CUSTOMER));
        }

        if (StrUtil.isNotEmpty(vendor)) {
            auxs.add(new AcDetails(null, vendor, AccountingCategoryType.VENDOR));
        }

        if (StrUtil.isNotEmpty(employee)) {
            auxs.add(new AcDetails(null, employee, AccountingCategoryType.EMPLOYEE));
        }

        if (StrUtil.isNotEmpty(project)) {
            auxs.add(new AcDetails(null, project, AccountingCategoryType.PROJECT));
        }

        if (StrUtil.isNotEmpty(department)) {
            auxs.add(new AcDetails(null, department, AccountingCategoryType.DEPARTMENT));
        }

        if (StrUtil.isNotEmpty(stocks)) {
            auxs.add(new AcDetails(null, stocks, AccountingCategoryType.STOCKS));
        }

        if (StrUtil.isNotEmpty(customizeAccCode)) {
            AcDetails acDetails = new AcDetails(null, customizeAccCode, AccountingCategoryType.CUSTOMER);
            acDetails.setCategoryName(customizeAccType);
            auxs.add(acDetails);
        }

        if (CollUtil.isNotEmpty(auxs)) {
            int start = nameSp.size() - auxs.size();
            for (AcDetails aux : auxs) {
                aux.setName(nameSp.get(start));
                start++;
            }
        }

        return auxs;
    }
}
