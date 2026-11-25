package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.dto.RoleDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.mapper.RoleDtoMapper;
import cn.gson.financial.kernel.model.repo.RoleRepository;
import cn.gson.financial.kernel.model.repo.UserAccountSetsRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年09月23日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RoleService extends AbsService implements AccountSetsProcessor, BackupProcessor {
    private final RoleDtoMapper roleDtoMapper;

    private final RoleRepository roleRepository;

    private final UserAccountSetsRepository userAccountSetsRepository;

    private final QRole qRole = QRole.role;

    private final QPermissions qPermissions = QPermissions.permissions;

    private final QAccountSets qAccountSets = QAccountSets.accountSets;

    private final QUserAccountSets qUserAccountSets = QUserAccountSets.userAccountSets;

    /**
     * 账套数据初始化
     *
     * @param accountSets
     * @param accountSetsTemplate
     */
    @Override
    @Transactional
    public void init(AccountSets accountSets, AccountSetsTemplate accountSetsTemplate) {
        List<Role> roleList = bqf.selectFrom(qRole)
                .where(qRole.accountSetsId.eq(0L))
                .fetch();
        List<Role> list = roleList.stream().map(s -> {
            bqf.detach(s);
            Role role = BeanUtil.toBean(s, Role.class);
            role.setAccountSetsId(accountSets.getId());
            role.setId(snowflakeId.generate());
            return role;
        }).toList();
        roleRepository.saveAllAndFlush(list);
    }

    /**
     * 账套数据清理
     *
     * @param accountSetsId
     */
    @Override
    public void clear(Long accountSetsId) {
        jqf.delete(qRole)
                .where(qRole.accountSetsId.eq(accountSetsId))
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
        return JSON.toJSONString(bqf.selectFrom(qRole)
                .where(qRole.accountSetsId.eq(accountSetsId)).fetch());
    }

    /**
     * 备份类型
     *
     * @return
     */
    @Override
    public Class<?> backupType() {
        return Role.class;
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
        List<Role> roles = JSON.parseArray(json, Role.class);

        roleRepository.saveAll(roles.stream().peek(role -> role.setAccountSetsId(accountSetsId)).toList());
    }

    public List<Role> list(Long accountSetsId) {
        return bqf.selectFrom(qRole)
                .where(qRole.accountSetsId.eq(accountSetsId).and(qRole.enabled.isTrue()))
                .orderBy(qRole.id.asc())
                .fetch();
    }

    @Transactional
    public void delete(Long accountSetsId, Long roleId) {
        Assert.isTrue(bqf.selectFrom(qUserAccountSets)
                .where(qUserAccountSets.roleId.eq(roleId)
                        .and(qUserAccountSets.accountSetsId.eq(accountSetsId)))
                .fetchCount() == 0, "角色已被使用，不能删除哦~");

        jqf.delete(qRole)
                .where(qRole.accountSetsId.eq(accountSetsId)
                        .and(qRole.id.eq(roleId))
                        .and(qRole.systemDefault.isFalse()))
                .execute();
    }

    @Transactional
    public void deleteAny(Long accountSetsId, Long roleId) {
        jqf.delete(qRole)
                .where(qRole.accountSetsId.eq(accountSetsId)
                        .and(qRole.id.eq(roleId)))
                .execute();
    }

    @Transactional
    public Role save(RoleDto roleDto) {
        Role role = roleDtoMapper.toEntity(roleDto);
        if (role.getPrimaryKey() != null) {
            Optional<Role> reference = roleRepository.findById(role.getPrimaryKey());
            Assert.isFalse(reference.isEmpty(), "角色不存在~");
            reference.ifPresent(r -> {
                BeanUtil.copyProperties(role, r, CopyOptions.create().ignoreNullValue());
                roleRepository.saveAndFlush(r);
            });
        } else {
            role.setEnabled(true);
            roleRepository.saveAndFlush(role);
        }
        return role;
    }

    @Transactional
    public void initAllAs() {
        List<Long> accountSets = bqf.selectFrom(qAccountSets)
                .select(qAccountSets.id)
                .where(qAccountSets.id.notIn(bqf.selectFrom(qRole)
                        .select(qRole.accountSetsId)))
                .fetch();
        List<UserAccountSets> userAccountSets = bqf.selectFrom(qUserAccountSets)
                .where(qUserAccountSets.roleId.isNull()).fetch();

        Map<Long, List<UserAccountSets>> collected = userAccountSets.stream().collect(Collectors.groupingBy(UserAccountSets::getAccountSetsId));

        List<Role> roleList = bqf.selectFrom(qRole)
                .where(qRole.accountSetsId.eq(0L))
                .fetch().stream().map(r -> Hibernate.unproxy(r, Role.class)).toList();
        for (Long asId : accountSets) {
            List<Role> list = roleList.stream().map(r -> {
                Role bean = BeanUtil.toBean(r, Role.class);
                bean.setAccountSetsId(asId);
                bean.setId(snowflakeId.generate());
                return bean;
            }).toList();
            roleRepository.saveAll(list);
            List<UserAccountSets> userList = collected.get(asId);
            if (CollUtil.isNotEmpty(userList)) {
                for (UserAccountSets sets : userList) {
                    Role r = null;
                    switch (sets.getRoleType()) {
                        case Manager -> {
                            r = CollUtil.findOneByField(list, "name", "账套管理员");
                        }
                        case Director -> {
                            r = CollUtil.findOneByField(list, "name", "主管");
                        }
                        case Making -> {
                            r = CollUtil.findOneByField(list, "name", "制单人");
                        }
                        case Cashier -> {
                            r = CollUtil.findOneByField(list, "name", "出纳");
                        }
                        case View -> {
                            r = CollUtil.findOneByField(list, "name", "查看");
                        }
                    }
                    if (r != null) {
                        sets.setRoleId(r.getId());
                    }
                }
            }
        }

        userAccountSetsRepository.saveAll(userAccountSets);
    }

    /**
     * 获取账套管理角色
     *
     * @param accountSetsId
     * @return
     */
    public Role getAstAdmin(Long accountSetsId) {
        Role role = bqf.selectFrom(qRole)
                .where(qRole.accountSetsId.eq(accountSetsId)
                        .and(qRole.systemDefault.isTrue())
                        .and(qRole.astAdmin.isTrue()))
                .fetchFirst();
        Assert.notNull(role, "账套未初始化账套管理员角色，请联系管理员~");
        return role;
    }

    @Transactional
    public Role load(Long accountSetsId, Long roleId) {
        Role role = bqf.selectFrom(qRole)
                .where(qRole.id.eq(roleId).and(qRole.accountSetsId.eq(accountSetsId)))
                .fetchFirst();
        if (role == null) {
            role = bqf.selectFrom(qRole)
                    .where(qRole.accountSetsId.eq(accountSetsId)
                            .and(qRole.systemDefault.isTrue())
                            .and(qRole.astAdmin.isTrue()))
                    .fetchFirst();
            if (role != null) {
                jqf.update(qUserAccountSets)
                        .set(qUserAccountSets.roleId, role.getId())
                        .where(qUserAccountSets.accountSetsId.eq(accountSetsId)
                                .and(qUserAccountSets.roleId.eq(roleId)))
                        .execute();
            }
        }
        Assert.notNull(role, "未找到关联角色，请检查账套角色~");

        role = BeanUtil.toBean(role, Role.class);

        if (CollUtil.isNotEmpty(role.getAuthorities())) {
            List<String> authorities = bqf.selectFrom(qPermissions)
                    .select(qPermissions.key)
                    .where(qPermissions.id.in(role.getAuthorities().stream().mapToLong(Long::parseLong).boxed().toList()))
                    .fetch();
            role.setAuthorities(authorities);
        }
        return role;
    }
}
