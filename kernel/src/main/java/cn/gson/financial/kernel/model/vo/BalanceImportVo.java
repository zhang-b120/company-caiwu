package cn.gson.financial.kernel.model.vo;

import cn.hutool.core.annotation.Alias;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年02月27日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class BalanceImportVo {
    @Alias("科目编码")
    private String code;
    @Alias("科目名称")
    private String subjectName;
    @Alias("方向")
    private String balanceDirection;
    @Alias("辅助核算项目")
    private String auxiliary;
    @Alias("是否辅助核算")
    private String numAuxiliary;
    @Alias("辅助核算编码")
    private String auxiliaryCode;
    @Alias("辅助核算名称")
    private String auxiliaryName;
    @Alias("期初余额")
    private BigDecimal beginBalance;
    @Alias("借方累计")
    private BigDecimal yearTotalDebit;
    @Alias("贷方累计")
    private BigDecimal yearTotalCredit;
    @Alias("年初余额")
    private BigDecimal yearTotal;
    @Alias("期初余额金额")
    private BigDecimal numBeginBalance;
    @Alias("借方累计金额")
    private BigDecimal numYearTotalDebit;
    @Alias("贷方累计金额")
    private BigDecimal numYearTotalCredit;
    @Alias("年初余额金额")
    private BigDecimal numYearTotal;
    @Alias("期初余额数量")
    private BigDecimal beginQuantity;
    @Alias("借方累计数量")
    private BigDecimal yearTotalDebitQuantity;
    @Alias("贷方累计数量")
    private BigDecimal yearTotalCreditQuantity;
    @Alias("年初余额数量")
    private BigDecimal yearTotalQuantity;
    @Alias("辅助ID")
    private Long auxiliaryDetailId;
    @Alias("币别ID")
    private Long currencyId;

    public String getCode() {
        Assert.notEmpty(code, subjectName + "科目编码不能为空，请检查导入数据");
        return StrUtil.trim(code).split("_")[0];
    }

    public Boolean isNotEmptyCode() {
        return StrUtil.isNotEmpty(code);
    }

    public String getAuxiliary() {
        return StrUtil.emptyToDefault(auxiliary, numAuxiliary);
    }

    public BigDecimal getBeginBalance() {
        return Optional.ofNullable(beginBalance).orElse(numBeginBalance);
    }

    public BigDecimal getYearTotalCredit() {
        return Optional.ofNullable(yearTotalCredit).orElse(numYearTotalCredit);
    }

    public BigDecimal getYearTotalDebit() {
        return Optional.ofNullable(yearTotalDebit).orElse(numYearTotalDebit);
    }

    public BigDecimal getYearTotal() {
        return Optional.ofNullable(yearTotal).orElse(numYearTotal);
    }
}
