package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.config.VoucherProcessor;
import cn.gson.financial.kernel.model.entity.AssetsChange;
import cn.gson.financial.kernel.model.entity.QAssetsChange;
import cn.gson.financial.kernel.model.repo.AssetsChangeRepository;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年12月24日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AssetsChangeService extends AbsService implements AccountSetsProcessor, VoucherProcessor, BackupProcessor {

    private final QAssetsChange qAssetsChange = QAssetsChange.assetsChange;

    private final AssetsChangeRepository changeRepository;

    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qAssetsChange)
                .where(qAssetsChange.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qAssetsChange)
                .where(qAssetsChange.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<?> backupType() {
        return AssetsChange.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<AssetsChange> entityList = JSON.parseArray(json, AssetsChange.class);
        for (AssetsChange entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            changeRepository.save(entity);
        }
    }

    @Override
    public void voucherDeleted(Long voucherId, Long accountSetId) {
        jqf.update(qAssetsChange)
                .setNull(qAssetsChange.voucherId)
                .where(qAssetsChange.voucherId.eq(voucherId))
                .execute();
    }
}
