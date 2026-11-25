package cn.gson.financial.sync;

import cn.gson.financial.sync.service.SyncHkjService;
import cn.gson.financial.sync.service.SyncJdyService;
import cn.gson.financial.sync.service.SyncKdZwyService;
import cn.gson.financial.sync.service.SyncNmyService;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.sync</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年09月01日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public enum SyncType {
    柠檬云(SyncNmyService.class),
    用友好会计(SyncHkjService.class),
    金蝶精斗云(SyncJdyService.class),
    金蝶账无忧(SyncKdZwyService.class);

    public final Class<? extends ImportService> importService;

    SyncType(Class<? extends ImportService> importService) {
        this.importService = importService;
    }
}
