package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.model.dto.VersionsDto;
import cn.gson.financial.kernel.model.entity.QAdmin;
import cn.gson.financial.kernel.model.entity.QVersions;
import cn.gson.financial.kernel.model.entity.Versions;
import cn.gson.financial.kernel.model.repo.VersionsRepository;
import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static cn.gson.financial.kernel.model.entity.QAdmin.admin;
import static cn.gson.financial.kernel.model.entity.QVersions.versions;

/**
 * <p>****************************************************************************</p >
 * <p><b>Copyright  2013-2023-09-01 开发公司 All Rights Reserved<b></p >
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023-09-01</li>
 * <li>@author     : 超豪</li>
 * </ul>
 * <p>****************************************************************************</p >
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VersionsService extends AbsService {

    private final QVersions qVersions = versions;

    private final QAdmin qAdmin = admin;

    private final VersionsRepository versionsRepository;

    @Transactional
    public void save(Versions versions) {
        versionsRepository.save(versions);
    }

    @Transactional
    public Optional<Versions> load(Long versionsId) {
        return versionsRepository.findById(versionsId);
    }

    @Transactional
    public void delete(Long versionsId) {
        versionsRepository.deleteById(versionsId);
    }


    public List<VersionsDto> list() {
        List<VersionsDto> versionsDtos = new ArrayList<>();
        bqf.selectFrom(qVersions)
                .select(qVersions, qAdmin.name)
                .leftJoin(qAdmin).on(qAdmin.id.eq(qVersions.adminId))
                .orderBy(qVersions.publishDate.desc())
                .fetch().forEach(tuple -> {
                    VersionsDto bean = BeanUtil.toBean(tuple.get(qVersions), VersionsDto.class);
                    bean.setAdminName(tuple.get(qAdmin.name));
                    versionsDtos.add(bean);
                });

        return versionsDtos;
    }


}