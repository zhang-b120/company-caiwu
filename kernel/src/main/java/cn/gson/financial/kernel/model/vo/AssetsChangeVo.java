package cn.gson.financial.kernel.model.vo;

import cn.gson.financial.kernel.model.entity.AssetsChange;
import lombok.Getter;
import lombok.Setter;

/**
 * 
 * <p><b>Copyright © 2010-2020开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022/11/1 14:17</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
public class AssetsChangeVo extends AssetsChange {

    private String assetsName;

    private String voucherSummary;
    private String assetsSubjectCode;

    private String code;

    private String voucher;

    private Double originalValue;

    private Double depreciationInit;


}
