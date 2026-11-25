package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.dto.VoucherTemplateDto;
import cn.gson.financial.kernel.model.entity.QVoucherTemplate;
import cn.gson.financial.kernel.model.entity.VoucherTemplate;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.VoucherTemplateMapper;
import cn.gson.financial.kernel.model.repo.VoucherTemplateRepository;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 凭证模板</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年09月15日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VoucherTemplateService extends AbsService implements AccountSetsProcessor, BackupProcessor {
    private final VoucherTemplateMapper voucherTemplateMapper;

    private final VoucherTemplateRepository voucherTemplateRepository;

    private final QVoucherTemplate qVoucherTemplate = QVoucherTemplate.voucherTemplate;

    /**
     * 模板列表
     *
     * @param accountSetsId
     * @return
     */
    public List<VoucherTemplateDto> list(Long accountSetsId) {
        return bqf.selectFrom(qVoucherTemplate)
                .where(qVoucherTemplate.accountSetsId.eq(accountSetsId))
                .fetch().stream().map(voucherTemplateMapper::toDto).collect(Collectors.toList());
    }

    /**
     * 模板列表
     *
     * @param templateDto
     * @return
     */
    @Transactional
    public void save(VoucherTemplateDto templateDto) {
        VoucherTemplate entity = voucherTemplateMapper.toEntity(templateDto);
        if (entity.getPrimaryKey() == null) {
            Assert.isTrue(bqf.selectFrom(qVoucherTemplate)
                    .where(qVoucherTemplate.accountSetsId.eq(entity.getAccountSetsId()).and(qVoucherTemplate.title.eq(entity.getTitle())))
                    .fetchCount() == 0, "模板名称不能重复");
        } else {
            Assert.isTrue(bqf.selectFrom(qVoucherTemplate)
                    .where(qVoucherTemplate.accountSetsId.eq(entity.getAccountSetsId())
                            .and(qVoucherTemplate.id.ne(entity.getId()))
                            .and(qVoucherTemplate.title.eq(entity.getTitle())))
                    .fetchCount() == 0, "模板名称不能重复");
        }
        voucherTemplateRepository.saveAndFlush(entity);
    }

    /**
     * 模板列表
     *
     * @param primaryKey
     * @return
     */
    @Transactional
    public void delete(PrimaryKey primaryKey) {
        voucherTemplateRepository.deleteById(primaryKey);
    }

    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qVoucherTemplate)
                .where(qVoucherTemplate.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qVoucherTemplate)
                .where(qVoucherTemplate.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<?> backupType() {
        return VoucherTemplate.class;
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
        if (StrUtil.isNotEmpty(json)) {
            List<VoucherTemplate> templates = JSON.parseArray(json).toJavaList(VoucherTemplate.class);
            if (CollUtil.isNotEmpty(templates)) {
                for (VoucherTemplate template : templates) {
                    template.setAccountSetsId(accountSetsId);
                }
                voucherTemplateRepository.saveAll(templates);
            }
        }
    }
}
