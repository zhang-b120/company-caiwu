package cn.gson.financial.sync.dto;

import cn.hutool.core.annotation.Alias;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.sync.dto</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年09月13日</li>
 * <li>@author     : 开发者</li>
 * 科目编码	科目名称	辅助核算项	部门	员工	项目	供应商	客户	存货	规格型号	计量单位	方向	期初数量	期初余额原币	期初余额本位币	本年借方累计数量	本年借方累计原币	本年借方累计本位币	本年贷方累计数量	本年贷方累计原币	本年贷方累计本位币
 * </ul>
 * 
 */
@Data
public class BalanceInitiaHkjImportVo {

    @Alias("科目编码")
    private String code;

    @Alias("科目名称")
    private String name;

    @Alias("辅助核算项")
    private String auxiliaryAccountingItems;

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

    @Alias("计量单位")
    private String unit;

    @Alias("方向")
    private String balanceDirection;

    @Alias("期初数量")
    private BigDecimal beginQuantity;

    @Alias("期初余额原币")
    private BigDecimal originalCurrency;

    @Alias("期初余额本位币")
    private BigDecimal beginBalance;

    @Alias("本年借方累计数量")
    private BigDecimal yearTotalDebitQuantity;

    @Alias("本年借方累计原币")
    private BigDecimal yearTotalDebitFor;

    @Alias("本年借方累计本位币")
    private BigDecimal yearTotalDebit;

    @Alias("本年贷方累计数量")
    private BigDecimal yearTotalCreditQuantity;

    @Alias("本年贷方累计原币")
    private BigDecimal yearTotalCreditFor;

    @Alias("本年贷方累计本位币")
    private BigDecimal yearTotalCredit;

    /**
     * 借方科目：年初数 = 期初数 + 本年累计贷方 - 本年累计借方
     * 贷方科目：年初数 = 期初数 + 本年累计借方 - 本年累计贷方
     *
     * @return
     */
    public BigDecimal getYearTotal() {
        switch (balanceDirection) {
            case "借" -> {
                return NumberUtil.add(this.beginBalance, NumberUtil.sub(this.yearTotalCredit, this.yearTotalDebit));
            }
            case "贷" -> {
                return NumberUtil.add(this.beginBalance, NumberUtil.sub(this.yearTotalDebit, this.yearTotalCredit));
            }
        }
        return BigDecimal.ZERO;
    }

    public String getCode() {
        return code.split("\\.", 2)[0];
    }

    public String[] getCodeSp() {
        String[] split = code.split("\\.", 2);
        if (split.length > 1) {
            return split[1].split(",");
        }
        return new String[0];
    }

    public List<String> getNameSp() {
        List<String> auxs = new ArrayList<>();

        if (StrUtil.isNotEmpty(department)) {
            auxs.add(StrUtil.split(department, '_', 2).get(1));
        }
        if (StrUtil.isNotEmpty(employee)) {
            auxs.add(StrUtil.split(employee, '_', 2).get(1));
        }
        if (StrUtil.isNotEmpty(project)) {
            auxs.add(StrUtil.split(project, '_', 2).get(1));
        }
        if (StrUtil.isNotEmpty(vendor)) {
            auxs.add(StrUtil.split(vendor, '_', 2).get(1));
        }
        if (StrUtil.isNotEmpty(customer)) {
            auxs.add(StrUtil.split(customer, '_', 2).get(1));
        }
        if (StrUtil.isNotEmpty(stocks)) {
            auxs.add(StrUtil.split(stocks, '_', 2).get(1));
        }
        return auxs;
    }
}
