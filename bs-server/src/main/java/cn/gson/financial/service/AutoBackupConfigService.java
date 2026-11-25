package cn.gson.financial.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.entity.AutoBackupConfig;
import cn.gson.financial.kernel.model.entity.QAutoBackupConfig;
import cn.gson.financial.kernel.model.repo.AutoBackupConfigRepository;
import cn.gson.financial.kernel.service.AbsService;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年06月21日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AutoBackupConfigService extends AbsService implements AccountSetsProcessor, BackupProcessor {

    private final QAutoBackupConfig qAutoBackupConfig = QAutoBackupConfig.autoBackupConfig;

    private final AutoBackupConfigRepository autoBackupConfigRepository;

    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qAutoBackupConfig)
                .where(qAutoBackupConfig.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        AutoBackupConfig autoBackupConfig = bqf.selectFrom(qAutoBackupConfig).
                where(qAutoBackupConfig.accountSetsId.eq(accountSetsId)).fetchFirst();
        if (autoBackupConfig != null) {
            return JSON.toJSONString(autoBackupConfig);
        }
        return null;
    }

    @Override
    public Class<?> backupType() {
        return AutoBackupConfig.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        AutoBackupConfig autoBackupConfig = JSON.parseObject(json, AutoBackupConfig.class);
        if (autoBackupConfig != null) {
            autoBackupConfig.setAccountSetsId(accountSetsId);
            autoBackupConfigRepository.save(autoBackupConfig);
        }
    }
}
