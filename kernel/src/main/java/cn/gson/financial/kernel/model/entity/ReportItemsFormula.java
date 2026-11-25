package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.enums.AccessRules;
import cn.gson.financial.kernel.model.enums.FormulaSource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.entity</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年09月05日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class ReportItemsFormula {

    /**
     * 计算方式
     */
    private String calculation;

    /**
     * 取数规则：0,净发生额度 1,借方发生额 2,贷方发生额
     */
    private AccessRules accessRules;

    /**
     * 数据来源标识
     */
    private String fromTag;

    private FormulaSource source;
}
