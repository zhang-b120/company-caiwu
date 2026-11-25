package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.entity.Permissions;
import cn.gson.financial.kernel.model.entity.QPermissions;
import cn.gson.financial.kernel.model.repo.PermissionsRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年10月13日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PermissionsServices extends AbsService {

    private final PermissionsRepository permissionsRepository;

    private final QPermissions qPermissions = QPermissions.permissions;

    public List<Permissions> list() {
        return bqf.selectFrom(qPermissions)
                .orderBy(qPermissions.pos.asc(), qPermissions.id.asc())
                .fetch();
    }

    @Transactional
    public void delete(Long permissionsId) {
        Assert.isTrue(bqf.selectFrom(qPermissions)
                .where(qPermissions.parentId.eq(permissionsId))
                .fetchCount() == 0, "请先删除子节点~");

        jqf.delete(qPermissions)
                .where(qPermissions.id.eq(permissionsId))
                .execute();
    }

    @Transactional
    public Permissions save(Permissions permissions) {
        if (permissions.getParentId() != null && permissions.getParentId() != 0L) {
            Permissions parent = bqf.selectFrom(qPermissions)
                    .where(qPermissions.id.eq(permissions.getParentId()))
                    .fetchFirst();
            if (StrUtil.isNotEmpty(parent.getKey())) {
                permissions.setKey(parent.getKey() + "-" + permissions.getIdentify());
            } else {
                permissions.setKey(permissions.getIdentify());
            }
            permissions.setFullName(parent.getFullName() + "-" + permissions.getName());
        } else {
            permissions.setKey(permissions.getIdentify());
            permissions.setFullName(permissions.getName());
        }

        if (permissions.getId() != null) {
            Optional<Permissions> reference = permissionsRepository.findById(permissions.getId());
            Assert.isFalse(reference.isEmpty(), "权限不存在~");
            reference.ifPresent(r -> {
                if (StrUtil.isNotEmpty(permissions.getKey())) {
                    Assert.isTrue(bqf.selectFrom(qPermissions)
                            .where(qPermissions.key.eq(permissions.getKey())
                                    .and(qPermissions.id.ne(permissions.getId()))
                            ).fetchCount() == 0, "权限标识 {} 重复", permissions.getKey());
                }
                BeanUtil.copyProperties(permissions, r, CopyOptions.create().ignoreNullValue());
                permissionsRepository.saveAndFlush(r);
            });
        } else {
            if (StrUtil.isNotEmpty(permissions.getKey())) {
                Assert.isTrue(bqf.selectFrom(qPermissions)
                        .where(qPermissions.key.eq(permissions.getKey()))
                        .fetchCount() == 0, "权限标识 {} 重复", permissions.getKey());
            }
            permissionsRepository.saveAndFlush(permissions);
        }
        return permissions;
    }
}
