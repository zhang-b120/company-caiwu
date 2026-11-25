package cn.gson.financial.kernel.config;

import cn.gson.financial.kernel.model.vo.UserVo;
import org.springframework.scheduling.annotation.Async;

import java.util.concurrent.CompletableFuture;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 归档</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年12月04日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
public interface ArchiveProcessor {
    /**
     * 归档
     *
     * @param userVo 账套ID
     * @return
     */
    @Async
    CompletableFuture<Boolean> archive(UserVo userVo);
}
