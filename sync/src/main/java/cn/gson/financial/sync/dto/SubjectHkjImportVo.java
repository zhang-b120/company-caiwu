package cn.gson.financial.sync.dto;

import cn.hutool.core.annotation.Alias;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年09月12日</li>
 * <li>@author     : 开发者</li>
 * </ul>科目编码	科目名称	方向	外币币种	数量核算	计量单位	部门	员工	项目	供应商	客户	存货
 * 
 */
@Data
public class SubjectHkjImportVo {
    @Alias("科目编码")
    private String code;

    @Alias("科目名称")
    private String name;

    @Alias("方向")
    private String balanceDirection;

    @Alias("外币币种")
    private String currency;

    @Alias("数量核算")
    private String auxiliaryAccounting;

    @Alias("计量单位")
    private String unit;

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


    public String getAuxiliaryAccounting() {
        List<String> auxs = new ArrayList<>();

        if (StrUtil.isNotEmpty(department)) {
            auxs.add("部门");
        }
        if (StrUtil.isNotEmpty(employee)) {
            auxs.add("职员");
        }
        if (StrUtil.isNotEmpty(project)) {
            auxs.add("项目");
        }
        if (StrUtil.isNotEmpty(vendor)) {
            auxs.add("供应商");
        }
        if (StrUtil.isNotEmpty(customer)) {
            auxs.add("客户");
        }
        if (StrUtil.isNotEmpty(stocks)) {
            auxs.add("存货");
        }

        return CollUtil.join(auxs, "/");
    }

    public String getCurrencyAccounting() {
        return this.currency;
    }

}
