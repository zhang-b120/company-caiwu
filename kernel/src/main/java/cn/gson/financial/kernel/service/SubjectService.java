package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.aspect.CacheRemove;
import cn.gson.financial.kernel.common.PinYinUtil;
import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.BalanceDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.entity.AuxiliaryDetail.Detail;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.BalanceMapper;
import cn.gson.financial.kernel.model.mapper.SubjectMapper;
import cn.gson.financial.kernel.model.repo.*;
import cn.gson.financial.kernel.model.vo.SubjectVo;
import cn.gson.financial.kernel.tool.SubjectUtil;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.alibaba.fastjson.JSON;
import com.blazebit.persistence.querydsl.BlazeJPAQuery;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : ${PACKAGE_NAME}</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubjectService extends AbsService implements AccountSetsProcessor, BackupProcessor {
    private final BalanceMapper balanceMapper;

    private final SubjectMapper subjectMapper;

    private final SubjectRepository subjectRepository;

    private final AuxiliaryDetailRepository auxiliaryDetailRepository;

    private final AccountingCategoryRepository accountingCategoryRepository;

    private final VoucherDetailsRepository voucherDetailsRepository;

    private final BalanceRepository balanceRepository;

    private final BalanceInitialRepository balanceInitialRepository;

    private final QSubject qSubject = QSubject.subject;

    private final QSubject qParentSubject = new QSubject("parent");

    private final QAuxiliaryDetail qAuxiliaryDetail = QAuxiliaryDetail.auxiliaryDetail;

    private final QVoucherDetails qVoucherDetails = QVoucherDetails.voucherDetails;

    private final QBalanceInitial qBalanceInitial = QBalanceInitial.balanceInitial;

    private final QAccountingCategory qAccountingCategory = QAccountingCategory.accountingCategory;

    private final QCurrency qCurrency = QCurrency.currency;

    private final QBalance qBalance = QBalance.balance;

    @Resource
    @Lazy
    private BalanceCalcService balanceCalcService;

    @Resource
    @Lazy
    private BackupService backupService;

    private final String[] ys = new String[]{"应收账款", "其他应收款", "预收账款"};

    private final String[] yf = new String[]{"应付账款", "其他应付款", "预付账款"};

    @Cacheable(value = "subject", key = "#accountSetsId+':selectData'")
    public List<Subject> selectData(Long accountSetsId) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.status.isTrue()))
                .orderBy(qSubject.queryCode.asc())
                .fetch();
    }

    @Cacheable(value = "subject", key = "#accountSetsId+':leaf'")
    public List<Subject> selectLeafData(Long accountSetsId) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.status.isTrue())
                        .and(bqf.selectFrom(qParentSubject)
                                .select(qParentSubject.count())
                                .where(qParentSubject.parentId.eq(qSubject.id)
                                        .and(qParentSubject.status.isTrue())
                                        .and(qParentSubject.accountSetsId.eq(accountSetsId))).eq(0L)))
                .orderBy(qSubject.queryCode.asc())
                .fetch();
    }

    @Transactional
    @CacheRemove(value = "subject", key = "#accountSets.id")
    public void importSubject(AccountSets accountSets, List<SubjectVo> subjectVos) {
        Map<String, Subject> subjectMap = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSets.getId()))
                .fetch().stream().collect(Collectors.toMap(Subject::getCode, s -> s));
        recursionSaveSubject(accountSets, 0L, subjectVos, subjectMap, 1);
    }

    /**
     * 递归存储导入科目
     *
     * @param accountSets
     * @param subjectVos
     */
    private void recursionSaveSubject(AccountSets accountSets, Long parentId, List<SubjectVo> subjectVos, Map<String, Subject> subjectMap, int level) {
        int encLen = accountSets.getEncoding().stream().reduce(Integer::sum).orElse(0);
        for (SubjectVo vo : subjectVos) {
            Subject subject = subjectMap.get(vo.getCode());
            if (subject == null) {
                subject = subjectMapper.toEntity(vo);
                subject.setId(snowflakeId.generate());
                subject.setAccountSetsId(accountSets.getId());
                subject.setParentId(parentId);
                subject.setSystemDefault(false);
                subject.setQueryCode(StrUtil.padAfter(subject.getCode(), encLen, "0"));
            } else {
                subject = subjectMapper.partialUpdate(vo, subject);
                subjectMap.remove(vo.getCode());
                subject.setQueryCode(StrUtil.padAfter(subject.getCode(), encLen, "0"));
            }
            subject.setLevel(level);
            subject.setLeaf(CollUtil.isEmpty(vo.getChildren()));
            subjectRepository.save(subject);
            if (CollUtil.isNotEmpty(vo.getChildren())) {
                recursionSaveSubject(accountSets, subject.getId(), vo.getChildren(), subjectMap, level + 1);
            }
        }
    }

    /**
     * 检查科目是否已经被使用
     *
     * @param subjectId
     * @param accountSetsId
     * @return
     */
    public Boolean checkUse(Long subjectId, Long accountSetsId) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId).and(qSubject.parentId.eq(subjectId)))
                .fetchCount() > 0 ||
                bqf.selectFrom(qVoucherDetails)
                        .where(qVoucherDetails.subjectId.eq(subjectId)
                                .and(qVoucherDetails.accountSetsId.eq(accountSetsId)))
                        .fetchCount() > 0 ||
                bqf.selectFrom(qBalanceInitial)
                        .where(qBalanceInitial.subjectId.eq(subjectId)
                                .and(qBalanceInitial.accountSetsId.eq(accountSetsId)))
                        .fetchCount() > 0;
    }

    /**
     * 检查科目是否已经产生凭证数据
     *
     * @param subjectId
     * @param accountSetsId
     * @return
     */
    public Boolean checkHasData(Long subjectId, Long accountSetsId) {
        return voucherDetailsRepository.count(qVoucherDetails.subjectId.eq(subjectId)
                .and(qVoucherDetails.accountSetsId.eq(accountSetsId))) > 0 ||
                bqf.selectFrom(qBalance)
                        .where(qBalance.subjectId.eq(subjectId)
                                .and(qBalance.accountSetsId.eq(accountSetsId))).fetchCount() > 0;
    }

    /**
     * 更新科目状态
     *
     * @param subject
     */
    @Transactional
    @CacheRemove(value = "subject", key = "#subject.accountSetsId")
    public void updateStatus(Subject subject) {
        Subject old = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(subject.getAccountSetsId())
                        .and(qSubject.id.eq(subject.getId())))
                .fetchFirst();
        old.setStatus(subject.getStatus());

        if (!subject.getStatus()) {
            long l = bqf.selectFrom(qVoucherDetails)
                    .leftJoin(qSubject).on(qSubject.id.eq(qVoucherDetails.subjectId).and(qSubject.accountSetsId.eq(qVoucherDetails.accountSetsId)))
                    .where(qVoucherDetails.accountSetsId.eq(subject.getAccountSetsId())
                            .and(qSubject.code.startsWith(old.getCode())))
                    .fetchCount();
            Assert.isTrue(l == 0, "科目已被使用，不能禁用~");
        }

        subjectRepository.save(old);

        if (!subject.getStatus()) {
            // 同步禁用子节点
            jqf.update(qSubject)
                    .set(qSubject.status, subject.getStatus())
                    .where(qSubject.accountSetsId.eq(subject.getAccountSetsId())
                            .and(qSubject.code.startsWith(old.getCode())))
                    .execute();
        }

        // 跟新父节点leaf状态
        if (old.getParentId() != null && old.getParentId() != 0L) {
            recursiveEnableParent(old);
        }
    }

    private void changeParentLeaf(Subject old) {
        long childNum = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(old.getAccountSetsId())
                        .and(qSubject.status.isTrue())
                        .and(qSubject.parentId.eq(old.getParentId())))
                .fetchCount();
        jqf.update(qSubject)
                .set(qSubject.leaf, childNum == 0)
                .where(qSubject.accountSetsId.eq(old.getAccountSetsId())
                        .and(qSubject.id.eq(old.getParentId())))
                .execute();
    }

    /**
     * 同步启用上级节点
     *
     * @param children
     */
    private void recursiveEnableParent(Subject children) {
        changeParentLeaf(children);
        Subject old = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(children.getAccountSetsId())
                        .and(qSubject.status.isFalse())
                        .and(qSubject.id.eq(children.getParentId())))
                .fetchFirst();
        if (old != null) {
            jqf.update(qSubject)
                    .set(qSubject.status, true)
                    .where(qSubject.accountSetsId.eq(old.getAccountSetsId())
                            .and(qSubject.id.eq(old.getId())))
                    .execute();

            if (old.getParentId() != null && old.getParentId() != 0L) {
                recursiveEnableParent(old);
            }
        }
    }

    /**
     * 保存科目
     *
     * @param entity
     * @param encLen
     */
    @Transactional(readOnly = false)
    public Subject testSaveSubject(Subject entity, Integer encLen) {
        Subject parent = null;
        Boolean parentIsLeaf = true;

        // 非必填辅助项目必须是在已选择的辅助项中，此处做一次二次过滤，确保数据有效
        if (CollUtil.isNotEmpty(entity.getAuxiliaryAccounting()) && CollUtil.isNotEmpty(entity.getNonAuxiliaryAccounting())) {
            entity.setNonAuxiliaryAccounting(entity.getNonAuxiliaryAccounting().stream()
                    .filter(a -> entity.getAuxiliaryAccounting().contains(a)).toList());
        }

        if (entity.getParentId() != null && entity.getParentId() != 0L) {
            parent = this.getById(new PrimaryKey(entity.getParentId(), entity.getAccountSetsId()));
            parentIsLeaf = parent.getLeaf();
            entity.setFullName(parent.getFullName() + "_" + entity.getName());
            entity.setMnemonicCode(PinYinUtil.getFirstLettersLo(entity.getFullName()));
            parent.setLeaf(false);

            //新增时，需要判断是否有子级，首个子级需要将父级的辅助信息传递下来
            if (entity.getId() == null) {
                //子级别数量
                long count = bqf.selectFrom(qSubject)
                        .where(qSubject.parentId.eq(entity.getParentId()).and(qSubject.accountSetsId.eq(entity.getAccountSetsId())))
                        .fetchCount();
                if (count == 0) {
                    if (StrUtil.isNotEmpty(parent.getUnit())) {
                        entity.setUnit(parent.getUnit());
                        parent.setUnit("");
                    }
                    if (CollUtil.isNotEmpty(parent.getAuxiliaryAccounting())) {
                        entity.setAuxiliaryAccounting(parent.getAuxiliaryAccounting());
                        entity.setNonAuxiliaryAccounting(parent.getNonAuxiliaryAccounting());
                        parent.setAuxiliaryAccounting(new ArrayList<>());
                        parent.setNonAuxiliaryAccounting(new ArrayList<>());
                    }

                    if (CollUtil.isNotEmpty(parent.getCurrencyAccounting())) {
                        entity.setCurrencyAccounting(parent.getCurrencyAccounting());
                        parent.setCurrencyAccounting(new ArrayList<>());
                    }
                }
            }

            subjectRepository.saveAndFlush(parent);
        } else {
            entity.setMnemonicCode(PinYinUtil.getFirstLettersLo(entity.getName()));
            entity.setFullName(entity.getName());
        }

        if (entity.getPrimaryKey() == null) {
            entity.setId(snowflakeId.generate());
            if (entity.getParentId() != null) {
                //判断父科目是否有凭证数据，有数据就创建子级别替换
                if (parent != null && parentIsLeaf && this.checkHasData(entity.getParentId(), entity.getAccountSetsId())) {
                    Subject copyChildSubject = BeanUtil.toBean(parent, Subject.class, CopyOptions.create().setIgnoreProperties("id"));
                    copyChildSubject.setId(snowflakeId.generate());
                    copyChildSubject.setParentId(entity.getParentId());
                    copyChildSubject.setCode(entity.getCode());
                    copyChildSubject.setLevel(parent.getLevel() + 1);
                    copyChildSubject.setSystemDefault(false);
                    copyChildSubject.setLeaf(true);
                    copyChildSubject.setMnemonicCode(parent.getMnemonicCode() + "_" + PinYinUtil.getFirstLettersLo(copyChildSubject.getName()));
                    copyChildSubject.setFullName(parent.getFullName() + "_" + copyChildSubject.getName());
                    copyChildSubject.setQueryCode(StrUtil.padAfter(copyChildSubject.getCode(), encLen, "0"));
                    copyChildSubject.setAuxiliaryAccounting(entity.getAuxiliaryAccounting());
                    copyChildSubject.setNonAuxiliaryAccounting(entity.getNonAuxiliaryAccounting());

                    parent.setLeaf(false);
                    parent.setQueryCode(StrUtil.padAfter(parent.getCode(), encLen, "0"));

                    parent.setUnit("");
                    parent.setAuxiliaryAccounting(new ArrayList<>());
                    parent.setCurrencyAccounting(new ArrayList<>());
                    entity.setUnit("");
                    entity.setAuxiliaryAccounting(new ArrayList<>());
                    entity.setCurrencyAccounting(new ArrayList<>());

                    subjectRepository.saveAndFlush(parent);
                    subjectRepository.saveAndFlush(copyChildSubject);

                    //更新凭证明细的关联科目
                    jqf.update(qVoucherDetails)
                            .set(qVoucherDetails.subjectId, copyChildSubject.getId())
                            .where(qVoucherDetails.accountSetsId.eq(entity.getAccountSetsId())
                                    .and(qVoucherDetails.subjectId.eq(parent.getId())))
                            .execute();

                    //  更新科目余额
                    for (Balance balance : bqf.selectFrom(qBalance)
                            .where(qBalance.accountSetsId.eq(entity.getAccountSetsId())
                                    .and(qBalance.subjectId.eq(parent.getId())))
                            .fetch()) {
                        BalanceDto dto = balanceMapper.toDto(balance);
                        dto.setId(snowflakeId.generate());
                        dto.setSubjectId(copyChildSubject.getId());
                        balanceRepository.saveAndFlush(balanceMapper.toEntity(dto));
                    }

                    //  更新科目余额
                    for (BalanceInitial balance : bqf.selectFrom(qBalanceInitial)
                            .where(qBalanceInitial.accountSetsId.eq(entity.getAccountSetsId())
                                    .and(qBalanceInitial.subjectId.eq(parent.getId())))
                            .fetch()) {
                        BalanceInitial initial = BeanUtil.toBean(balance, BalanceInitial.class, CopyOptions.create().setIgnoreProperties("id"));
                        initial.setId(snowflakeId.generate());
                        initial.setSubjectId(copyChildSubject.getId());
                        balanceInitialRepository.saveAndFlush(initial);
                    }

                    entity.setCode(NumberUtil.add(NumberUtil.parseNumber(entity.getCode()), NumberUtil.newBigInteger("1")).toString());
                }
            } else {
                entity.setParentId(0L);
            }

            if (subjectRepository.count(qSubject.accountSetsId.eq(entity.getAccountSetsId())
                    .and(qSubject.name.eq(entity.getName())
                            .and(qSubject.type.eq(entity.getType()))
                            .and(qSubject.parentId.eq(entity.getParentId() == null ? 0L : entity.getParentId())))) > 0) {
//                throw new ServiceException(entity.getName() + "科目名称已经存在！");
                Subject subject = bqf.selectFrom(qSubject).where(qSubject.accountSetsId.eq(entity.getAccountSetsId()).and(qSubject.name.eq(entity.getName())).and(qSubject.type.eq(entity.getType()).and(qSubject.parentId.eq(entity.getParentId() == null ? 0L : entity.getParentId())))).fetchFirst();
                return subject;
            }

            entity.setQueryCode(StrUtil.padAfter(entity.getCode(), encLen, "0"));
            subjectRepository.saveAndFlush(entity);
        } else {
            if (subjectRepository.count(qSubject.accountSetsId.eq(entity.getAccountSetsId())
                    .and(qSubject.name.eq(entity.getName())
                            .and(qSubject.type.eq(entity.getType()))
                            .and(qSubject.id.ne(entity.getId()))
                            .and(qSubject.status.isTrue())
                            .and(qSubject.parentId.eq(entity.getParentId() == null ? 0L : entity.getParentId())))) > 0) {
//                throw new ServiceException("科目名称已经存在！");
                List<Subject> subject = bqf.selectFrom(qSubject).where(qSubject.accountSetsId.eq(entity.getAccountSetsId()).and(qSubject.name.eq(entity.getName())).and(qSubject.type.eq(entity.getType()).and(qSubject.id.ne(entity.getId()))
                        .and(qSubject.status.isTrue()).and(qSubject.parentId.eq(entity.getParentId() == null ? 0L : entity.getParentId())))).fetch();
                return subject.get(0);
            }

            Subject original = subjectRepository.getReferenceById(entity.getPrimaryKey());
            //禁用前判断余额是否为0
            if (original.getStatus() && !entity.getStatus()) {
                BigDecimal balance = bqf.selectFrom(qBalance)
                        .select(qBalance.endBalance.sum())
                        .where(qBalance.accountSetsId.eq(entity.getAccountSetsId())
                                .and(qBalance.subjectId.eq(entity.getId())))
                        .fetchFirst();
                Assert.isTrue(balance == null || BigDecimal.ZERO.compareTo(balance) == 0, "余额不为0，不允许禁用~");

                //同时禁用下面的子节点
                jqf.update(qSubject)
                        .set(qSubject.status, entity.getStatus())
                        .where(qSubject.accountSetsId.eq(original.getAccountSetsId()).and(qSubject.code.like(original.getCode() + "%")))
                        .execute();
            }

            // 名称是否变更了
            String originalFullName = original.getFullName();
            boolean nameChanged = original.getName().equals(entity.getName());

            BeanUtil.copyProperties(entity, original, CopyOptions.create().ignoreNullValue());
            if (CollUtil.isEmpty(entity.getAuxiliaryAccounting())) {
                original.setAuxiliaryAccounting(null);
            }
            if (CollUtil.isEmpty(entity.getCurrencyAccounting())) {
                original.setCurrencyAccounting(null);
            }
            if (StrUtil.isEmpty(entity.getUnit())) {
                original.setUnit("");
            }
            entity.setQueryCode(StrUtil.padAfter(original.getCode(), encLen, "0"));
            subjectRepository.saveAndFlush(original);

            jqf.update(qSubject)
                    .set(qSubject.cash, original.getCash())
                    .where(qSubject.accountSetsId.eq(original.getAccountSetsId())
                            .and(qSubject.code.startsWith(original.getCode())))
                    .execute();

            // 名称变更了后，级联更新后代名称
            if (!nameChanged) {
                List<Subject> children = bqf.selectFrom(qSubject)
                        .where(qSubject.accountSetsId.eq(entity.getAccountSetsId())
                                .and(qSubject.code.startsWith(entity.getCode()))
                                .and(qSubject.code.ne(entity.getCode())))
                        .fetch();
                if (CollUtil.isNotEmpty(children)) {
                    for (Subject child : children) {
                        child.setFullName(StrUtil.replaceFirst(child.getFullName(), originalFullName, entity.getFullName()));
                        child.setMnemonicCode(PinYinUtil.getFirstLettersLo(child.getFullName()));
                    }
                    subjectRepository.saveAll(children);
                }
            }
        }
        return entity;
    }

    /**
     * 保存科目
     *
     * @param entity
     * @param encLen
     */
    @Transactional(isolation = Isolation.READ_UNCOMMITTED)
    @CacheRemove(value = "subject", key = "#entity.accountSetsId")
    public Subject save(Subject entity, Integer encLen) {
        Subject parent = null;
        Boolean parentIsLeaf = true;

        // 非必填辅助项目必须是在已选择的辅助项中，此处做一次二次过滤，确保数据有效
        if (CollUtil.isNotEmpty(entity.getAuxiliaryAccounting()) && CollUtil.isNotEmpty(entity.getNonAuxiliaryAccounting())) {
            entity.setNonAuxiliaryAccounting(entity.getNonAuxiliaryAccounting().stream()
                    .filter(a -> entity.getAuxiliaryAccounting().contains(a)).toList());
        }

        if (entity.getParentId() != null && entity.getParentId() != 0L) {
            parent = this.getById(new PrimaryKey(entity.getParentId(), entity.getAccountSetsId()));
            parentIsLeaf = parent.getLeaf();
            entity.setFullName(parent.getFullName() + "_" + entity.getName());
            entity.setMnemonicCode(PinYinUtil.getFirstLettersLo(entity.getFullName()));
            parent.setLeaf(false);

            //新增时，需要判断是否有子级，首个子级需要将父级的辅助信息传递下来
            if (entity.getId() == null) {
                //子级别数量
                long count = bqf.selectFrom(qSubject)
                        .where(qSubject.parentId.eq(entity.getParentId()).and(qSubject.accountSetsId.eq(entity.getAccountSetsId())))
                        .fetchCount();
                if (count == 0) {
                    if (StrUtil.isNotEmpty(parent.getUnit())) {
                        entity.setUnit(parent.getUnit());
                        parent.setUnit("");
                    }
                    if (CollUtil.isNotEmpty(parent.getAuxiliaryAccounting())) {
                        entity.setAuxiliaryAccounting(parent.getAuxiliaryAccounting());
                        entity.setNonAuxiliaryAccounting(parent.getNonAuxiliaryAccounting());
                        parent.setAuxiliaryAccounting(new ArrayList<>());
                        parent.setNonAuxiliaryAccounting(new ArrayList<>());
                    }

                    if (CollUtil.isNotEmpty(parent.getCurrencyAccounting())) {
                        entity.setCurrencyAccounting(parent.getCurrencyAccounting());
                        parent.setCurrencyAccounting(new ArrayList<>());
                    }
                }
            }

            subjectRepository.saveAndFlush(parent);
        } else {
            entity.setMnemonicCode(PinYinUtil.getFirstLettersLo(entity.getName()));
            entity.setFullName(entity.getName());
        }

        if (entity.getPrimaryKey() == null) {
            entity.setId(snowflakeId.generate());
            if (entity.getParentId() != null) {
                //判断父科目是否有凭证数据，有数据就创建子级别替换
                if (parent != null && parentIsLeaf && this.checkHasData(entity.getParentId(), entity.getAccountSetsId())) {
                    Subject copyChildSubject = BeanUtil.toBean(parent, Subject.class, CopyOptions.create().setIgnoreProperties("id"));
                    copyChildSubject.setId(snowflakeId.generate());
                    copyChildSubject.setParentId(entity.getParentId());
                    copyChildSubject.setCode(entity.getCode());
                    copyChildSubject.setLevel(parent.getLevel() + 1);
                    copyChildSubject.setSystemDefault(false);
                    copyChildSubject.setLeaf(true);
                    copyChildSubject.setMnemonicCode(parent.getMnemonicCode() + "_" + PinYinUtil.getFirstLettersLo(copyChildSubject.getName()));
                    copyChildSubject.setFullName(parent.getFullName() + "_" + copyChildSubject.getName());
                    copyChildSubject.setQueryCode(StrUtil.padAfter(copyChildSubject.getCode(), encLen, "0"));
                    copyChildSubject.setAuxiliaryAccounting(entity.getAuxiliaryAccounting());
                    copyChildSubject.setNonAuxiliaryAccounting(entity.getNonAuxiliaryAccounting());

                    parent.setLeaf(false);
                    parent.setQueryCode(StrUtil.padAfter(parent.getCode(), encLen, "0"));

                    parent.setUnit("");
                    parent.setAuxiliaryAccounting(new ArrayList<>());
                    parent.setCurrencyAccounting(new ArrayList<>());
                    entity.setUnit("");
                    entity.setAuxiliaryAccounting(new ArrayList<>());
                    entity.setCurrencyAccounting(new ArrayList<>());

                    subjectRepository.saveAndFlush(parent);
                    subjectRepository.saveAndFlush(copyChildSubject);

                    //更新凭证明细的关联科目
                    jqf.update(qVoucherDetails)
                            .set(qVoucherDetails.subjectId, copyChildSubject.getId())
                            .where(qVoucherDetails.accountSetsId.eq(entity.getAccountSetsId())
                                    .and(qVoucherDetails.subjectId.eq(parent.getId())))
                            .execute();

                    //  更新科目余额
                    for (Balance balance : bqf.selectFrom(qBalance)
                            .where(qBalance.accountSetsId.eq(entity.getAccountSetsId())
                                    .and(qBalance.subjectId.eq(parent.getId())))
                            .fetch()) {
                        BalanceDto dto = balanceMapper.toDto(balance);
                        dto.setId(snowflakeId.generate());
                        dto.setSubjectId(copyChildSubject.getId());
                        balanceRepository.saveAndFlush(balanceMapper.toEntity(dto));
                    }

                    //  更新科目余额
                    for (BalanceInitial balance : bqf.selectFrom(qBalanceInitial)
                            .where(qBalanceInitial.accountSetsId.eq(entity.getAccountSetsId())
                                    .and(qBalanceInitial.subjectId.eq(parent.getId())))
                            .fetch()) {
                        BalanceInitial initial = BeanUtil.toBean(balance, BalanceInitial.class, CopyOptions.create().setIgnoreProperties("id"));
                        initial.setId(snowflakeId.generate());
                        initial.setSubjectId(copyChildSubject.getId());
                        balanceInitialRepository.saveAndFlush(initial);
                    }

                    entity.setCode(NumberUtil.add(NumberUtil.parseNumber(entity.getCode()), NumberUtil.newBigInteger("1")).toString());
                }
            } else {
                entity.setParentId(0L);
            }

            // 同一父节点下不能同名同类型（兄弟不能重名）；二级与一级可以同名，因 parentId 不同
            if (subjectRepository.count(qSubject.accountSetsId.eq(entity.getAccountSetsId())
                    .and(qSubject.name.eq(entity.getName())
                            .and(qSubject.type.eq(entity.getType()))
                            .and(qSubject.parentId.eq(entity.getParentId() == null ? 0L : entity.getParentId())))) > 0) {
                throw new ServiceException(entity.getName() + "科目名称已经存在！");
            }

            if (subjectRepository.count(qSubject.accountSetsId.eq(entity.getAccountSetsId())
                    .and(qSubject.code.eq(entity.getCode()))) > 0) {
                throw new ServiceException(entity.getCode() + "科目编码已经存在！");
            }
            entity.setQueryCode(StrUtil.padAfter(entity.getCode(), encLen, "0"));
            subjectRepository.saveAndFlush(entity);
        } else {
            // 同一父节点下不能同名同类型
            if (subjectRepository.count(qSubject.accountSetsId.eq(entity.getAccountSetsId())
                    .and(qSubject.name.eq(entity.getName())
                            .and(qSubject.type.eq(entity.getType()))
                            .and(qSubject.id.ne(entity.getId()))
                            .and(qSubject.status.isTrue())
                            .and(qSubject.parentId.eq(entity.getParentId() == null ? 0L : entity.getParentId())))) > 0) {
                throw new ServiceException("科目名称已经存在！");
            }

            if (subjectRepository.count(qSubject.accountSetsId.eq(entity.getAccountSetsId())
                    .and(qSubject.status.isTrue())
                    .and(qSubject.id.ne(entity.getId())).and(qSubject.code.eq(entity.getCode()))) > 0) {
                throw new ServiceException("科目编码已经存在！");
            }

            Subject original = subjectRepository.getReferenceById(entity.getPrimaryKey());
            //禁用前判断余额是否为0
            if (original.getStatus() && !entity.getStatus()) {
                BigDecimal balance = bqf.selectFrom(qBalance)
                        .select(qBalance.endBalance.sum())
                        .where(qBalance.accountSetsId.eq(entity.getAccountSetsId())
                                .and(qBalance.subjectId.eq(entity.getId())))
                        .fetchFirst();
                Assert.isTrue(balance == null || BigDecimal.ZERO.compareTo(balance) == 0, "余额不为0，不允许禁用~");

                //同时禁用下面的子节点
                jqf.update(qSubject)
                        .set(qSubject.status, entity.getStatus())
                        .where(qSubject.accountSetsId.eq(original.getAccountSetsId()).and(qSubject.code.like(original.getCode() + "%")))
                        .execute();
            }

            // 名称是否变更了
            String originalFullName = original.getFullName();
            boolean nameChanged = original.getName().equals(entity.getName());

            BeanUtil.copyProperties(entity, original, CopyOptions.create().ignoreNullValue());
            if (CollUtil.isEmpty(entity.getAuxiliaryAccounting())) {
                original.setAuxiliaryAccounting(null);
            }
            if (CollUtil.isEmpty(entity.getCurrencyAccounting())) {
                original.setCurrencyAccounting(null);
            }
            if (StrUtil.isEmpty(entity.getUnit())) {
                original.setUnit("");
            }
            entity.setQueryCode(StrUtil.padAfter(original.getCode(), encLen, "0"));
            subjectRepository.saveAndFlush(original);

            jqf.update(qSubject)
                    .set(qSubject.cash, original.getCash())
                    .where(qSubject.accountSetsId.eq(original.getAccountSetsId())
                            .and(qSubject.code.startsWith(original.getCode())))
                    .execute();

            // 名称变更了后，级联更新后代名称
            if (!nameChanged) {
                List<Subject> children = bqf.selectFrom(qSubject)
                        .where(qSubject.accountSetsId.eq(entity.getAccountSetsId())
                                .and(qSubject.code.startsWith(entity.getCode()))
                                .and(qSubject.code.ne(entity.getCode())))
                        .fetch();
                if (CollUtil.isNotEmpty(children)) {
                    for (Subject child : children) {
                        child.setFullName(StrUtil.replaceFirst(child.getFullName(), originalFullName, entity.getFullName()));
                        child.setMnemonicCode(PinYinUtil.getFirstLettersLo(child.getFullName()));
                    }
                    subjectRepository.saveAll(children);
                }
            }
        }
        return entity;
    }


    @Cacheable(value = "subject", key = "#accountSetsId+':voList'")
    public List<SubjectVo> list(Long accountSetsId) {
        return list(accountSetsId, null, null);
    }

    @Cacheable(value = "subject", key = "#accountSetsId+':list:'+(#type==null?'all':#type)+(#status==null?'':#status)", unless = "#result.empty")
    public List<SubjectVo> list(Long accountSetsId, String type, Boolean status) {
        BlazeJPAQuery<Long> childrenNum = bqf.selectFrom(qParentSubject)
                .select(qParentSubject.count())
                .where(qParentSubject.parentId.eq(qSubject.id)
                        .and(qParentSubject.status.isTrue())
                        .and(qParentSubject.accountSetsId.eq(accountSetsId)));
        BooleanExpression expression = qSubject.accountSetsId.eq(accountSetsId);
        if (StrUtil.isNotEmpty(type)) {
            expression = expression.and(qSubject.type.eq(type));
        }
        if (status != null) {
            expression = expression.and(qSubject.status.eq(status));
        }
        List<Tuple> tuples = bqf.selectFrom(qSubject).select(qSubject, childrenNum).where(expression)
                .orderBy(qSubject.code.asc()).fetch();
        //辅助项id列表
        Set<Long> auxiliaryAccountingIds = tuples.stream().filter(tuple -> CollUtil.isNotEmpty(tuple.get(qSubject).getAuxiliaryAccounting()))
                .flatMap(tuple -> tuple.get(qSubject).getAuxiliaryAccounting().stream()).collect(Collectors.toSet());
        //外币id列表
        Set<Long> currencyAccountingIds = tuples.stream().filter(tuple -> CollUtil.isNotEmpty(tuple.get(qSubject).getCurrencyAccounting()))
                .flatMap(tuple -> tuple.get(qSubject).getCurrencyAccounting().stream()).collect(Collectors.toSet());

        final Dict aaMap = Dict.create();
        final Dict caMap = Dict.create();

        if (CollUtil.isNotEmpty(auxiliaryAccountingIds)) {
            bqf.selectFrom(qAccountingCategory)
                    .where(qAccountingCategory.accountSetsId.eq(accountSetsId))
                    .fetch().forEach(it -> aaMap.set(String.valueOf(it.getId()), it.getName()));
        }

        if (CollUtil.isNotEmpty(currencyAccountingIds)) {
            bqf.selectFrom(qCurrency)
                    .where(qCurrency.accountSetsId.eq(accountSetsId))
                    .fetch().forEach(it -> caMap.set(String.valueOf(it.getId()), it.getName()));
        }

        return tuples.stream()
                .map((tuple) -> {
                    SubjectVo dto = subjectMapper.toVo(tuple.get(qSubject));
                    dto.setLeaf(Optional.ofNullable(tuple.get(childrenNum)).orElse(0L) == 0L);
                    subjectMapper.toAuxiliaryAccountingNames(dto, aaMap)
                            .toCurrencyAccountingNames(dto, caMap);
                    return dto;
                })
                .collect(Collectors.toList());
    }

    @Transactional
    @CacheRemove(value = "subject", key = "#accountSetsId")
    public void delete(Long subjectId, Long accountSetsId) {
        Assert.isTrue(bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.parentId.eq(subjectId)))
                .fetchCount() == 0, "请先删除子科目~");

        Assert.isFalse(this.checkUse(subjectId, accountSetsId), "科目已被使用，不能删除！");
        Subject subject = subjectRepository.getReferenceById(new PrimaryKey(subjectId, accountSetsId));
        if (subject.getParentId() != 0L) {
            //是否只有当前一个子节点
            long count = bqf.selectFrom(qSubject)
                    .where(qSubject.parentId.eq(subject.getParentId()).and(qSubject.accountSetsId.eq(accountSetsId)))
                    .fetchCount();
            if (count == 1) {
                //删除后没有子节点的情况，需要向上透传辅助信息
                Subject parent = subjectRepository.getReferenceById(new PrimaryKey(subject.getParentId(), accountSetsId));
                parent.setUnit(StrUtil.emptyToDefault(subject.getUnit(), ""));
                parent.setAuxiliaryAccounting(subject.getAuxiliaryAccounting());
                parent.setCurrencyAccounting(subject.getCurrencyAccounting());
                parent.setLeaf(true);
                subjectRepository.save(parent);
            }
        }
        jqf.delete(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId).and(qSubject.id.eq(subjectId)))
                .execute();
    }

    @Cacheable(value = "subject", key = "#primaryKey.accountSetsId+':'+#primaryKey.id")
    public Subject getById(PrimaryKey primaryKey) {
        Subject subject = subjectRepository.findById(primaryKey).orElseThrow(() -> new ServiceException("科目信息错误"));
        bqf.detach(subject);
        long childrenCount = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(primaryKey.getAccountSetsId())
                        .and(qSubject.status.isTrue())
                        .and(qSubject.parentId.eq(subject.getId())))
                .fetchCount();
        subject.setLeaf(childrenCount == 0L);
        return subject;
    }

    @Transactional
    @Override
    @CacheRemove(value = "subject", key = "#accountSets.id")
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        try {
            Integer len = accountSetsTemplate.getEncoding().stream().reduce(Integer::sum).orElse(0);
            //编码规则是否一致
            boolean encodingEq = CollUtil.isEqualList(accountSets.getEncoding(), accountSetsTemplate.getEncoding());
            List<Subject> subjectTemplateItems = bqf.selectFrom(qSubject)
                    .where(qSubject.accountSetsId.eq(accountSetsTemplate.getId()))
                    .fetch();
            List<Subject> subjects = subjectTemplateItems.stream().collect(ArrayList::new, (list, tuple) -> {
                bqf.detach(tuple);
                Subject subject = BeanUtil.toBean(tuple, Subject.class);
                if (!encodingEq) {
                    String code = SubjectUtil.updateCode(subject.getCode(), accountSetsTemplate.getEncoding(), accountSets.getEncoding());
                    subject.setCode(code);
                }
                subject.setAccountSetsId(accountSets.getId());
                subject.setQueryCode(StrUtil.padAfter(subject.getCode(), len, "0"));
                list.add(subject);
            }, List::addAll);
            subjectRepository.saveAll(subjects);
        } catch (Exception e) {
            log.error("加载默认科目失败....", e);
            throw new ServiceException("初始化默认科目失败");
        }
    }

    @Transactional
    @Override
    @CacheRemove(value = "subject", key = "#accountSetsId")
    public void clear(Long accountSetsId) {
        jqf.delete(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId))
                .execute();
    }

    public Map<String, Subject> listByCode(Long accountSetsId, List<String> codeList) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.code.in(CollUtil.distinct(codeList))))
                .fetch().stream().collect(Collectors.toMap(Subject::getCode, s -> s));
    }

    public Subject listByCode(Long accountSetsId, String code) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.code.eq(code)))
                .fetchFirst();
    }

    public Subject listByName(Long accountSetsId, String name) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.name.eq(name)))
                .fetchFirst();
    }

    /** 按 (accountSetsId, name, type, parentId) 精确查找——与 save() 里的唯一性校验同步 */
    public Subject findByNameTypeParent(Long accountSetsId, String name, String type, Long parentId) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.name.eq(name))
                        .and(qSubject.type.eq(type))
                        .and(qSubject.parentId.eq(parentId == null ? 0L : parentId)))
                .fetchFirst();
    }

    /** 按 accountSetId + subjectId 查找科目 */
    public Subject findById(Long accountSetsId, Long subjectId) {
        return subjectRepository.findById(new PrimaryKey(subjectId, accountSetsId)).orElse(null);
    }

    /** 查找指定父科目下的第一个末级子科目 */
    public Subject findFirstLeafChild(Long accountSetsId, Long parentId) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.parentId.eq(parentId))
                        .and(qSubject.leaf.eq(true))
                        .and(qSubject.status.eq(true)))
                .orderBy(qSubject.code.asc())
                .fetchFirst();
    }

    public String getMaxChildCode(Long parentId, Long accountSetsId) {
        return bqf.selectFrom(qSubject)
                .select(qSubject.code.max())
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.parentId.eq(parentId)))
                .fetchFirst();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<?> backupType() {
        return Subject.class;
    }

    @Override
    @CacheRemove(value = "subject", key = "#accountSetsId")
    public void recover(Long accountSetsId, String json) {
        List<Subject> entityList = JSON.parseArray(json, Subject.class);
        for (Subject entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            subjectRepository.save(entity);
        }
    }

    @Transactional
    @CacheRemove(value = "subject", key = "#accountSetsId")
    public void batchSave(Long accountSetsId, List<Subject> subjects) {
        subjectRepository.saveAll(subjects);
    }

    /**
     * 根据科目名称加载科目
     *
     * @param subjectName
     * @param accountSetsId
     * @return
     */
    public Subject loadByName(String subjectName, Long accountSetsId) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.name.eq(subjectName)
                        .and(qSubject.status.isTrue())
                        .and(qSubject.accountSetsId.eq(accountSetsId)))
                .orderBy(qSubject.id.desc())
                .fetchFirst();
    }

    public Map<Long, SubjectVo> loadById(Set<Long> subjectIds, Long accountSetsId) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.id.in(subjectIds).and(qSubject.accountSetsId.eq(accountSetsId)))
                .fetch().stream().map(subjectMapper::toVo).collect(Collectors.toMap(SubjectVo::getId, s -> s));
    }

    @Cacheable(value = "subject", key = "#accountSetsId+':nodeSelect'")
    public List<SubjectVo> nodeSelect(Long accountSetsId) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.status.isTrue())
                        .and(qSubject.leaf.isFalse()))
                .orderBy(qSubject.queryCode.asc())
                .fetch().stream().map(subjectMapper::toVo).collect(Collectors.toList());
    }

    /**
     * 明细转辅助项目
     *
     * @param accountSetsId
     * @param userId
     * @param subjectCode
     * @param subjectId
     * @param categoryId
     */
    @Transactional
    @CacheRemove(value = "subject", key = "#accountSetsId")
    public void toAuxiliary(Long accountSetsId, Long userId, String subjectCode, Long subjectId, Long categoryId) {
        List<Subject> subjects = bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(accountSetsId)
                        .and(qSubject.id.ne(subjectId))
                        .and(qSubject.code.like(subjectCode + "%")))
                .fetch();
        Assert.notEmpty(subjects, "科目没有明细项～");

        Assert.isFalse(subjects.stream().anyMatch(s -> CollUtil.isNotEmpty(s.getAuxiliaryAccounting()) || CollUtil.isNotEmpty(s.getCurrencyAccounting())
                || StrUtil.isNotEmpty(s.getUnit())), "科目的下级含有数量、外币、辅助核算时，不支持转换,请检查下级科目～");

        //备份
        backupService.backup(userId, accountSetsId);

        Optional<Subject> optional = subjectRepository.findById(new PrimaryKey(subjectId, accountSetsId));
        AccountingCategory category = accountingCategoryRepository.getReferenceById(new PrimaryKey(categoryId, accountSetsId));
        AuxiliaryDataService dataService = SpringUtil.getBean(category.getCategoryType().dataService);

        optional.ifPresent(subject -> {
            List<Long> auxiliaryAccounting = subject.getAuxiliaryAccounting();
            if (CollUtil.isEmpty(auxiliaryAccounting)) {
                auxiliaryAccounting = new ArrayList<>();
            }
            auxiliaryAccounting.add(categoryId);
            subject.setAuxiliaryAccounting(auxiliaryAccounting);

            subjectRepository.save(subject);

            //根科目
            List<Subject> leafSubject = subjects.stream().filter(Subject::getLeaf).toList();

            Map<Long, Long> acDetails = new HashMap<>();
            for (Subject s : leafSubject) {
                AcDetails ad = new AcDetails();
                ad.setId(snowflakeId.generate());
                ad.setName(s.getName());
                ad.setCode(RandomUtil.randomNumbers(4));
                ad.setEnable(true);
                ad.setCategoryId(categoryId);
                ad.setCategoryType(category.getCategoryType());
                ad.setAccountSetsId(accountSetsId);
                dataService.save(BeanUtil.toBean(ad, category.getCategoryType().entity));

                acDetails.put(s.getId(), ad.getId());
            }

            Set<Long> subjectIds = leafSubject.stream().map(Subject::getId).collect(Collectors.toSet());

            //科目关联的所有凭证项
            List<VoucherDetails> detailsList = bqf.selectFrom(qVoucherDetails)
                    .where(qVoucherDetails.subjectId.in(subjectIds).and(qVoucherDetails.accountSetsId.eq(accountSetsId)))
                    .fetch();

            Map<Long, Long> sAcDetailMap = new HashMap<>();

            for (VoucherDetails voucherDetails : detailsList) {
                if (!sAcDetailMap.containsKey(voucherDetails.getSubjectId())) {
                    List<Detail> details = new ArrayList<>();
                    Detail detail = new Detail();
                    detail.setCategoryId(categoryId);
                    detail.setCategoryType(category.getCategoryType());
                    detail.setValue(acDetails.get(voucherDetails.getSubjectId()));
                    details.add(detail);

                    if (voucherDetails.getAuxiliaryDetailId() != null) {
                        AuxiliaryDetail auxiliaryDetail = bqf.selectFrom(qAuxiliaryDetail)
                                .where(qAuxiliaryDetail.accountSetsId.eq(accountSetsId).and(qAuxiliaryDetail.id.eq(voucherDetails.getAuxiliaryDetailId())))
                                .fetchFirst();
                        if (auxiliaryDetail != null && CollUtil.isNotEmpty(auxiliaryDetail.getDetails())) {
                            details.addAll(auxiliaryDetail.getDetails());
                        }
                    }

                    //构建辅助项id组合键
                    String calCol = details.stream()
                            .sorted(Comparator.comparing(Detail::getCategoryId))
                            .map(s -> String.valueOf(s.getValue()))
                            .collect(Collectors.joining("_"));

                    //查询组合键是否存在
                    Long detailId = bqf.selectFrom(qAuxiliaryDetail)
                            .select(qAuxiliaryDetail.id)
                            .where(qAuxiliaryDetail.calCol.eq(calCol).and(qAuxiliaryDetail.accountSetsId.eq(accountSetsId)))
                            .fetchFirst();

                    if (detailId != null) {
                        voucherDetails.setAuxiliaryDetailId(detailId);
                    } else {
                        //创建辅助组合明细记录
                        AuxiliaryDetail ad = new AuxiliaryDetail();
                        ad.setDetails(details);
                        ad.setCalCol(calCol);
                        ad.setId(snowflakeId.generate());
                        ad.setAccountSetsId(accountSetsId);
                        auxiliaryDetailRepository.save(ad);
                        voucherDetails.setAuxiliaryDetailId(ad.getId());
                    }

                    //保留已经创建的组合id
                    sAcDetailMap.put(voucherDetails.getSubjectId(), voucherDetails.getAuxiliaryDetailId());
                } else {
                    voucherDetails.setAuxiliaryDetailId(sAcDetailMap.get(voucherDetails.getSubjectId()));
                }

                voucherDetails.setSubjectId(subjectId);

                voucherDetailsRepository.save(voucherDetails);
            }

            //删除明细项目
            subjectRepository.deleteAll(subjects);

            //重置余额表
            Optional<LocalDate> minDate = detailsList.stream().map(VoucherDetails::getVoucherDate).distinct().min(LocalDate::compareTo);

            minDate.ifPresent(d -> balanceCalcService.calc(accountSetsId, YearMonth.of(d.getYear(), d.getMonthValue())));
        });
    }

    /**
     * 获取第一个叶子节点
     *
     * @param subject
     * @return
     */
    public Subject loadFirstLeafChildren(Subject subject) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(subject.getAccountSetsId())
                        .and(qSubject.leaf.isTrue())
                        .and(qSubject.code.like(subject.getCode() + "%")))
                .orderBy(qSubject.queryCode.asc(), qSubject.id.asc())
                .fetchFirst();
    }

    /**
     * 根据前台预构建的科目信息创建子科目信息
     *
     * @param newSubjectName
     * @param accountSet     1、智能匹配六大往来（应收账款、其他应收款、预收账款、
     *                       应付账款、其他应付款和预付账款）的客户/供应商
     *                       2、自动新增对应的往来单位
     * @return
     */
    @Transactional
    @CacheRemove(value = "subject", key = "#accountSet.id")
    public Tuple2<Long, AcDetails> createLeaf(Long subjectId, String newSubjectName, AccountSets accountSet) {
        Subject topSubject = this.getById(new PrimaryKey(subjectId, accountSet.getId()));

        AccountingCategoryType categoryType = null;
        if (CollUtil.contains(Arrays.asList(ys), topSubject.getName()) && CollUtil.isNotEmpty(topSubject.getAuxiliaryAccounting())) {
            if (!topSubject.getAuxiliaryAccounting().contains((long) AccountingCategoryType.CUSTOMER.ordinal())) {
                throw new ServiceException(topSubject.getName() + "没有关联客户辅助核算，无法自动匹配生成凭证");
            }
            categoryType = AccountingCategoryType.CUSTOMER;
        } else if (CollUtil.contains(Arrays.asList(yf), topSubject.getName()) && CollUtil.isNotEmpty(topSubject.getAuxiliaryAccounting())) {
            if (!topSubject.getAuxiliaryAccounting().contains((long) AccountingCategoryType.VENDOR.ordinal())) {
                throw new ServiceException(topSubject.getName() + "没有关联供应商辅助核算，无法自动匹配生成凭证");
            }
            categoryType = AccountingCategoryType.VENDOR;
        }

        // 关联辅助核算，不建子科目
        if (categoryType != null) {
            Assert.isTrue(topSubject.getLeaf(), topSubject.getCode() + topSubject.getName() + "配置了辅助项，但不是末级科目，请修改模板~");
            AuxiliaryDataService dataService = SpringUtil.getBean(categoryType.dataService);
            AcDetails acDetails = dataService.loadByName(accountSet.getId(), (long) categoryType.ordinal(), newSubjectName);
            if (acDetails == null) {
                acDetails = new AcDetails();
                acDetails.setCategoryType(categoryType);
                acDetails.setCategoryId((long) categoryType.ordinal());
                acDetails.setName(newSubjectName);
                acDetails.setAccountSetsId(accountSet.getId());

                String maxCode = dataService.loadMaxCode(accountSet.getId(), (long) categoryType.ordinal());

                if (StrUtil.isNotEmpty(maxCode) && NumberUtil.isNumber(maxCode)) {
                    Number number = NumberUtil.parseNumber(maxCode, 1);
                    BigDecimal add = NumberUtil.add(number, 1);
                    acDetails.setCode(add.toString());
                } else {
                    acDetails.setCode("1001");
                }
                acDetails = dataService.save(BeanUtil.toBean(acDetails, categoryType.entity));
            }

            return Tuples.of(subjectId, acDetails);
        }

        Subject subject = bqf.selectFrom(qSubject)
                .where(qSubject.parentId.eq(subjectId)
                        .and(qSubject.accountSetsId.eq(accountSet.getId()))
                        .and(qSubject.name.eq(newSubjectName))
                ).fetchFirst();

        // 已对应的科目名称不存在下级就创建新的下级科目
        if (subject == null) {
            // 获取下级最大科目编码
            String code = bqf.selectFrom(qSubject)
                    .select(qSubject.code.max())
                    .where(qSubject.parentId.eq(subjectId)
                            .and(qSubject.accountSetsId.eq(accountSet.getId()))
                    ).fetchFirst();

            // 将上级信息复制到下级
            subject = BeanUtil.toBean(topSubject, Subject.class);

            if (StrUtil.isNotEmpty(code)) {
                // 有下级科目最大值时，科目编码递增
                subject.setCode((Long.parseLong(code) + 1) + "");
                Assert.isTrue(subject.getCode().startsWith(topSubject.getCode()), topSubject.getCode() + "下级编码长度不够，请修改科目编码规则~");
            } else {
                List<String> code1 = SubjectUtil.checkCode(topSubject.getCode(), accountSet.getEncoding());
                // 没有下级科目最大编码，则按规则编号
                subject.setCode(topSubject.getCode() + StrUtil.padPre("1", accountSet.getEncoding().get(code1.size()), "0"));
            }

            // 覆盖从父级科目继承过来的科目信息
            subject.setId(null);
            subject.setParentId(topSubject.getId());
            subject.setLeaf(true);
            subject.setSystemDefault(false);
            subject.setLevel(topSubject.getLevel() + 1);
            subject.setName(newSubjectName);

            subject = this.save(subject, accountSet.getEncoding().stream().reduce(Integer::sum).orElse(0));
        } else {
            subject.setStatus(true);
            subjectRepository.save(subject);
        }

        return Tuples.of(subject.getId(), new AcDetails());
    }

    /**
     * 根据账套模板ID获取模板配置的科目列表
     *
     * @param astId
     * @return
     */
    public List<Subject> listByAstId(Long astId) {
        return bqf.selectFrom(qSubject)
                .where(qSubject.accountSetsId.eq(astId))
                .orderBy(qSubject.code.asc())
                .fetch();
    }
}
