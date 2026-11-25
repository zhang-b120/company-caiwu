package cn.gson.financial.sync.dto;

import cn.gson.financial.kernel.annotation.ColName;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.entity.AcDetails;
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
 * </ul>
 * 
 */
@Data
public class VoucherJdyImportVo {
    @ColName("日期")
    private String date;
    @ColName("凭证字号")
    private String voucherNumber;
    @ColName("摘要")
    private String digest;
    @ColName("科目")
    private String subjectTitle;
    @ColName("外币代码")
    private String foreignCurrencyCode;
    @ColName("数量")
    private BigDecimal number;
    @ColName("单价")
    private BigDecimal price;
    @ColName("币别")
    private String coins;
    @ColName("原币金额")
    private BigDecimal originalCurrencyAmount;
    @ColName("汇率")
    private BigDecimal rate;

    @ColName("借方金额")
    private BigDecimal debitAmount;

    @ColName("贷方金额")
    private BigDecimal creditAmount;

    @ColName("制单人")
    private String makingPeople;

    @ColName("审核人")
    private String auditor;

    public LocalDate getDate() {
        if (StrUtil.isNotEmpty(this.date)) {
            return DateUtil.parseDate(this.date).toLocalDateTime().toLocalDate();
        }
        throw new ServiceException(this.voucherNumber + ",科目：" + this.subjectTitle + "，日期必填~");
    }

    private List<AcDetails> accountingCategoryDetails = new ArrayList<>();

    public String getWord() {
        return StrUtil.split(this.voucherNumber, '-').get(0);
    }

    public int getCode() {
        return Integer.parseInt(StrUtil.split(this.voucherNumber, '-').get(1));
    }

    public String getSubjectCode() {
        String[] s = this.getSubjectTitle().split(" ");
        List<String> codeSp = StrUtil.split(s[0], '_', 2);
        return codeSp.get(0);
    }

    public List<AcDetails> getAccountingCategoryDetails() {
        String[] s = this.getSubjectTitle().split(" ");
        List<String> codeSp = StrUtil.split(s[0], '_', 2);
        List<String> nameSp = StrUtil.split(s[1], '_', 2);
        if (codeSp.size() > 1) {
            List<AcDetails> acDetails = new ArrayList<>();
            for (int i = 0; i < codeSp.size(); i++) {
                AcDetails ad = new AcDetails();
                ad.setCode(codeSp.get(i));
                ad.setName(nameSp.get(i));
                acDetails.add(ad);
            }
            return acDetails;
        }

        return accountingCategoryDetails;
    }
}
