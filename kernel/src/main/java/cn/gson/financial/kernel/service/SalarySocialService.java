package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AccountSetsTemplate;
import cn.gson.financial.kernel.model.entity.QSalaryField;
import cn.gson.financial.kernel.model.entity.QSalarySocial;
import cn.gson.financial.kernel.model.entity.QSalarySocialEmployee;
import cn.gson.financial.kernel.model.entity.SalaryField;
import cn.gson.financial.kernel.model.entity.SalarySocial;
import cn.gson.financial.kernel.model.enums.SocialType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.SalaryFieldRepository;
import cn.gson.financial.kernel.model.repo.SalarySocialRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-05 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-05</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalarySocialService extends AbsService implements AccountSetsProcessor, BackupProcessor {

    private final SalarySocialRepository salarySocialRepository;
    private final SalaryFieldRepository salaryFieldRepository;

    private final QSalarySocial qSocialSecurity = QSalarySocial.salarySocial;
    private final QSalaryField qSalaryField = QSalaryField.salaryField;
    private final QSalarySocialEmployee qSalarySocialEmployee = QSalarySocialEmployee.salarySocialEmployee;


    public List<SalarySocial> list(Long accountSetsId) {
        return bqf.selectFrom(qSocialSecurity)
                .where(qSocialSecurity.accountSetsId.eq(accountSetsId))
                .orderBy(qSocialSecurity.id.asc())
                .fetch();
    }

    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        List<SalarySocial> socialSecurities = bqf.selectFrom(qSocialSecurity)
                .where(qSocialSecurity.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch();

        if (CollUtil.isNotEmpty(socialSecurities)) {
            salarySocialRepository.saveAll(socialSecurities.stream().map(s -> {
                bqf.detach(s);
                SalarySocial bean = BeanUtil.toBean(s, SalarySocial.class);
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
        jqf.delete(qSocialSecurity)
                .where(qSocialSecurity.accountSetsId.eq(accountSetsId))
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
        return JSON.toJSONString(bqf.selectFrom(qSocialSecurity)
                .where(qSocialSecurity.accountSetsId.eq(accountSetsId))
                .fetch());
    }

    /**
     * 备份类型
     *
     * @return
     */
    @Override
    public Class<?> backupType() {
        return SalarySocial.class;
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
        List<SalarySocial> salaryFieldSchedules = JSON.parseArray(json, SalarySocial.class);
        salaryFieldSchedules.forEach(s -> s.setAccountSetsId(accountSetsId));
        salarySocialRepository.saveAll(salaryFieldSchedules);
    }

    @Transactional
    public void save(SalarySocial social) {
        if (social.getDepositBase() != null && NumberUtil.isGreater(social.getDepositBase(), BigDecimal.ZERO)) {
            social.setCompanyNum(NumberUtil.mul(social.getDepositBase(), NumberUtil.div(social.getCompanyRatio(), 100)));
            social.setPersonNum(NumberUtil.mul(social.getDepositBase(), NumberUtil.div(social.getPersonRatio(), 100)));
        }

        boolean isNew = social.getPrimaryKey() == null;
        if (isNew) {
            salarySocialRepository.save(social);
        } else {
            SalarySocial reference = salarySocialRepository.getReferenceById(social.getPrimaryKey());
            BeanUtil.copyProperties(social, reference, CopyOptions.create().ignoreNullValue());
            salarySocialRepository.save(reference);
        }

        // 如果是新增且类型为社保，自动更新对应工资项公式（公积金不需要）
        if (isNew && SocialType.社保.equals(social.getType()) && StrUtil.isNotBlank(social.getName())) {
            updateSalaryFieldFormula(social);
        }
    }

    /**
     * 更新工资项公式，自动添加社保/公积金的引用
     *
     * @param social 社保/公积金配置
     */
    private void updateSalaryFieldFormula(SalarySocial social) {
        // 根据类型确定要更新的字段名称
        String fieldName = SocialType.社保.equals(social.getType()) ? "社保缴纳" : "公积金缴纳";

        // 查找对应的工资项字段
        SalaryField salaryField = bqf.selectFrom(qSalaryField)
                .where(qSalaryField.accountSetsId.eq(social.getAccountSetsId())
                        .and(qSalaryField.fieldName.eq(fieldName)))
                .fetchFirst();

        if (salaryField == null) {
            // 如果字段不存在，跳过更新（不自动创建）
            log.debug("工资项字段[{}]不存在，跳过公式更新", fieldName);
            return;
        }

        // 构建要添加的公式部分（只需要个人部分）
        String formulaPart = "个人" + social.getName();

        // 更新公式
        String currentFormula = salaryField.getFormula();
        if (StrUtil.isBlank(currentFormula)) {
            // 如果原公式为空，直接设置
            salaryField.setFormula(formulaPart);
        } else {
            // 如果已有公式，检查是否已包含该引用，避免重复添加
            if (!currentFormula.contains(formulaPart)) {
                salaryField.setFormula(currentFormula + " + " + formulaPart);
            } else {
                // 已存在，不重复添加
                return;
            }
        }

        // 保存更新后的工资项字段
        salaryFieldRepository.save(salaryField);
    }

    @Transactional
    public void delete(Long accountSetsId, Long id) {
        // 先查询社保/公积金信息，获取类型和名称
        SalarySocial social = bqf.selectFrom(qSocialSecurity)
                .where(qSocialSecurity.accountSetsId.eq(accountSetsId)
                        .and(qSocialSecurity.id.eq(id)))
                .fetchFirst();

        if (social == null) {
            return; // 如果不存在，直接返回
        }

        // 检查是否有员工使用该社保/公积金
        long count = bqf.selectFrom(qSalarySocialEmployee)
                .where(qSalarySocialEmployee.accountSetsId.eq(accountSetsId)
                        .and(qSalarySocialEmployee.socialId.eq(id)))
                .fetchCount();

        if (count > 0) {
            String typeName = SocialType.社保.equals(social.getType()) ? "社保" : "公积金";
            throw new ServiceException("该" + typeName + "已被员工使用，无法删除");
        }

        // 从工资项公式中删除该社保/公积金的引用
        removeFromSalaryFieldFormula(social);

        // 删除社保/公积金配置
        salarySocialRepository.deleteById(new PrimaryKey(id, accountSetsId));
    }

    /**
     * 从工资项公式中删除社保/公积金的引用
     *
     * @param social 社保/公积金配置
     */
    private void removeFromSalaryFieldFormula(SalarySocial social) {
        // 根据类型确定要更新的字段名称
        String fieldName = SocialType.社保.equals(social.getType()) ? "社保缴纳" : "公积金缴纳";

        // 查找对应的工资项字段
        SalaryField salaryField = bqf.selectFrom(qSalaryField)
                .where(qSalaryField.accountSetsId.eq(social.getAccountSetsId())
                        .and(qSalaryField.fieldName.eq(fieldName)))
                .fetchFirst();

        if (salaryField == null || StrUtil.isBlank(salaryField.getFormula())) {
            // 如果字段不存在或公式为空，跳过
            return;
        }

        String currentFormula = salaryField.getFormula();
        String formulaPart = "个人" + social.getName();

        // 检查公式中是否包含该引用
        if (!currentFormula.contains(formulaPart)) {
            return; // 不包含，无需处理
        }

        // 从公式中删除该引用
        String newFormula = currentFormula
                .replace(" + " + formulaPart, "") // 删除 " + 个人XXX"
                .replace(formulaPart + " + ", "") // 删除 "个人XXX + "
                .replace(" +" + formulaPart, "") // 删除 " +个人XXX"（无空格）
                .replace(formulaPart + "+ ", "") // 删除 "个人XXX+ "（无空格）
                .replace(formulaPart, ""); // 删除单独的 "个人XXX"

        // 清理多余的空格和 + 号
        newFormula = newFormula.trim();
        // 清理开头的 + 号
        while (newFormula.startsWith("+")) {
            newFormula = newFormula.substring(1).trim();
        }
        // 清理结尾的 + 号
        while (newFormula.endsWith("+")) {
            newFormula = newFormula.substring(0, newFormula.length() - 1).trim();
        }
        // 清理连续的 + 号
        newFormula = newFormula.replaceAll("\\+\\s*\\+", "+");
        // 清理 + 号前后的多余空格
        newFormula = newFormula.replaceAll("\\s*\\+\\s*", " + ").trim();

        salaryField.setFormula(newFormula);
        salaryFieldRepository.save(salaryField);
    }
}
