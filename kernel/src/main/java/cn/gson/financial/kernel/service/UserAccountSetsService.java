package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.entity.QUserAccountSets;
import cn.gson.financial.kernel.model.entity.UserAccountSets;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.UserAccountSetsRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : ${PACKAGE_NAME}</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年08月01日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Service
@RequiredArgsConstructor
public class UserAccountSetsService extends AbsService implements AccountSetsProcessor, BackupProcessor {

    private final UserAccountSetsRepository userAccountSetsRepository;

    private final QUserAccountSets qUserAccountSets = QUserAccountSets.userAccountSets;


    @Transactional
    public UserAccountSets save(UserAccountSets userAccountSets) {
        if (userAccountSets.getId() != null) {
            UserAccountSets original = userAccountSetsRepository.getReferenceById(userAccountSets.getPrimaryKey());
            BeanUtil.copyProperties(userAccountSets, original, CopyOptions.create().ignoreNullValue());
            userAccountSets = userAccountSetsRepository.save(original);
        } else {
            userAccountSets = userAccountSetsRepository.save(userAccountSets);
        }
        return userAccountSets;
    }

    public List<UserAccountSets> list() {
        return bqf.selectFrom(qUserAccountSets)
                .orderBy(qUserAccountSets.id.desc())
                .fetch();
    }

    @Transactional
    public void delete(PrimaryKey primaryKey) {
        userAccountSetsRepository.deleteById(primaryKey);
    }

    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qUserAccountSets)
                .where(qUserAccountSets.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qUserAccountSets)
                .where(qUserAccountSets.accountSetsId.eq(accountSetsId))
                .fetch());
    }

    @Override
    public Class<?> backupType() {
        return UserAccountSets.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        if (bqf.selectFrom(qUserAccountSets)
                .where(qUserAccountSets.accountSetsId.eq(accountSetsId))
                .fetchCount() == 0) {
            List<UserAccountSets> userAccountSets = JSON.parseArray(json, UserAccountSets.class);
            userAccountSets.forEach(biReport -> {
                biReport.setAccountSetsId(accountSetsId);
            });
            userAccountSetsRepository.saveAll(userAccountSets);
        }
    }
}
