package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.SubjectUseCheckProcessor;
import cn.gson.financial.kernel.config.VoucherProcessor;
import cn.gson.financial.kernel.model.entity.QVoucherDetails;
import cn.gson.financial.kernel.model.entity.VoucherDetails;
import cn.gson.financial.kernel.model.repo.VoucherDetailsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

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
@Service
@RequiredArgsConstructor
public class VoucherDetailsService extends AbsService implements SubjectUseCheckProcessor, VoucherProcessor {

    private final VoucherDetailsRepository voucherDetailsRepository;

    private final QVoucherDetails qVoucherDetails = QVoucherDetails.voucherDetails;

    public List<VoucherDetails> list(Long voucherId, Long accountSetId) {
        return bqf.selectFrom(qVoucherDetails)
                .where(qVoucherDetails.voucherId.eq(voucherId).and(qVoucherDetails.accountSetsId.eq(accountSetId)))
                .orderBy(qVoucherDetails.id.desc())
                .fetch();
    }

    /**
     * 查询账套已被凭证使用过的科目编码
     *
     * @param subjectIdList
     * @param accountSetsId
     * @return
     */
    public List<Long> codeDataCheck(Set<Long> subjectIdList, Long accountSetsId) {
        return bqf.selectFrom(qVoucherDetails)
                .select(qVoucherDetails.subjectId)
                .distinct()
                .where(qVoucherDetails.voucherId.isNotNull()
                        .and(qVoucherDetails.accountSetsId.eq(accountSetsId))
                        .and(qVoucherDetails.subjectId.in(subjectIdList)))
                .fetch();
    }

    /**
     * 是否使用了相应科目
     *
     * @param accountSetsId
     * @param subjectId
     * @return
     */
    @Override
    public boolean isSubjectUsed(Long accountSetsId, Long subjectId) {
        return voucherDetailsRepository.count(qVoucherDetails.subjectId.eq(subjectId)
                .and(qVoucherDetails.accountSetsId.eq(accountSetsId))) > 0;
    }

    @Override
    public void voucherDeleted(Long voucherId, Long accountSetId) {
        jqf.delete(qVoucherDetails)
                .where(qVoucherDetails.voucherId.eq(voucherId).and(qVoucherDetails.accountSetsId.eq(accountSetId)))
                .execute();
    }
}











