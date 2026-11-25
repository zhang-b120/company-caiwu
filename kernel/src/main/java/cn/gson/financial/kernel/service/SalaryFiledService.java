package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AccountSetsTemplate;
import cn.gson.financial.kernel.model.entity.QSalaryField;
import cn.gson.financial.kernel.model.entity.SalaryField;
import cn.gson.financial.kernel.model.enums.DataType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.SalaryFieldRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson.JSON;
import com.querydsl.core.BooleanBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
public class SalaryFiledService extends AbsService implements AccountSetsProcessor, BackupProcessor {

    private final SalaryFieldRepository salaryFieldRepository;

    private final QSalaryField qSalaryField = QSalaryField.salaryField;

    /**
     * 自定义字段列表
     *
     * @param accountSetsId
     * @param query         状态
     * @return
     */
    public List<SalaryField> list(long accountSetsId, Query query) {
        return bqf.selectFrom(qSalaryField)
                .where(query.builder.and(qSalaryField.accountSetsId.eq(accountSetsId)))
                .orderBy(qSalaryField.weight.asc())
                .fetch();
    }

    @Transactional
    public void save(Long accountSetsId, List<SalaryField> salaryFields) {
        List<String> list = salaryFields.stream()
                .collect(Collectors.groupingBy(SalaryField::getFieldName, Collectors.counting())).entrySet().stream()
                .filter(entry -> entry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();
        Assert.isTrue(list.isEmpty(), CollUtil.join(list, ",") + "字段名称重复,请检查~");
        salaryFieldRepository.saveAll(salaryFields.stream().peek(s -> s.setAccountSetsId(accountSetsId)).toList());
    }

    @Transactional
    public void delete(PrimaryKey primaryKey) {
        salaryFieldRepository.deleteById(primaryKey);
    }

    /**
     * 账套数据初始化
     *
     * @param accountSets
     * @param accountSetsTemplate
     */
    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        List<SalaryField> fieldList = bqf.selectFrom(qSalaryField)
                .where(qSalaryField.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch();
        if (CollUtil.isNotEmpty(fieldList)) {
            salaryFieldRepository.saveAll(fieldList.stream().map(o -> {
                bqf.detach(o);
                SalaryField bean = BeanUtil.toBean(o, SalaryField.class);
                bean.setAccountSetsId(accountSets.getId());
                return bean;
            }).toList());
        }
    }

    /**
     * 账套数据清理
     *
     * @param accountSetsId
     */
    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qSalaryField)
                .where(qSalaryField.accountSetsId.eq(accountSetsId))
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
        return JSON.toJSONString(bqf.selectFrom(qSalaryField)
                .where(qSalaryField.accountSetsId.eq(accountSetsId)).fetch());
    }

    /**
     * 备份类型
     *
     * @return
     */
    @Override
    public Class<?> backupType() {
        return SalaryField.class;
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
        List<SalaryField> salaryFields = com.alibaba.fastjson2.JSON.parseArray(json, SalaryField.class);
        salaryFields.forEach(s -> s.setAccountSetsId(accountSetsId));
        salaryFieldRepository.saveAll(salaryFields);
    }

    public static class Query {
        private BooleanBuilder builder = new BooleanBuilder();

        private final QSalaryField qSalaryField = QSalaryField.salaryField;

        public void setEnabled(Boolean enabled) {
            if (enabled != null) {
                builder.and(qSalaryField.enabled.eq(enabled));
            }
        }

        public void setNumber(Boolean number) {
            if (number != null) {
                builder.and(qSalaryField.dataType.ne(DataType.字符));
            }
        }
    }
}
