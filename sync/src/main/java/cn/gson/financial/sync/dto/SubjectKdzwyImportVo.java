package cn.gson.financial.sync.dto;

import cn.hutool.core.annotation.Alias;
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
public class SubjectKdzwyImportVo {
    @Alias("编码")
    private String code;
    @Alias("名称")
    private String name;
    @Alias("类别")
    private String type;
    @Alias("余额方向")
    private String balanceDirection;
    @Alias("辅助核算")
    private String auxiliaryAccounting;
    @Alias("外币")
    private String currencyAccounting;
    @Alias("数量")
    private String unit;
    @Alias("是否调汇")
    private String adjustments;
    @Alias("状态")
    private String status;

    public boolean getStatus() {
        return "已启用".equals(status);
    }

    public boolean getAdjustments() {
        return "是".equals(adjustments);
    }
}
