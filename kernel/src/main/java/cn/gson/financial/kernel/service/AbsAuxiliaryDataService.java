package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.common.PinYinUtil;
import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.AccountingCategoryDetailsCheck;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.blazebit.persistence.PagedList;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年04月09日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
public abstract class AbsAuxiliaryDataService<E extends AcDetails> extends AbsService implements AccountSetsProcessor, BackupProcessor, AuxiliaryDataService {

    public JpaRepository<E, PrimaryKey> getRepository() {
        return null;
    }

    public QAcDetails getQ() {
        return null;
    }

    public abstract AccountingCategoryType getType();

    @Transactional
    @Override
    public void clear(Long categoryId, Long accountSetsId) {
        List<AcDetails> details = bqf.selectFrom(getQ()).where(getQ().accountSetsId.eq(accountSetsId)).fetch();
        for (AcDetails detail : details) {
            try {
                this.delete(new PrimaryKey(detail.getId(), accountSetsId));
            } catch (Exception e) {
                log.info("accountSetsId:{},categoryId:{},detailId：{},已经被使用不能删除～", accountSetsId, categoryId, detail.getId());
            }
        }
    }

    @Override
    @Transactional
    public Map<String, Integer> importData(Long accountSetsId, Long categoryId, AccountingCategoryType categoryType, Map<String, AcDetails> detailsMap) {
        Map<String, Integer> result = new HashMap<>(4);
        result.put("total", detailsMap.size());

        if (AccountingCategoryType.EMPLOYEE.equals(categoryType) || AccountingCategoryType.PROJECT.equals(categoryType)) {
            Set<String> departmentNames = detailsMap.values().stream().map(d -> {
                if (d instanceof Employee) {
                    Employee employee = (Employee) d;
                    return employee.getDepartmentName();
                } else {
                    Project project = (Project) d;
                    return project.getDepartmentName();
                }
            }).filter(StrUtil::isNotEmpty).collect(Collectors.toSet());

            Map<String, Long> deptMap = bqf.selectFrom(QDepartment.department)
                    .select(QDepartment.department.id, QDepartment.department.name)
                    .where(QDepartment.department.name.in(departmentNames)
                            .and(QDepartment.department.accountSetsId.eq(accountSetsId)))
                    .fetch().stream().collect(Collectors.toMap(tuple -> tuple.get(QDepartment.department.name), tuple -> tuple.get(QDepartment.department.id)));
            detailsMap.values().forEach(row -> {
                if (row instanceof Employee) {
                    Employee employee = (Employee) row;
                    if (StrUtil.isNotEmpty(employee.getDepartmentName())) {
                        Long deptId = deptMap.get(employee.getDepartmentName());
                        Assert.notNull(deptId, "请先创建部门：" + employee.getDepartmentName());
                        employee.setDepartmentId(deptId);
                    }
                } else {
                    Project project = (Project) row;
                    if (StrUtil.isNotEmpty(project.getDepartmentName())) {
                        Long deptId = deptMap.get(project.getDepartmentName());
                        Assert.notNull(deptId, "请先创建部门：" + project.getDepartmentName());
                        project.setDepartmentId(deptId);
                    }
                }
            });
        }

        //判断是否已经在系统存在
        //已存在的数据
        List<AcDetails> existing = bqf.selectFrom(getQ())
                .where(getQ().categoryId.eq(categoryId)
                        .and(getQ().accountSetsId.eq(accountSetsId))
                        .and(getQ().code.in(detailsMap.keySet())))
                .fetch();
        result.put("updated", existing.size());
        for (AcDetails acDetails : existing) {
            AcDetails d = detailsMap.get(acDetails.getCode());
            if (d != null) {
                d.setId(acDetails.getId());
            }
        }

        //新插入的
        List<AcDetails> inserted = detailsMap.values().stream().filter(d -> d.getId() == null).toList();
        result.put("inserted", inserted.size());

        List<E> rows = detailsMap.values().stream().map(d -> (E) d).collect(Collectors.toList());
        getRepository().saveAll(rows);
        return result;
    }

    @Override
    @Transactional
    public AcDetails save(AcDetails entity) {
        entity.setMnemonics(PinYinUtil.getFirstLettersLo(entity.getName()));
        if (entity.getPrimaryKey() != null) {
            Optional<E> original = getRepository().findById(entity.getPrimaryKey());
            if (original.isPresent()) {
                E e = original.get();
                if (!e.getCode().equalsIgnoreCase(entity.getCode())) {
                    Assert.isTrue(bqf.selectFrom(getQ())
                                          .where(getQ().categoryId.eq(entity.getCategoryId())
                                                  .and(getQ().id.ne(e.getId()))
                                                  .and(getQ().accountSetsId.eq(entity.getAccountSetsId()))
                                                  .and(getQ().code.eq(entity.getCode())))
                                          .fetchCount() == 0, "编码：" + entity.getCode() + "已重复,请检查~");
                }
                BeanUtil.copyProperties(entity, e, CopyOptions.create().ignoreNullValue());
                entity = getRepository().save(e);
            } else {
                getRepository().saveAndFlush((E) entity);
            }
        } else {
            Assert.isTrue(bqf.selectFrom(getQ())
                                  .where(getQ().categoryId.eq(entity.getCategoryId())
                                          .and(getQ().accountSetsId.eq(entity.getAccountSetsId()))
                                          .and(getQ().code.eq(entity.getCode())))
                                  .fetchCount() == 0, "编码：" + entity.getCode() + "已重复,请检查~");
            entity.setId(snowflakeId.generate());
            entity.setCategoryType(getType());
            entity = getRepository().saveAndFlush((E) entity);
        }
        return entity;
    }

    @Transactional
    public void delete(PrimaryKey primaryKey) {
        Map<String, AccountingCategoryDetailsCheck> acdcBeans = SpringUtil.getBeansOfType(AccountingCategoryDetailsCheck.class);
        E reference = getRepository().getReferenceById(primaryKey);
        if (CollUtil.isNotEmpty(acdcBeans)) {
            Assert.isTrue(acdcBeans.values().stream().noneMatch(s -> s.isAccountingCategoryDetailsUsed(reference)), "已被使用，不能删除，请检查期初，凭证，资产或资金模块~");
        }
        AccountingCategoryDetailsService detailsService = SpringUtil.getBean(AccountingCategoryDetailsService.class);
        detailsService.delRelate(primaryKey, reference);
        getRepository().deleteById(primaryKey);
    }

    @Override
    public List<? extends AcDetails> loadData(Long accountSetsId) {
        return loadData(accountSetsId, null);
    }

    @Override
    public List<? extends AcDetails> loadData(Long accountSetsId, Long categoryId) {
        return loadData(accountSetsId, categoryId, null);
    }

    @Override
    public List<? extends AcDetails> loadData(Long accountSetsId, Long categoryId, String filter) {
        BooleanExpression expression = getExpression(accountSetsId, categoryId, filter);
        return bqf.selectFrom(getQ())
                .where(expression)
                .orderBy(getQ().code.castToNum(Integer.class).asc(), getQ().id.asc())
                .fetch();
    }

    @Override
    public PageResults<? extends AcDetails> loadPageData(Long accountSetsId, Page page) {
        return loadPageData(accountSetsId, page, null);
    }

    @Override
    public PageResults<? extends AcDetails> loadPageData(Long accountSetsId, Page page, String filter) {
        return loadPageData(accountSetsId, null, page, filter);
    }

    @Override
    public PageResults<? extends AcDetails> loadPageData(Long accountSetsId, Long categoryId, Page page) {
        return loadPageData(accountSetsId, categoryId, page, null);
    }

    @Override
    public PageResults<? extends AcDetails> loadPageData(Long accountSetsId, Long categoryId, Page page, String filter) {
        BooleanExpression expression = getExpression(accountSetsId, categoryId, filter);
        PagedList<AcDetails> pagedList = bqf.selectFrom(getQ())
                .where(expression)
                .orderBy(getQ().code.castToNum(Integer.class).asc(), getQ().id.asc(), getQ().accountSetsId.asc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());
        return new PageResults<>(pagedList, page);
    }

    @Override
    public Map<Long, ? extends AcDetails> loadDataByIdList(Long accountSetsId, Set<Long> detailsIdList) {
        return loadDataByIdList(accountSetsId, null, detailsIdList);
    }

    @Override
    public Map<Long, ? extends AcDetails> loadDataByIdList(Long accountSetsId, Long categoryId, Set<Long> detailsIdList) {
        return loadListByIdList(accountSetsId, categoryId, detailsIdList).stream().collect(Collectors.toMap(AcDetails::getId, a -> a));
    }

    @Override
    public List<? extends AcDetails> loadListByIdList(Long accountSetsId, Set<Long> detailsIdList) {
        return loadListByIdList(accountSetsId, null, detailsIdList);
    }

    @Override
    public List<? extends AcDetails> loadListByIdList(Long accountSetsId, Long categoryId, Set<Long> detailsIdList) {
        detailsIdList = detailsIdList.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        if (CollUtil.isEmpty(detailsIdList)) {
            return new ArrayList<>();
        }
        BooleanExpression expression = getQ().accountSetsId.eq(accountSetsId).and(getQ().id.in(detailsIdList));
        if (categoryId != null) {
            expression = expression.and(getQ().categoryId.eq(categoryId));
        }
        return bqf.selectFrom(getQ())
                .where(expression)
                .orderBy(getQ().code.castToNum(Integer.class).asc(), getQ().id.asc(), getQ().accountSetsId.asc())
                .fetch();
    }

    @Override
    public AcDetails loadByCode(Long accountSetsId, Long categoryId, String code) {
        return bqf.selectFrom(getQ())
                .where(getQ().categoryId.eq(categoryId)
                        .and(getQ().code.eq(code))
                        .and(getQ().accountSetsId.eq(accountSetsId)))
                .orderBy(getQ().code.castToNum(Integer.class).asc(), getQ().id.asc(), getQ().accountSetsId.asc())
                .fetchFirst();
    }

    @Override
    public String loadMaxCode(Long accountSetsId, Long categoryId) {
        return bqf.selectFrom(getQ())
                .select(getQ().code)
                .where(getQ().categoryId.eq(categoryId)
                        .and(getQ().accountSetsId.eq(accountSetsId)))
                .orderBy(getQ().code.castToNum(Integer.class).desc(), getQ().id.desc(), getQ().accountSetsId.asc())
                .fetchFirst();
    }

    @Override
    public AcDetails loadByName(Long accountSetsId, Long categoryId, String name) {
        return bqf.selectFrom(getQ())
                .where(getQ().categoryId.eq(categoryId)
                        .and(getQ().name.eq(name))
                        .and(getQ().accountSetsId.eq(accountSetsId)))
                .orderBy(getQ().code.castToNum(Integer.class).asc(), getQ().id.asc(), getQ().accountSetsId.asc())
                .fetchFirst();
    }

    @Override
    public Optional<AcDetails> loadById(Long accountSetsId, Long categoryId, Long id) {
        if (categoryId == null || id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(bqf.selectFrom(getQ())
                .where(getQ().categoryId.eq(categoryId)
                        .and(getQ().id.eq(id))
                        .and(getQ().accountSetsId.eq(accountSetsId)))
                .orderBy(getQ().code.castToNum(Integer.class).asc(), getQ().id.asc(), getQ().accountSetsId.asc())
                .fetchFirst());
    }

    private BooleanExpression getExpression(Long accountSetsId, Long categoryId, String filter) {
        BooleanExpression expression = getQ().accountSetsId.eq(accountSetsId);
        if (categoryId != null) {
            expression = expression.and(getQ().categoryId.eq(categoryId));
        }
        if (StrUtil.isNotEmpty(filter)) {
            expression = expression.and(getQ().code.contains(filter).or(getQ().name.contains(filter)));
        }
        return expression;
    }
}
