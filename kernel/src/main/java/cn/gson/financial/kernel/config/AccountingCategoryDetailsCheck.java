package cn.gson.financial.kernel.config;

import cn.gson.financial.kernel.model.entity.AcDetails;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.config</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年12月23日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public interface AccountingCategoryDetailsCheck {

    /**
     * 辅助核算项是否已使用
     *
     * @param acDetails
     * @return
     */
    boolean isAccountingCategoryDetailsUsed(AcDetails acDetails);
}
