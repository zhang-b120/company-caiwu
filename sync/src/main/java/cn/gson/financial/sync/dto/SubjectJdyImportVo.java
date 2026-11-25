package cn.gson.financial.sync.dto;

import cn.gson.financial.kernel.annotation.ColName;
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
public class SubjectJdyImportVo {
    @ColName("编码")
    private String code;
    @ColName("名称")
    private String name;
    @ColName("类别")
    private String type;
    @ColName("余额方向")
    private String balanceDirection;
    @ColName("辅助核算")
    private String auxiliaryAccounting;
    @ColName("外币")
    private String currencyAccounting;
    @ColName("数量")
    private String unit;
    @ColName("是否调汇")
    private String adjustments;
    @ColName("状态")
    private String status;

}
