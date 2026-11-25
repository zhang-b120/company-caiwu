package cn.gson.financial.kernel.config;

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
public interface BackupProcessor {

    /**
     * 备份
     *
     * @param accountSetsId
     * @return
     */
    String backup(Long accountSetsId);

    /**
     * 备份类型
     *
     * @return
     */
    Class<?> backupType();

    /**
     * 恢复备份
     *
     * @param accountSetsId
     * @return
     */
    void recover(Long accountSetsId, String json);

}
