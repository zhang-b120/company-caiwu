package cn.gson.financial.kernel.config;

import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;

/**
 * 
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 辅助项处理</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年01月10日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public interface AuxiliaryProcessor {

    /**
     * 辅助项目删除
     *
     * @param primaryKey
     * @param details
     */
    void deleted(PrimaryKey primaryKey, AcDetails details);
}
