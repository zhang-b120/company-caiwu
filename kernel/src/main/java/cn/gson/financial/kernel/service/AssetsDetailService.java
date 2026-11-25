package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.annotation.CopyField;
import cn.gson.financial.kernel.config.BackupProcessor;
import cn.gson.financial.kernel.config.VoucherProcessor;
import cn.gson.financial.kernel.model.dto.AssetsDetailDto;
import cn.gson.financial.kernel.model.entity.AssetsDetail;
import cn.gson.financial.kernel.model.entity.QAssetsDetail;
import cn.gson.financial.kernel.model.entity.QDepartment;
import cn.gson.financial.kernel.model.entity.QVoucher;
import cn.gson.financial.kernel.model.repo.AssetsDetailRepository;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.alibaba.fastjson.JSON;
import com.querydsl.core.Tuple;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : (AssetsDetail)</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022-11-25 23:50:30</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AssetsDetailService extends AbsService implements VoucherProcessor, BackupProcessor {

    private final QAssetsDetail qAssetsDetail = QAssetsDetail.assetsDetail;

    private final QVoucher qVoucher = QVoucher.voucher;

    private final QDepartment qDepartment = QDepartment.department;

    private final AssetsDetailRepository assetsDetailRepository;

    public List<AssetsDetailDto> list(Long accountSetId, Long assetsId) {
        List<Tuple> fetch = bqf.selectFrom(qAssetsDetail)
                .select(qAssetsDetail, qVoucher.word, qVoucher.code, qDepartment.name)
                .leftJoin(qVoucher).on(qVoucher.id.eq(qAssetsDetail.voucherId).and(qVoucher.accountSetsId.eq(accountSetId)))
                .leftJoin(qDepartment).on(qDepartment.id.eq(qAssetsDetail.deptId).and(qDepartment.accountSetsId.eq(accountSetId)))
                .where(qAssetsDetail.accountSetsId.eq(accountSetId).and(qAssetsDetail.assertId.eq(assetsId)))
                .orderBy(qAssetsDetail.checkDate.asc(), qAssetsDetail.id.asc())
                .fetch();

        List<AssetsDetailDto> dtos = new ArrayList<>();
        AssetsDetailDto month = new AssetsDetailDto();
        AssetsDetailDto year = new AssetsDetailDto();
        CopyOptions copyOptions = CopyOptions.create().setPropertiesFilter((field, val) -> field.isAnnotationPresent(CopyField.class));

        for (int i = 0; i < fetch.size(); i++) {
            AssetsDetailDto dto = BeanUtil.toBean(fetch.get(i).get(qAssetsDetail), AssetsDetailDto.class);
            if (dto.getVoucherId() != null) {
                dto.setVoucherTitle(fetch.get(i).get(qVoucher.word) + "-" + fetch.get(i).get(qVoucher.code));
            }
            dto.setDeptName(fetch.get(i).get(qDepartment.name));
            month.setDigest("本期合计");
            year.setDigest("本年合计");
            BeanUtil.copyProperties(dto, month, copyOptions);
            BeanUtil.copyProperties(dto, year, copyOptions);
            dtos.add(dto);
            if (i + 1 == fetch.size()) {
                dtos.add(month);
                dtos.add(year);
            } else {
                AssetsDetailDto next = BeanUtil.toBean(fetch.get(i + 1).get(qAssetsDetail), AssetsDetailDto.class);
                if (next.getCheckDate().getYear() != dto.getCheckDate().getYear()) {
                    dtos.add(month);
                    dtos.add(year);
                    month = new AssetsDetailDto();
                    year = new AssetsDetailDto();
                } else if (next.getCheckDate().getMonthValue() != dto.getCheckDate().getMonthValue()) {
                    dtos.add(month);
                    month = new AssetsDetailDto();
                }
            }
        }
        return dtos;
    }

    @Override
    public void voucherDeleted(Long voucherId, Long accountSetId) {
        jqf.update(qAssetsDetail)
                .setNull(qAssetsDetail.voucherId)
                .where(qAssetsDetail.voucherId.eq(voucherId))
                .execute();
    }

    @Override
    public String backup(Long accountSetsId) {
        return JSON.toJSONString(bqf.selectFrom(qAssetsDetail)
                .where(qAssetsDetail.accountSetsId.eq(accountSetsId)).fetch());
    }

    @Override
    public Class<?> backupType() {
        return AssetsDetail.class;
    }

    @Override
    public void recover(Long accountSetsId, String json) {
        List<AssetsDetail> entityList = JSON.parseArray(json, AssetsDetail.class);
        for (AssetsDetail entity : entityList) {
            entity.setAccountSetsId(accountSetsId);
            assetsDetailRepository.save(entity);
        }
    }
}

