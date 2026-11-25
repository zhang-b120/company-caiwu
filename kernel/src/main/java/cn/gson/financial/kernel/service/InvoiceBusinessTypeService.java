package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.common.CopierCopyUtils;
import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.InvoiceBusinessTypeDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.InvoiceBusinessTypeMapper;
import cn.gson.financial.kernel.model.repo.InvoiceBusinessTypeRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nonapi.io.github.classgraph.json.JSONUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (InvoiceBusinessType)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-12-19 21:07:38</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvoiceBusinessTypeService extends AbsService implements AccountSetsProcessor, BackupProcessor {
    private final InvoiceBusinessTypeMapper invoiceBusinessTypeMapper;

    private final QInvoiceBusinessType qInvoiceBusinessType = QInvoiceBusinessType.invoiceBusinessType;
    private final QVoucherWord qVoucherWord = QVoucherWord.voucherWord;

    private final InvoiceBusinessTypeRepository invoiceBusinessTypeRepository;

    private final QInvoice qInvoice = QInvoice.invoice;
    private final QInvoiceItems qInvoiceItems = QInvoiceItems.invoiceItems;
    private final QSubject qSubject = QSubject.subject;
    private final QAccountSets qAccountSets = QAccountSets.accountSets;

    @Transactional
    public InvoiceBusinessType save(InvoiceBusinessType invoiceBusinessType) {
        if (invoiceBusinessType.getId() != null) {
            InvoiceBusinessType original = invoiceBusinessTypeRepository.getReferenceById(invoiceBusinessType.getPrimaryKey());
            if (!original.getTitle().equals(invoiceBusinessType.getTitle())) {
                Assert.isTrue(bqf.selectFrom(qInvoiceBusinessType)
                        .where(qInvoiceBusinessType.accountSetsId.eq(invoiceBusinessType.getAccountSetsId())
                                .and(qInvoiceBusinessType.id.ne(invoiceBusinessType.getId()))
                                .and(qInvoiceBusinessType.invoiceType.eq(invoiceBusinessType.getInvoiceType()))
                                .and(qInvoiceBusinessType.title.eq(invoiceBusinessType.getTitle())))
                        .fetchCount() == 0, "名称不能重复~");
            }
            BeanUtil.copyProperties(invoiceBusinessType, original, CopyOptions.create().ignoreNullValue());
            invoiceBusinessType = invoiceBusinessTypeRepository.save(original);
        } else {
            Assert.isTrue(bqf.selectFrom(qInvoiceBusinessType)
                    .where(qInvoiceBusinessType.accountSetsId.eq(invoiceBusinessType.getAccountSetsId())
                            .and(qInvoiceBusinessType.invoiceType.eq(invoiceBusinessType.getInvoiceType()))
                            .and(qInvoiceBusinessType.title.eq(invoiceBusinessType.getTitle())))
                    .fetchCount() == 0, "名称不能重复~");
            invoiceBusinessType = invoiceBusinessTypeRepository.save(invoiceBusinessType);
        }
        return invoiceBusinessType;
    }

    public List<InvoiceBusinessTypeDto> list(InvoiceType type, Long accountSetsId) {
        BooleanExpression expression = qInvoiceBusinessType.accountSetsId.eq(accountSetsId);
        if (type != null) {
            expression = expression
                    .and(qInvoiceBusinessType.invoiceType.eq(type));
        }
        return bqf.selectFrom(qInvoiceBusinessType)
                .select(qInvoiceBusinessType, qVoucherWord.word)
                .leftJoin(qVoucherWord).on(qVoucherWord.id.eq(qInvoiceBusinessType.voucherWord).and(qVoucherWord.accountSetsId.eq(qInvoiceBusinessType.accountSetsId)))
                .where(expression)
                .orderBy(qInvoiceBusinessType.id.desc())
                .fetch().stream().map(tuple -> {
                    InvoiceBusinessTypeDto dto = invoiceBusinessTypeMapper.toDto(tuple.get(qInvoiceBusinessType));
                    dto.setWord(tuple.get(qVoucherWord.word));
                    return dto;
                }).collect(Collectors.toList());
    }

    @Transactional
    public void delete(PrimaryKey primaryKey) {
        invoiceBusinessTypeRepository.deleteById(primaryKey);
    }

    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        //账套默认
        invoiceBusinessTypeRepository.saveAll(bqf.selectFrom(qInvoiceBusinessType)
                .where(qInvoiceBusinessType.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch().stream().map(s -> {
                    bqf.detach(s);
                    InvoiceBusinessType bean = BeanUtil.toBean(s, InvoiceBusinessType.class);
                    bean.setAccountSetsId(accountSets.getId());
                    return bean;
                }).collect(Collectors.toList()));
    }

    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qInvoiceBusinessType)
                .where(qInvoiceBusinessType.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qInvoiceBusinessType)
                .where(qInvoiceBusinessType.accountSetsId.eq(accountSetsId))
                .fetch());
    }

    @Override
    public Class<?> backupType() {
        return InvoiceBusinessType.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<InvoiceBusinessType> entityList = JSON.parseArray(json, InvoiceBusinessType.class);
        for (InvoiceBusinessType entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            invoiceBusinessTypeRepository.save(entity);
        }
    }

    /**
     * 批量修改发票业务类型
     * @param invoiceIdList
     * @param invoiceType
     * @param accountSetId
     */
    @Transactional
    public JsonResult batchChangeBusinessType(List<Long> invoiceIdList, InvoiceType invoiceType, Long accountSetId) {
        List<Long> unUpdateIdList = new ArrayList<>();
        BooleanExpression expression = qInvoiceBusinessType.accountSetsId.eq(accountSetId).and(qInvoiceBusinessType.matchWords.isNotNull());
        if(ObjectUtils.isNotEmpty(invoiceType)){
            expression = expression.and(qInvoiceBusinessType.invoiceType.eq(invoiceType));
        }
        if("OUT".equals(invoiceType.name())){
            String invoiceBusinessTypeTitle = "销售收入";
            expression = expression.and(qInvoiceBusinessType.title.eq(invoiceBusinessTypeTitle));
            Long id = bqf.selectFrom(qInvoiceBusinessType)
                    .select(qInvoiceBusinessType.id)
                    .where(expression)
                    .fetchFirst();
            if(ObjectUtils.isEmpty(id)){
                return JsonResult.failure("业务类型[\"" + invoiceBusinessTypeTitle + "\"]未匹配到！");
            }
            jqf.update(qInvoice)
                    .set(qInvoice.invoiceBusinessTypeId, id)
                    .where(qInvoice.id.in(invoiceIdList))
                    .execute();
        }else{
            expression = expression.and(qInvoiceBusinessType.matchWords.isNotNull());
            // 发票关键字匹配类型
            List<InvoiceBusinessType> invoiceBusinessTypeList = bqf.selectFrom(qInvoiceBusinessType)
                    .where(expression)
                    .fetch();
            List<InvoiceItems> invoiceItemsList = bqf.selectFrom(qInvoiceItems)
                    .leftJoin(qInvoice).on(qInvoice.id.eq(qInvoiceItems.invoiceId))
                    .where(qInvoiceItems.invoiceId.in(invoiceIdList).and(qInvoice.invoiceBusinessTypeId.isNull()))
                    .fetch();
            if (invoiceBusinessTypeList.size() > 0) {
                List<Long> finalUnUpdateIdList = new ArrayList<>();
                invoiceBusinessTypeList.forEach(invoiceBusinessType -> {
                    List<String> checkList = new ArrayList<>();
                    List<String> matchWordList = invoiceBusinessType.getMatchWords().toJavaList(String.class);
                    //防止有中文逗号
                    matchWordList.forEach(item -> {
                        checkList.addAll(Arrays.asList(item.split("，")));
                    });
                    invoiceItemsList.forEach(invoiceItems -> {
                        boolean flag = Boolean.TRUE;
                        if (checkList.stream().anyMatch(invoiceItems.getGoodsName()::contains)) {
                            jqf.update(qInvoice)
                                    .set(qInvoice.invoiceBusinessTypeId, invoiceBusinessType.getId())
                                    .where(qInvoice.id.eq(invoiceItems.getInvoiceId()))
                                    .execute();
                            flag = Boolean.FALSE;
                        }
                        if (flag) {
                            finalUnUpdateIdList.add(invoiceItems.getInvoiceId());
                        }
                    });
                });
                unUpdateIdList = finalUnUpdateIdList.stream().distinct().collect(Collectors.toList());
            }else{

                unUpdateIdList = invoiceIdList;
            }
        }
        return JsonResult.successful(unUpdateIdList);
    }

    /**
     * 根据名称查找或创建业务类型
     * @param businessTypeName 业务类型名称
     * @param invoiceType 发票类型
     * @param accountSetsId 账套ID
     * @return 业务类型ID，如果创建失败返回null
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long findOrCreateByName(String businessTypeName, InvoiceType invoiceType, Long accountSetsId) {
        try {
            // 先查找是否已存在
            InvoiceBusinessType existing = bqf.selectFrom(qInvoiceBusinessType)
                    .where(qInvoiceBusinessType.accountSetsId.eq(accountSetsId)
                            .and(qInvoiceBusinessType.invoiceType.eq(invoiceType))
                            .and(qInvoiceBusinessType.title.eq(businessTypeName)))
                    .fetchFirst();
            
            if (existing != null) {
                return existing.getId();
            }
            
            // 不存在则创建新的业务类型
            InvoiceBusinessType newType = new InvoiceBusinessType();
            newType.setAccountSetsId(accountSetsId);
            newType.setInvoiceType(invoiceType);
            newType.setTitle(businessTypeName);
            newType.setMatchWords(new JSONArray()); // 空的关键字列表
            newType.setItems(new ArrayList<>()); // 空的凭证模板项
            
            InvoiceBusinessType saved = invoiceBusinessTypeRepository.save(newType);
            log.info("创建新的业务类型：{}，账套ID：{}，发票类型：{}", businessTypeName, accountSetsId, invoiceType);
            return saved.getId();
        } catch (Exception e) {
            log.error("查找或创建业务类型失败：{}", businessTypeName, e);
            return null;
        }
    }
}

