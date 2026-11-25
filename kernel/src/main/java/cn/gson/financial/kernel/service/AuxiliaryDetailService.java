package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.config.AccountingCategoryDetailsCheck;
import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.entity.QAuxiliaryDetail;
import cn.gson.financial.kernel.model.entity.QBalanceInitial;
import cn.gson.financial.kernel.model.entity.QVoucherDetails;
import cn.hutool.core.lang.Dict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 
 * <p><b>Copyright © 2010-2024 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.service</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年03月20日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuxiliaryDetailService extends AbsService implements AccountingCategoryDetailsCheck {
    private final QVoucherDetails qVoucherDetails = QVoucherDetails.voucherDetails;
    private final QBalanceInitial qBalanceInitial = QBalanceInitial.balanceInitial;
    private final QAuxiliaryDetail qAuxiliaryDetail = QAuxiliaryDetail.auxiliaryDetail;


    /**
     * 辅助核算项是否已使用
     *
     * @param acDetails
     * @return
     */
    @Override
    public boolean isAccountingCategoryDetailsUsed(AcDetails acDetails) {
        Dict params = Dict.create()
                .set("accountSetsId", acDetails.getAccountSetsId())
                .set("categoryType", acDetails.getCategoryType().name())
                .set("categoryId", acDetails.getCategoryId())
                .set("detailId", acDetails.getId());
        List<Dict> acIds = lazyDao.findBySql("ab-auxiliary-by-acId", params, Dict.class);
        // 判断有没有被使用到，没有被使用到，就删除辅助标识
        List<Long> idList = acIds.stream().map(dict -> dict.getLong("id")).toList();

        long biCount = bqf.selectFrom(qBalanceInitial)
                .where(qBalanceInitial.auxiliaryDetailId.in(idList)
                        .and(qBalanceInitial.accountSetsId.eq(acDetails.getAccountSetsId())))
                .fetchCount();
        long bCount = bqf.selectFrom(qVoucherDetails)
                .where(qVoucherDetails.auxiliaryDetailId.in(idList)
                        .and(qVoucherDetails.accountSetsId.eq(acDetails.getAccountSetsId())))
                .fetchCount();

        if (biCount == 0 && bCount == 0) {
            // 都没有用到就删除辅助核算项标识
            jqf.delete(qAuxiliaryDetail)
                    .where(qAuxiliaryDetail.accountSetsId.eq(acDetails.getAccountSetsId())
                            .and(qAuxiliaryDetail.id.in(idList)))
                    .execute();
            return false;

        }

        return true;
    }
}
