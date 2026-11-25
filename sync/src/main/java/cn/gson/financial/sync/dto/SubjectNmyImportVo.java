package cn.gson.financial.sync.dto;

import cn.hutool.core.annotation.Alias;
import cn.hutool.core.util.StrUtil;
import lombok.Data;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 柠檬云科目导入</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年09月12日</li>
 * <li>@author     : 开发者</li>
 * </ul>科目编码	科目名称	类别	余额方向	数量核算	辅助核算	是否必录	外币核算	期末调汇
 * 
 */
@Data
public class SubjectNmyImportVo {
    @Alias("科目编码")
    private String code;

    @Alias("科目名称")
    private String name;

    @Alias("类别")
    private String type;

    @Alias("余额方向")
    private String balanceDirection;

    @Alias("外币核算")
    private String currency;

    @Alias("辅助核算")
    private String auxiliaryAccounting;

    @Alias("数量核算")
    private String unit;

    public String getCode() {
        return StrUtil.trim(code);
    }

    /**
     * 科目名称
     */
    public void setName(String name) {
        this.name = StrUtil.trim(name);
    }

    /**
     * 科目编码
     */
    public void setCode(String code) {
        this.code = StrUtil.trim(code);
    }
}
