package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.ProjectDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.ProjectMapper;
import cn.gson.financial.kernel.model.repo.ProjectRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.blazebit.persistence.PagedList;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.dsl.BooleanExpression;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (Project)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-04-05 14:05:02</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProjectService extends AbsAuxiliaryDataService<Project> {
    private final ProjectMapper projectMapper;

    private final QProject qProject = QProject.project;
    private final QDepartment qDepartment = QDepartment.department;

    private final ProjectRepository projectRepository;

    public PageResults<ProjectDto> list(Page page, String filter, Long accountSetsId) {
        BooleanExpression expression = qProject.accountSetsId.eq(accountSetsId);
        if (StrUtil.isNotEmpty(filter)) {
            expression = qProject.code.contains(filter).or(qProject.name.contains(filter));
        }
        PagedList<Tuple> pagedList = bqf.selectFrom(qProject)
                .select(qProject, qDepartment.name)
                .leftJoin(qDepartment).on(qDepartment.id.eq(qProject.departmentId).and(qDepartment.accountSetsId.eq(accountSetsId)))
                .where(expression)
                .orderBy(qProject.code.castToNum(Long.class).asc(), qProject.id.desc(), qProject.accountSetsId.asc())
                .fetchPage(page.getOffset(), page.getOffsetEnd());
        List<ProjectDto> projectDtoList = pagedList.stream().map(tuple -> {
            ProjectDto dto = projectMapper.toDto(tuple.get(qProject));
            dto.setDepartmentName(tuple.get(qDepartment.name));
            return dto;
        }).collect(Collectors.toList());
        return new PageResults<>(projectDtoList, page, pagedList.getTotalSize());
    }

    @Transactional
    public void delete(PrimaryKey primaryKey) {
        projectRepository.deleteById(primaryKey);
    }

    @Override
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        List<Project> flows = bqf.selectFrom(qProject)
                .where(qProject.accountSetsId.eq(accountSetsTemplate.getId()))
                .fetch();

        if (CollUtil.isNotEmpty(flows)) {
            projectRepository.saveAll(flows.stream().map(s -> {
                bqf.detach(s);
                Project bean = BeanUtil.toBean(s, Project.class);
                bean.setAccountSetsId(accountSets.getId());
                bean.setCategoryType(AccountingCategoryType.PROJECT);
                return bean;
            }).collect(Collectors.toList()));
        }
    }

    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qProject)
                .where(qProject.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qProject)
                .where(qProject.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<Project> backupType() {
        return Project.class;
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
            List<Project> javaList = JSONArray.parseArray(json).toJavaList(backupType());
            javaList.forEach(s -> s.setAccountSetsId(accountSetsId));
            projectRepository.saveAll(javaList);
        }
    }

    @Override
    public JpaRepository<Project, PrimaryKey> getRepository() {
        return projectRepository;
    }

    @Override
    public QAcDetails getQ() {
        return qProject._super;
    }

    @Override
    public AccountingCategoryType getType() {
        return AccountingCategoryType.PROJECT;
    }
}


