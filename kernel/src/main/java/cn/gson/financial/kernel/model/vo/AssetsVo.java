package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.entity.Assets;
import com.fasterxml.jackson.annotation.JsonGetter;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 
 * <p><b>Copyright © 2010-2020开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022/11/1 11:48</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Setter
@Getter
public class AssetsVo extends Assets {

    private String assetsTypeName;
    private String deptName;
    /**
     * 本期折旧
     */
    private BigDecimal currentDepreciation;
    /**
     * 资产净值
     */
    private BigDecimal netValue;

    /**
     * 实际累计折旧
     */
    private BigDecimal depreciationCreditSum;

    private Long detailCount;

    @JsonGetter
    public boolean canEdit() {
        return !this.getIsVoucher() && this.detailCount == 0;
    }
}
