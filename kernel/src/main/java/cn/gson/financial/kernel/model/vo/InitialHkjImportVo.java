package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.annotation.ColName;
import jakarta.persistence.Column;
import lombok.Data;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Comment;

import java.math.BigDecimal;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年08月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>科目编码	科目名称	辅助核算项	部门	员工	项目	供应商	客户	存货	规格型号	计量单位	方向
 * 期初数量	期初余额原币	期初余额本位币	本年借方累计数量	本年借方累计原币
 * 本年借方累计本位币	本年贷方累计数量	本年贷方累计原币	本年贷方累计本位币
 * 
 */
@Data
public class InitialHkjImportVo {
    @ColName("科目编码")
    private String code;
    @ColName("科目名称")
    private String name;
    @ColName("辅助核算")
    private String auxiliaryAccounting;
    @ColName("外币币种")
    private String currencyAccounting;
    @ColName("计量单位")
    private String unit;
    @ColName("部门")
    private String department;
    @ColName("员工")
    private String employee;
    @ColName("项目")
    private String project;
    @ColName("供应商")
    private String vendor;
    @ColName("客户")
    private String customer;
    @ColName("存货")
    private String stocks;
    @ColName("期初数量")
    private BigDecimal beginQuantity;
    @ColName("期初余额原币")
    private BigDecimal beginBalanceFor;
    @ColName("期初余额本位币")
    private BigDecimal beginBalance;
    @ColName("本年借方累计数量")
    private BigDecimal yearTotalDebitQuantity;
    @ColName("本年借方累计原币")
    private BigDecimal yearTotalDebitFor;
    @ColName("本年借方累计本位币")
    private BigDecimal yearTotalDebit;
    @ColName("本年贷方累计数量")
    private BigDecimal yearTotalCreditQuantity;
    @ColName("本年贷方累计原币")
    private BigDecimal yearTotalCreditFor;
    @ColName("本年贷方累计本位币")
    private BigDecimal yearTotalCredit;
}
