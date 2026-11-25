package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.dto.CashierIoTypeDto;
import cn.gson.financial.kernel.model.dto.CashierIoTypeVoucherTemplateDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.mapper.CashierIoTypeMapper;
import cn.gson.financial.kernel.model.mapper.CashierIoTypeVoucherTemplateMapper;
import cn.gson.financial.kernel.model.repo.CashierIoTypeRepository;
import cn.gson.financial.kernel.model.repo.CashierIoTypeVoucherTemplateRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年10月25日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CashierIoTypeService extends AbsService implements AccountSetsProcessor, BackupProcessor {
    private final CashierIoTypeVoucherTemplateMapper cashierIoTypeVoucherTemplateMapper;
    private final CashierIoTypeMapper cashierIoTypeMapper;

    private final CashierIoTypeRepository cashierIoTypeRepository;

    private final CashierIoTypeVoucherTemplateRepository cashierIoTypeVoucherTemplateRepository;

    private final QCashierIoType qCashierIoType = QCashierIoType.cashierIoType;

    private final QSubject qSubject = QSubject.subject;
    private final QVoucherWord qVoucherWord = QVoucherWord.voucherWord;

    private final QCashierIoTypeVoucherTemplate qCashierIoTypeVoucherTemplate = QCashierIoTypeVoucherTemplate.cashierIoTypeVoucherTemplate;

    public List<CashierIoTypeDto> list(Long accountSetsId, String filter) {
        BooleanExpression expression = qCashierIoType.accountSetsId.eq(accountSetsId);
        if (StrUtil.isNotEmpty(filter)) {
            expression = expression.and(qCashierIoType.code.contains(filter).or(qCashierIoType.name.contains(filter)));
        }

        return bqf.selectFrom(qCashierIoType)
                .where(expression)
                .orderBy(qCashierIoType.type.asc(), qCashierIoType.code.castToNum(Integer.class).asc())
                .fetch().stream().map(ioType -> {
                    CashierIoTypeDto dto = cashierIoTypeMapper.toDto(ioType);
                    dto.setTemplates(bqf.selectFrom(qCashierIoTypeVoucherTemplate)
                            .where(qCashierIoTypeVoucherTemplate.ioTypeId.eq(ioType.getId())
                                    .and(qCashierIoTypeVoucherTemplate.accountSetsId.eq(accountSetsId)))
                            .fetch().stream().map(cashierIoTypeVoucherTemplateMapper::toDto).toList());
                    return dto;
                }).toList();
    }

    public List<CashierIoTypeVoucherTemplateDto> templateList(Long accountSetsId, CashierIoType.Type type, CashierAccount.CashierAccountType accountType) {
        BooleanExpression expression = qCashierIoTypeVoucherTemplate.accountSetsId.eq(accountSetsId);
        if (accountType != null) {
            expression = expression.and(qCashierIoTypeVoucherTemplate.journalType.eq(accountType));
        }
        if (type != null) {
            expression = expression.and(qCashierIoTypeVoucherTemplate.type.eq(type));
        }
        return bqf.selectFrom(qCashierIoTypeVoucherTemplate)
                .leftJoin(qCashierIoType).on(qCashierIoType.id.eq(qCashierIoTypeVoucherTemplate.ioTypeId).and(qCashierIoType.accountSetsId.eq(accountSetsId)))
                .leftJoin(qVoucherWord).on(qVoucherWord.id.eq(qCashierIoTypeVoucherTemplate.wordId).and(qVoucherWord.accountSetsId.eq(accountSetsId)))
                .leftJoin(qSubject).on(qSubject.id.eq(qCashierIoTypeVoucherTemplate.subjectId).and(qSubject.accountSetsId.eq(accountSetsId)))
                .select(qCashierIoTypeVoucherTemplate, qCashierIoType.name, qSubject.code, qSubject.fullName, qVoucherWord.word)
                .where(expression).orderBy(qCashierIoTypeVoucherTemplate.journalType.asc(), qCashierIoType.id.asc(), qCashierIoTypeVoucherTemplate.direction.asc())
                .fetch().stream().collect(ArrayList::new, (list, tuple) -> {
                    CashierIoTypeVoucherTemplateDto templateDto = BeanUtil.toBean(tuple.get(qCashierIoTypeVoucherTemplate), CashierIoTypeVoucherTemplateDto.class);
                    templateDto.setIoTypeName(tuple.get(qCashierIoType.name));
                    templateDto.setWord(tuple.get(qVoucherWord.word));
                    templateDto.setSubjectTitle(tuple.get(qSubject.code) + "-" + tuple.get(qSubject.fullName));
                    list.add(templateDto);
                }, List::addAll);
    }

    @Transactional
    public CashierIoTypeVoucherTemplate saveTemplate(CashierIoTypeVoucherTemplate template) {
        CashierIoTypeVoucherTemplate first = bqf.selectFrom(qCashierIoTypeVoucherTemplate)
                .where(qCashierIoTypeVoucherTemplate.accountSetsId.eq(template.getAccountSetsId())
                        .and(qCashierIoTypeVoucherTemplate.journalType.eq(template.getJournalType()))
                        .and(qCashierIoTypeVoucherTemplate.ioTypeId.eq(template.getIoTypeId()))
                        .and(qCashierIoTypeVoucherTemplate.direction.eq(template.getDirection())))
                .fetchFirst();
        if (first != null) {
            BeanUtil.copyProperties(template, first, CopyOptions.create().ignoreNullValue());
            return cashierIoTypeVoucherTemplateRepository.save(first);
        } else {
            return cashierIoTypeVoucherTemplateRepository.save(template);
        }
    }

    @Transactional
    public void deleteTemplate(Long accountSetsId, Long[] templateId) {
        jqf.delete(qCashierIoTypeVoucherTemplate)
                .where(qCashierIoTypeVoucherTemplate.accountSetsId.eq(accountSetsId)
                        .and(qCashierIoTypeVoucherTemplate.id.in(templateId)))
                .execute();
    }

    @Transactional
    public CashierIoType save(CashierIoType cashierIoType) {
        BooleanExpression codeExp = (cashierIoType.getCode() != null
                ? qCashierIoType.code.eq(cashierIoType.getCode())
                : qCashierIoType.code.isNull())
                .and(qCashierIoType.type.eq(cashierIoType.getType()))
                .and(qCashierIoType.accountSetsId.eq(cashierIoType.getAccountSetsId()));
        BooleanExpression nameExp = (cashierIoType.getName() != null
                ? qCashierIoType.name.eq(cashierIoType.getName())
                : qCashierIoType.name.isNull())
                .and(qCashierIoType.type.eq(cashierIoType.getType()))
                .and(qCashierIoType.accountSetsId.eq(cashierIoType.getAccountSetsId()));

        if (cashierIoType.getId() != null) {
            codeExp = codeExp.and(qCashierIoType.id.ne(cashierIoType.getId()));
            nameExp = nameExp.and(qCashierIoType.id.ne(cashierIoType.getId()));
        }

        Assert.isTrue(bqf.selectFrom(qCashierIoType).where(codeExp).fetchCount() == 0, "编码已存在~");
        Assert.isTrue(bqf.selectFrom(qCashierIoType).where(nameExp).fetchCount() == 0, "名称已存在~");

        return cashierIoTypeRepository.save(cashierIoType);
    }

    @Transactional
    public void delete(Long accountSetsId, Long cashierIoTypeId) {
        jqf.delete(qCashierIoType)
                .where(qCashierIoType.accountSetsId.eq(accountSetsId).and(qCashierIoType.id.eq(cashierIoTypeId)))
                .execute();
        jqf.delete(qCashierIoTypeVoucherTemplate)
                .where(qCashierIoTypeVoucherTemplate.accountSetsId.eq(accountSetsId)
                        .and(qCashierIoTypeVoucherTemplate.ioTypeId.eq(cashierIoTypeId)))
                .execute();
    }

    @Transactional
    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        List<CashierIoType> list = bqf.selectFrom(qCashierIoType)
                .where(qCashierIoType.accountSetsId.eq(accountSetsTemplate.getId())).fetch().stream().map(s -> {
                    bqf.detach(s);
                    CashierIoType bean = BeanUtil.toBean(s, CashierIoType.class);
                    bean.setAccountSetsId(accountSets.getId());
                    return bean;
                }).collect(Collectors.toList());
        if (CollUtil.isNotEmpty(list)) {
            cashierIoTypeRepository.saveAll(list);
        }
        //凭证模板
        List<CashierIoTypeVoucherTemplate> templateList = bqf.selectFrom(qCashierIoTypeVoucherTemplate)
                .where(qCashierIoTypeVoucherTemplate.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch().stream().map(s -> {
                    bqf.detach(s);
                    CashierIoTypeVoucherTemplate bean = BeanUtil.toBean(s, CashierIoTypeVoucherTemplate.class);
                    bean.setAccountSetsId(accountSets.getId());
                    return bean;
                }).collect(Collectors.toList());
        if (CollUtil.isNotEmpty(templateList)) {
            cashierIoTypeVoucherTemplateRepository.saveAll(templateList);
        }
    }

    @Transactional
    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qCashierIoType)
                .where(qCashierIoType.accountSetsId.eq(accountSetsId))
                .execute();
        jqf.delete(qCashierIoTypeVoucherTemplate)
                .where(qCashierIoTypeVoucherTemplate.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        Dict dict = Dict.create().set("cit", bqf.selectFrom(qCashierIoType)
                        .where(qCashierIoType.accountSetsId.eq(accountSetsId)).fetch())
                .set("vt", bqf.selectFrom(qCashierIoTypeVoucherTemplate)
                        .where(qCashierIoTypeVoucherTemplate.accountSetsId.eq(accountSetsId)).fetch());
        return JSON.toJSONString(dict);
    }

    @Override
    public Class<?> backupType() {
        return CashierIoType.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        //兼容旧备份还原
        if (json.startsWith("{")) {
            JSONObject data = JSON.parseObject(json);
            if (data.containsKey("cit")) {
                List<CashierIoType> entityList = data.getJSONArray("cit").toJavaList(CashierIoType.class);
                for (CashierIoType entity : entityList) {
                    entity.setAccountSetsId(accountSetsId);
                    cashierIoTypeRepository.save(entity);
                }
            }

            if (data.containsKey("vt")) {
                List<CashierIoTypeVoucherTemplate> entityList = data.getJSONArray("vt").toJavaList(CashierIoTypeVoucherTemplate.class);
                for (CashierIoTypeVoucherTemplate entity : entityList) {
                    entity.setAccountSetsId(accountSetsId);
                    cashierIoTypeVoucherTemplateRepository.save(entity);
                }
            }
        } else {
            List<CashierIoType> entityList = JSON.parseArray(json, CashierIoType.class);
            for (CashierIoType entity : entityList) {
                entity.setAccountSetsId(accountSetsId);
                cashierIoTypeRepository.save(entity);
            }
        }
    }
}
