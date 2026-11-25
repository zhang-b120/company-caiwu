package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.annotation.ColName;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.ToString;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年03月22日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
@ToString
public class JournalImportVo {
    @ColName("摘要")
    @NotEmpty(message = "摘要必须填写")
    private String digest;

    @ColName("日期")
    @NotNull(message = "日期不能为空")
    private LocalDate billDate;

    @ColName("收支类别编码")
    @NotEmpty(message = "收支类别编码必须填写")
    private String ioTypeCode;

    @ColName("收支类型")
    @NotEmpty(message = "收支类型必须填写")
    @Pattern(regexp = "[收支]", message = "收支类型必须填写(收或支)")
    private String type;

    @ColName("收入（借方）")
    private BigDecimal debit;

    @ColName("支出（贷方）")
    private BigDecimal credit;

    @ColName("备注")
    private String remark;

    @ColName("票据号")
    private String billNo;

    @ColName("对方科目")
    private String subjectCode;

    @ColName("交易附言")
    private String transactionPostscript;

    @ColName("对方户名")
    private String oppositeAccountName;

    public BigDecimal getCredit() {
        if ("支".equals(type)) {
            if (credit != null && credit.compareTo(BigDecimal.ZERO) != 0) {
                return credit;
            }
            return debit;
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal getDebit() {
        if ("收".equals(type)) {
            if (debit != null && debit.compareTo(BigDecimal.ZERO) != 0) {
                return debit;
            }
            return credit;
        }
        return BigDecimal.ZERO;
    }
}
