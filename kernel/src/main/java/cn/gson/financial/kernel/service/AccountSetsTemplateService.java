package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.common.PinYinUtil;
import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.model.dto.AccountSetsTemplateDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.AccountSetsTemplateMapper;
import cn.gson.financial.kernel.model.repo.AccountSetsTemplateRepository;
import cn.gson.financial.kernel.model.repo.SubjectRepository;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.tool.SubjectUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年10月22日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class AccountSetsTemplateService extends AbsService {
    private final List<AccountSetsProcessor> accountSetsProcessors;

    private final AccountSetsTemplateMapper subjectTemplateMapper;

    private final SubjectRepository subjectRepository;

    private final AccountSetsTemplateRepository templateRepository;

    private final QAccountSetsTemplate qAccountSetsTemplate = QAccountSetsTemplate.accountSetsTemplate;

    private final QAccountSets qAccountSets = QAccountSets.accountSets;

    private final QSubject qSubject = QSubject.subject;

    public List<AccountSetsTemplateDto> list() {
        return bqf.selectFrom(qAccountSetsTemplate)
                .orderBy(qAccountSetsTemplate.id.asc())
                .fetch().stream().map(subjectTemplateMapper::toDto).collect(Collectors.toList());
    }

    /**
     * 可用账套模板
     *
     * @return
     */
    public List<AccountSetsTemplateDto> enableList() {
        return bqf.selectFrom(qAccountSetsTemplate)
                .select(qAccountSetsTemplate.id, qAccountSetsTemplate.accountStandard, qAccountSetsTemplate.encoding, qAccountSetsTemplate.vatType, qAccountSetsTemplate.title)
                .orderBy(qAccountSetsTemplate.id.asc())
                .where(qAccountSetsTemplate.enabled.isTrue())
                .fetch().stream().map(tuple -> {
                    AccountSetsTemplateDto dto = new AccountSetsTemplateDto();
                    dto.setAccountStandard(tuple.get(qAccountSetsTemplate.accountStandard));
                    dto.setVatType(tuple.get(qAccountSetsTemplate.vatType));
                    dto.setId(tuple.get(qAccountSetsTemplate.id));
                    dto.setTitle(tuple.get(qAccountSetsTemplate.title));
                    dto.setEncoding(tuple.get(qAccountSetsTemplate.encoding));
                    return dto;
                }).collect(Collectors.toList());
    }

    @Transactional
    public void updateEncoding(Long templateId, List<Integer> newModel) {
        AccountSetsTemplate model = bqf.selectFrom(qAccountSetsTemplate)
                .where(qAccountSetsTemplate.id.eq(templateId)).fetchFirst();
        if (!CollUtil.join(newModel, "-").equals(CollUtil.join(model.getEncoding(), "-"))) {
            //更新所有科目编码
            List<Subject> subjects = bqf.selectFrom(qSubject).where(qSubject.accountSetsId.eq(templateId)).fetch();

            subjects.forEach(subject -> {
                String code = SubjectUtil.updateCode(subject.getCode(), model.getEncoding(), newModel);
                subject.setCode(code);
            });
            subjectRepository.saveAll(subjects);
            model.setEncoding(newModel);
            templateRepository.save(model);
            this.updateLastUpdate(templateId);
        }
    }

    public AccountSetsTemplate load(Long templateId) {
        return jqf.selectFrom(qAccountSetsTemplate).where(qAccountSetsTemplate.id.eq(templateId)).fetchFirst();
    }

    @Transactional
    public void importVoucher(Long templateId, List<SubjectVo> voucherList) {
        jqf.delete(qSubject)
                .where(qSubject.accountSetsId.eq(templateId).and(qSubject.accountSetsId.eq(templateId)))
                .execute();
        AccountSetsTemplate model = bqf.selectFrom(qAccountSetsTemplate)
                .where(qAccountSetsTemplate.id.eq(templateId)).fetchFirst();
        Integer encLen = model.getEncoding().stream().reduce(Integer::sum).orElse(0);
        recursionSaveSubject(templateId, 0L, voucherList, encLen);
        this.updateLastUpdate(templateId);
    }

    /**
     * 递归存储导入科目
     *
     * @param templateId
     * @param voucherList
     * @param encLen
     */
    private void recursionSaveSubject(Long templateId, Long parentId, List<SubjectVo> voucherList, Integer encLen) {
        for (SubjectVo vo : voucherList) {
            Subject subject = BeanUtil.toBean(vo, Subject.class);
            subject.setId(snowflakeId.generate());
            subject.setAccountSetsId(templateId);
            subject.setParentId(parentId);
            subject.setQueryCode(StrUtil.padAfter(subject.getCode(), encLen, "0"));
            subjectRepository.save(subject);
            if (CollUtil.isNotEmpty(vo.getChildren())) {
                recursionSaveSubject(templateId, subject.getId(), vo.getChildren(), encLen);
            }
        }
    }


    public List<Subject> itemList(Long templateId, String type) {
        BooleanExpression expression = qSubject.accountSetsId.eq(templateId).and(qSubject.accountSetsId.eq(templateId));
        if (StrUtil.isNotEmpty(type)) {
            expression = expression.and(qSubject.type.eq(type));
        }
        return bqf.selectFrom(qSubject)
                .where(expression)
                .orderBy(qSubject.code.asc())
                .fetch();
    }

    @Transactional
    public void saveItem(Subject item) {
        item.setMnemonicCode(PinYinUtil.getFirstLettersLo(item.getName()));
        if (item.getPrimaryKey() != null) {
            Subject original = subjectRepository.getReferenceById(new PrimaryKey(item.getId(), item.getAccountSetsId()));
            BeanUtil.copyProperties(item, original, CopyOptions.create().ignoreNullValue());
            item = original;
        }
        if (item.getParentId() != null && item.getParentId() != 0L) {
            Subject parent = subjectRepository.getReferenceById(new PrimaryKey(item.getParentId(), item.getAccountSetsId()));
            item.setFullName(parent.getFullName() + "_" + item.getName());

            if (parent.getLeaf()) {
                parent.setLeaf(false);
                subjectRepository.save(parent);
            }
        } else {
            item.setFullName(item.getName());
        }

        AccountSetsTemplate model = bqf.selectFrom(qAccountSetsTemplate)
                .where(qAccountSetsTemplate.id.eq(item.getAccountSetsId())).fetchFirst();
        Integer encLen = model.getEncoding().stream().reduce(Integer::sum).orElse(0);
        item.setQueryCode(StrUtil.padAfter(item.getCode(), encLen, "0"));
        subjectRepository.save(item);
        this.updateLastUpdate(item.getAccountSetsId());
    }

    @Transactional
    public void deleteItem(Long accountSetsId, Long itemId) {
        Subject reference = subjectRepository.getReferenceById(new PrimaryKey(itemId, accountSetsId));
        long l = bqf.selectFrom(qSubject)
                .where(qSubject.parentId.eq(reference.getId()).and(qSubject.accountSetsId.eq(accountSetsId)))
                .fetchCount();
        Assert.isTrue(l == 0, "请先删除子节点...");
        Long parentId = reference.getParentId();
        jqf.delete(qSubject)
                .where(qSubject.id.eq(itemId).and(qSubject.accountSetsId.eq(accountSetsId)))
                .execute();

        // 更新节点属性
        jqf.update(qSubject)
                .set(qSubject.leaf, bqf.selectFrom(qSubject)
                        .where(qSubject.accountSetsId.eq(accountSetsId).and(qSubject.parentId.eq(parentId)))
                        .fetchCount() == 0)
                .where(qSubject.accountSetsId.eq(accountSetsId).and(qSubject.id.eq(parentId)))
                .execute();

        this.updateLastUpdate(reference.getAccountSetsId());
    }

    public void updateLastUpdate(Long templateId) {
        jqf.update(qAccountSetsTemplate)
                .set(qAccountSetsTemplate.lastUpdateTime, LocalDateTime.now())
                .where(qAccountSetsTemplate.id.eq(templateId))
                .execute();
    }

    @Transactional
    public AccountSetsTemplate save(AccountSetsTemplate accountSetsTemplate) {
        if (accountSetsTemplate.getId() != null) {
            AccountSetsTemplate reference = templateRepository.getReferenceById(accountSetsTemplate.getId());
            if (!CollUtil.isEqualList(reference.getEncoding(), accountSetsTemplate.getEncoding())
                    || !CollUtil.isEqualList(reference.getSubjectCategory(), accountSetsTemplate.getSubjectCategory())
            ) {
                Assert.isTrue(bqf.selectFrom(qSubject)
                        .where(qSubject.accountSetsId.eq(accountSetsTemplate.getId()))
                        .fetchCount() == 0, "此模板已创建科目，不允许修改：<科目编码规则> 或 <科目类别>");
            }
        }
        return templateRepository.saveAndFlush(accountSetsTemplate);
    }

    @Transactional
    public void delete(Long templateId) {
        Assert.isTrue(bqf.selectFrom(qAccountSets).
                where(qAccountSets.astId.eq(templateId))
                .fetchCount() == 0, "已有用户使用此模板，不能进行删除操作~");

        jqf.delete(qAccountSetsTemplate)
                .where(qAccountSetsTemplate.id.eq(templateId))
                .execute();

        accountSetsProcessors.forEach(p -> p.clear(templateId));
    }
}
