package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.config.VoucherProcessor;
import cn.gson.financial.kernel.model.dto.VoucherChangeLogDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.QUser;
import cn.gson.financial.kernel.model.entity.QVoucherChangeLog;
import cn.gson.financial.kernel.model.entity.VoucherChangeLog;
import cn.gson.financial.kernel.model.mapper.VoucherChangeLogMapper;
import cn.gson.financial.kernel.model.repo.VoucherChangeLogRepository;
import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * <p>***************************************</p>
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年10月20日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * <p>***************************************</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VoucherChangeLogService extends AbsService implements VoucherProcessor, BackupProcessor {
    private final VoucherChangeLogMapper voucherChangeLogMapper;

    private final QVoucherChangeLog qVoucherChangeLog = QVoucherChangeLog.voucherChangeLog;

    private final VoucherChangeLogRepository voucherChangeLogRepository;

    private final QUser qUser = QUser.user;

    @Async
    @Transactional
    public void saveLog(Long voucherId, Long accountSetId, VoucherDto voucherDto) {
        VoucherChangeLog changeLog = new VoucherChangeLog();
        changeLog.setVoucherId(voucherId);
        changeLog.setAccountSetsId(accountSetId);
        changeLog.setUpdateMember(voucherDto.getLastUpdateMember());
        changeLog.setUpdateDate(voucherDto.getLastUpdateDate());
        changeLog.setVoucher(voucherDto);
        voucherChangeLogRepository.save(changeLog);
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qVoucherChangeLog)
                .where(qVoucherChangeLog.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<?> backupType() {
        return VoucherChangeLog.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<VoucherChangeLog> entityList = JSON.parseArray(json, VoucherChangeLog.class);
        for (VoucherChangeLog entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            voucherChangeLogRepository.save(entity);
        }
    }

    @Override
    public void voucherDeleted(Long voucherId, Long accountSetId) {
        jqf.delete(qVoucherChangeLog)
                .where(qVoucherChangeLog.accountSetsId.eq(accountSetId)
                        .and(qVoucherChangeLog.voucherId.eq(voucherId)))
                .execute();
    }

    /**
     * 变更记录
     * @param accountSetsId
     * @param voucherId
     * @return
     */
    public List<VoucherChangeLogDto> changeLog(Long accountSetsId, Long voucherId) {
        return bqf.selectFrom(qVoucherChangeLog)
                .select(qVoucherChangeLog, qUser.realName)
                .join(qUser).on(qUser.id.eq(qVoucherChangeLog.updateMember))
                .where(qVoucherChangeLog.accountSetsId.eq(accountSetsId)
                        .and(qVoucherChangeLog.voucherId.eq(voucherId)))
                .orderBy(qVoucherChangeLog.updateDate.desc(), qVoucherChangeLog.id.desc())
                .fetch().stream().map(tuple -> {
                    VoucherChangeLogDto dto = voucherChangeLogMapper.toDto(tuple.get(qVoucherChangeLog));
                    dto.setUpdateMemberName(tuple.get(qUser.realName));
                    return dto;
                }).toList();
    }
}
