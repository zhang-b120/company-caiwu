package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.annotation.ColName;
import cn.hutool.core.util.StrUtil;
import lombok.Data;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年09月12日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
public class SubjectImportVo {
    @ColName("科目编码")
    private String code;
    @ColName("科目名称")
    private String name;
    @ColName("科目类别")
    private String type;
    @ColName("类别")
    private String type2;
    @ColName("余额方向")
    private String balanceDirection;
    @ColName("数量核算")
    private String unit;
    @ColName("辅助核算")
    private String auxiliaryAccounting;
    @ColName("外币核算")
    private String currencyAccounting;
    @ColName("期末调汇")
    private String adjustments;

    public String getType() {
        return StrUtil.emptyToDefault(type, type2);
    }
}
