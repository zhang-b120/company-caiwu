package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.entity.QSalaryFieldValue;
import cn.gson.financial.kernel.model.entity.SalaryFieldValue;
import cn.gson.financial.kernel.model.repo.SalaryFieldValueRepository;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年12月28日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalaryFiledValueService extends AbsService implements AccountSetsProcessor, BackupProcessor {

    private final QSalaryFieldValue qSalaryFieldValue = QSalaryFieldValue.salaryFieldValue;

    private final SalaryFieldValueRepository salaryFieldValueRepository;

    /**
     * 账套数据清理
     *
     * @param accountSetsId
     */
    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qSalaryFieldValue)
                .where(qSalaryFieldValue.accountSetsId.eq(accountSetsId))
                .execute();
    }

    /**
     * 备份
     *
     * @param accountSetsId
     * @return
     */
    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qSalaryFieldValue)
                .where(qSalaryFieldValue.accountSetsId.eq(accountSetsId))
                .fetch());
    }

    /**
     * 备份类型
     *
     * @return
     */
    @Override
    public Class<?> backupType() {
        return SalaryFieldValue.class;
    }

    /**
     * 恢复备份
     *
     * @param accountSetsId
     * @param json
     * @return
     */
    @Override
    public void recover(Long accountSetsId, String json) {
        List<SalaryFieldValue> fieldValues = JSON.parseArray(json, SalaryFieldValue.class);
        salaryFieldValueRepository.saveAll(fieldValues.stream()
                .peek(s -> s.setAccountSetsId(accountSetsId)).toList());
    }
}
