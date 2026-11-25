package cn.gson.financial.kernel.model.vo;

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
public class VoucherImportVo {
    @ColName("日期")
    private String date;
    @ColName("凭证号")
    private String voucherNumber;
    @ColName("摘要")
    private String digest;
    @ColName("科目编码")
    private String subjectCode;
    @ColName("科目名称")
    private String subjectTitle;
    @ColName("外币代码")
    private String foreignCurrencyCode;
    @ColName("借方数量")
    private BigDecimal debitNumber;
    @ColName("借方外币")
    private BigDecimal debitForeignCurrency;
    @ColName("借方本币")
    private BigDecimal debitCurrency;
    @ColName("贷方数量")
    private BigDecimal creditNumber;
    @ColName("贷方外币")
    private BigDecimal creditForeignCurrency;
    @ColName("贷方本币")
    private BigDecimal creditCurrency;
    @ColName("制单人")
    private String makingPeople;
    @ColName("审核人")
    private String auditor;
    @ColName("附件数")
    private Integer attachmentNum;
    @ColName("备注")
    private String remark;

    public LocalDate getDate() {
        if (StrUtil.isNotEmpty(this.date)) {
            try {
                return DateUtil.parseDate(this.date).toLocalDateTime().toLocalDate();
            } catch (Exception e) {
                throw new ServiceException(this.voucherNumber + ",科目：" + this.subjectTitle + "，日期值[" + this.date + "]错误~");
            }
        }
        throw new ServiceException(this.voucherNumber + ",科目：" + this.subjectTitle + "，日期必填~");
    }

    private List<AcDetails> accountingCategoryDetails = new ArrayList<>();
}
