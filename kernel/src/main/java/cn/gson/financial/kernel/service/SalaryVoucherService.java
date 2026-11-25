package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.config.VoucherProcessor;
import cn.gson.financial.kernel.model.entity.QSalaryVoucher;
import cn.gson.financial.kernel.model.entity.QVoucher;
import cn.gson.financial.kernel.model.entity.SalaryVoucher;
import cn.gson.financial.kernel.model.repo.SalaryVoucherRepository;
import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年01月02日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SalaryVoucherService extends AbsService implements VoucherProcessor, BackupProcessor {

    private QSalaryVoucher qSalaryVoucher = QSalaryVoucher.salaryVoucher;
    private QVoucher qVoucher = QVoucher.voucher;

    private final SalaryVoucherRepository salaryVoucherRepository;

    /**
     * 根据凭证模板ID和期间查询出已生成的凭证。
     * <p>FN-FIX-VOUCHER-DELETE-CASCADE：INNER JOIN voucher 表验真，剔除「voucher 已被
     * 删但 salary_voucher 残留」的孤儿行（手工 SQL 删凭证场景）。否则 autoGenerateVouchers
     * 的「已生成」幂等检查会被孤儿骗过、跳过重生成。
     */
    public Map<Long, SalaryVoucher> loadList(Long accountSetId, List<Long> templateIds, LocalDate period, Long salaryId) {
        Map<Long, SalaryVoucher> voucherMap = new HashMap<>();
        if (CollUtil.isNotEmpty(templateIds)) {
            voucherMap = bqf.selectFrom(qSalaryVoucher)
                    .innerJoin(qVoucher).on(qVoucher.id.eq(qSalaryVoucher.voucherId)
                            .and(qVoucher.accountSetsId.eq(qSalaryVoucher.accountSetsId)))
                    .where(qSalaryVoucher.accountSetsId.eq(accountSetId)
                            .and(qSalaryVoucher.templateId.in(templateIds))
                            .and(qSalaryVoucher.salaryId.eq(salaryId))
                            .and(qSalaryVoucher.period.eq(period)))
                    .fetch().stream().collect(Collectors.toMap(SalaryVoucher::getTemplateId, t -> t));
        }
        return voucherMap;
    }

    @Transactional
    public void save(SalaryVoucher salaryVoucher) {
        salaryVoucherRepository.save(salaryVoucher);
    }

    @Override
    public void voucherDeleted(Long voucherId, Long accountSetId) {
        jqf.delete(qSalaryVoucher)
                .where(qSalaryVoucher.accountSetsId.eq(accountSetId)
                        .and(qSalaryVoucher.voucherId.eq(voucherId)))
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
        return JSON.toJSONString(bqf.selectFrom(qSalaryVoucher).where(qSalaryVoucher.accountSetsId.eq(accountSetsId)).fetch());
    }

    /**
     * 备份类型
     *
     * @return
     */
    @Override
    public Class<?> backupType() {
        return SalaryVoucher.class;
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
        List<SalaryVoucher> salaryVouchers = JSON.parseArray(json, SalaryVoucher.class);
        salaryVoucherRepository.saveAll(salaryVouchers.stream()
                .peek(sv -> sv.setAccountSetsId(accountSetsId))
                .toList());
    }
}
