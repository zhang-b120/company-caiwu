package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.entity.QSalarySocialEmployee;
import cn.gson.financial.kernel.model.entity.SalarySocialEmployee;
import cn.gson.financial.kernel.model.repo.SalarySocialEmployeeRepository;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-10 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-10</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalarySocialEmployeeService extends AbsService implements AccountSetsProcessor, BackupProcessor {
    private final SalarySocialEmployeeRepository ser;

    private final QSalarySocialEmployee qSocialEmployee = QSalarySocialEmployee.salarySocialEmployee;


    /**
     * 账套数据清理
     *
     * @param accountSetsId
     */
    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qSocialEmployee)
                .where(qSocialEmployee.accountSetsId.eq(accountSetsId))
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
        return JSON.toJSONString(bqf.selectFrom(qSocialEmployee)
                .where(qSocialEmployee.accountSetsId.eq(accountSetsId))
                .fetch());
    }

    /**
     * 备份类型
     *
     * @return
     */
    @Override
    public Class<?> backupType() {
        return SalarySocialEmployee.class;
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
        List<SalarySocialEmployee> salaryFieldSchedules = JSON.parseArray(json, SalarySocialEmployee.class);
        salaryFieldSchedules.forEach(s -> s.setAccountSetsId(accountSetsId));
        ser.saveAll(salaryFieldSchedules);
    }
}
