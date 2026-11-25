package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.aspect.CacheRemove;
import cn.gson.financial.kernel.config.AccountSetsProcessor;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.model.dto.BiReportDto;
import cn.gson.financial.kernel.model.entity.BiReport;
import cn.gson.financial.kernel.model.entity.QBiReport;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.mapper.BiReportMapper;
import cn.gson.financial.kernel.model.repo.BiReportRepository;
import cn.gson.financial.kernel.model.vo.AccountBookQueryVO;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BiReportService extends AbsService implements AccountSetsProcessor, BackupProcessor {
    private final BiReportMapper biReportMapper;

    private final QBiReport qBiReport = QBiReport.biReport;

    private final BiReportRepository biReportRepository;

    @Transactional
    @CacheRemove(value = "bi-report", key = "#entity.accountSetsId")
    public BiReport save(BiReport entity) {
        if (entity.getPrimaryKey() == null) {
            Assert.isTrue(biReportRepository.count(qBiReport.accountSetsId.eq(entity.getAccountSetsId())
                    .and(qBiReport.name.eq(entity.getName()))) == 0, "名称已经存在！");
            return biReportRepository.save(entity);
        } else {
            Assert.isTrue(biReportRepository.count(qBiReport.accountSetsId.eq(entity.getAccountSetsId())
                    .and(qBiReport.name.eq(entity.getName())).and(qBiReport.id.ne(entity.getId()))) == 0, "名称已经存在！");
            BiReport reference = biReportRepository.getReferenceById(entity.getPrimaryKey());

            BeanUtil.copyProperties(entity, reference, CopyOptions.create().ignoreNullValue());
            return biReportRepository.save(reference);
        }
    }

    /**
     * 币别列表
     *
     * @param accountSetsId
     * @return
     */
    @Cacheable(value = "bi-report", key = "#accountSetsId")
    public List<BiReportDto> list(Long accountSetsId) {
        return bqf.selectFrom(qBiReport)
                .select(qBiReport.id, qBiReport.name, qBiReport.pos, qBiReport.tag, qBiReport.createTime, qBiReport.updateTime)
                .where(qBiReport.accountSetsId.eq(accountSetsId))
                .orderBy(qBiReport.pos.desc(), qBiReport.id.asc())
                .fetch().stream().map(tuple -> {
                    BiReportDto dto = new BiReportDto();
                    dto.setId(tuple.get(qBiReport.id));
                    dto.setName(tuple.get(qBiReport.name));
                    dto.setPos(tuple.get(qBiReport.pos));
                    dto.setTag(tuple.get(qBiReport.tag));
                    dto.setCreateTime(tuple.get(qBiReport.createTime));
                    dto.setUpdateTime(tuple.get(qBiReport.updateTime));
                    return dto;
                }).collect(Collectors.toList());
    }

    @Override
    @CacheRemove(value = "bi-report", key = "#accountSetsId")
    public void clear(Long accountSetsId) {
        jqf.delete(qBiReport)
                .where(qBiReport.accountSetsId.eq(accountSetsId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qBiReport)
                .where(qBiReport.accountSetsId.eq(accountSetsId))
                .fetch());
    }

    @Override
    public Class<?> backupType() {
        return BiReport.class;
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
            List<BiReport> biReports = JSON.parseArray(json).toJavaList(BiReport.class);
            List<Map<String, Object>> datas = new ArrayList<>();
            if (CollUtil.isNotEmpty(biReports)) {
                biReports.forEach(biReport -> {
                    biReport.setAccountSetsId(accountSetsId);
                    Map<String, Object> map = BeanUtil.beanToMap(biReport);
                    map.put("config", JSON.toJSONString(biReport.getConfig()));
                    map.put("vars", JSON.toJSONString(biReport.getVars()));
                    map.put("cells", JSON.toJSONString(biReport.getCells()));
                    datas.add(map);
                });
                lazyDao.batchUpdate("insertBi", datas);
            }
        }
    }

    @Transactional
    @CacheRemove(value = "bi-report", key = "#primaryKey.accountSetsId")
    public void delete(PrimaryKey primaryKey) {
        biReportRepository.deleteById(primaryKey);
    }

    @Cacheable(value = "bi-report", key = "#primaryKey.accountSetsId+':'+#primaryKey.id")
    public BiReport load(PrimaryKey primaryKey) {
        return bqf.selectFrom(qBiReport)
                .where(qBiReport.accountSetsId.eq(primaryKey.getAccountSetsId())
                        .and(qBiReport.id.eq(primaryKey.getId())))
                .fetchFirst();
    }

    /**
     * 获取辅助ID
     *
     * @param queryVO
     * @return
     */
    public Set<Long> auxiliaryDetailIdList(AccountBookQueryVO queryVO) {
        List<JSONObject> results = lazyDao.findBySql("ab-auxiliaryIdList", queryVO.toMap(), JSONObject.class);
        return results.stream().map(d -> d.getLong("id")).collect(Collectors.toSet());
    }
}
