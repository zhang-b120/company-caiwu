package cn.gson.financial.kernel.config;

import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AccountSetsTemplate;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.config</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年11月11日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public interface AccountSetsProcessor {

    /**
     * 账套数据初始化
     *
     * @param accountSets
     * @param accountSetsTemplate
     */
    default void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {

    }

    /**
     * 账套数据清理
     *
     * @param accountSetsId
     */
    void clear(Long accountSetsId);
}
