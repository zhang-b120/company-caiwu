package cn.gson.financial.crm.service;

import cn.gson.financial.common.ConvertMoney;
import cn.gson.financial.kernel.common.CopierCopyUtils;
import cn.gson.financial.kernel.model.crm.req.GetMyAccountSetsReq;
import cn.gson.financial.kernel.model.crm.resp.GetMyAccountSetsResp;
import cn.gson.financial.kernel.model.crm.resp.GetTaxTabResp;
import cn.gson.financial.kernel.model.dto.TaxDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.service.AbsService;
import cn.gson.financial.kernel.service.TaxService;
import cn.hutool.core.collection.CollUtil;
import com.blazebit.persistence.querydsl.BlazeJPAQuery;
import com.querydsl.core.Tuple;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @Author zhb
 * @Date 2025-07-05
 * @Description
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CrmAccountSetsService extends AbsService {

    private final QAccountSets qAccountSets = QAccountSets.accountSets;
    private final QTaxTable qTaxTable = QTaxTable.taxTable;
    private final QCurrency qCurrency = QCurrency.currency;
    private final QCheckout qCheckout = QCheckout.checkout;
    private QTaxReportResult qTaxReportResult = QTaxReportResult.taxReportResult;
    private final QImagePayment qImagePayment = QImagePayment.imagePayment;
    @Value("${static-url.base-url}")
    private String baseUrl;

    @Value("${static-url.img}")
    private String imgs;

    public List<GetMyAccountSetsResp> newMyAccountSets(GetMyAccountSetsReq req) {
        List<GetMyAccountSetsResp> respList = new ArrayList<>();

        List<Tuple> tupleList = bqf.selectFrom(qAccountSets)
                .select(qAccountSets.id, qAccountSets.companyName, qAccountSets.accountStandard, qAccountSets.vatType, qAccountSets.createDate,
                        qAccountSets.creatorId, qAccountSets.creditCode, qAccountSets.enableDate, qAccountSets.currentAccountDate, qAccountSets.electricityTaxPhone,
                        qAccountSets.electricityTaxCode, qAccountSets.province, qTaxTable.taxId, qTaxTable.taxEndTime, qTaxTable.taxState, qTaxTable.taxType, qCurrency.id, qCheckout.checkDate, qCheckout.status,
                        qTaxReportResult.allCount, qTaxReportResult.successCount, qTaxReportResult.imgPath,qImagePayment.imageUrl
                )
                .leftJoin(qTaxTable).on(qTaxTable.accountSetsId.eq(qAccountSets.id))
                .leftJoin(qCurrency).on(qCurrency.accountSetsId.eq(qAccountSets.id))
                .leftJoin(qCheckout).on(qCheckout.accountSetsId.eq(qAccountSets.id))
                .leftJoin(qTaxReportResult).on(qCheckout.accountSetsId.eq(qTaxReportResult.accountSetId).and(qCheckout.checkDate.eq(qTaxReportResult.taxTime)))
                .leftJoin(qImagePayment).on(qTaxTable.accountSetsId.eq(qImagePayment.accountSetsId))
                .where(qAccountSets.id.goe(1L)
                        .and(qCheckout.checkDate.eq(req.getCheckDate()))
                        .and(req.getStatus() == null ? null : qCheckout.status.eq(req.getStatus()))
                        .and(StringUtils.isEmpty(req.getPhoneNum()) ? null : qAccountSets.electricityTaxPhone.eq(req.getPhoneNum()))
                        .and(StringUtils.isEmpty(req.getCompanyName()) ? null : qAccountSets.companyName.like("%" + req.getCompanyName() + "%"))
                        .and(StringUtils.isEmpty(req.getValType()) ? null : qAccountSets.vatType.eq(VatType.valueOf(req.getValType())))
                        .and(StringUtils.isEmpty(req.getProvince()) ? null : qAccountSets.province.eq(req.getProvince()))
                        .and(StringUtils.isEmpty(req.getTaxState()) ? null : qTaxTable.taxState.eq(req.getTaxState()))

                ).fetch();
        Map<Long, List<Tuple>> tuplesMap = tupleList.stream().collect(Collectors.groupingBy(item -> item.get(qAccountSets.id)));
        if (CollUtil.isNotEmpty(tupleList)) {
            tuplesMap.forEach((k, v) -> {
                GetMyAccountSetsResp resp = new GetMyAccountSetsResp();
                resp.setAccountSetsId(v.get(0).get(qAccountSets.id));
                resp.setStatus(v.get(0).get(qCheckout.status));
                resp.setCheckDate(v.get(0).get(qCheckout.checkDate));
                resp.setAccountStandard(v.get(0).get(qAccountSets.accountStandard).display);
                resp.setCompanyName(v.get(0).get(qAccountSets.companyName));
                resp.setEnableDate(v.get(0).get(qAccountSets.enableDate));
                resp.setCreditCode(v.get(0).get(qAccountSets.creditCode));
                resp.setVatType(v.get(0).get(qAccountSets.vatType).name());
                resp.setElectricityTaxCode(v.get(0).get(qAccountSets.electricityTaxCode));
                resp.setElectricityTaxPhone(v.get(0).get(qAccountSets.electricityTaxPhone));
                resp.setCreateDate(v.get(0).get(qAccountSets.createDate));
                resp.setCreatorId(v.get(0).get(qAccountSets.creatorId));
                resp.setProvince(v.get(0).get(qAccountSets.province));
                resp.setLocalCurrencyId(v.get(0).get(qCurrency.accountSetsId));
                resp.setCurrentAccountDate(v.get(0).get(qAccountSets.currentAccountDate));
                resp.setAllCount(v.get(0).get(qTaxReportResult.allCount));
                resp.setSuccessCount(v.get(0).get(qTaxReportResult.successCount));
                resp.setImgPath(baseUrl + imgs + v.get(0).get(qTaxReportResult.imgPath));
                resp.setTaxAmountImg(baseUrl + imgs + v.get(0).get(qImagePayment.imageUrl));
                if (ObjectUtils.isNotEmpty(v.get(0).get(qTaxTable.taxId))) {
                    List<GetTaxTabResp> taxTabList = v.stream().map(item -> {
                        GetTaxTabResp taxTabResp = new GetTaxTabResp();
                        taxTabResp.setTaxId(item.get(qTaxTable.taxId));
                        taxTabResp.setTaxState(item.get(qTaxTable.taxState));
                        taxTabResp.setTaxType(item.get(qTaxTable.taxType));
                        taxTabResp.setTaxEndTime(item.get(qTaxTable.taxEndTime));
                        return taxTabResp;
                    }).collect(Collectors.toList());

                    boolean existFlag = taxTabList.stream().anyMatch(task ->
                            task.getTaxType().contains("财务报表报送")
                    );
                    if (!existFlag && taxTabList.get(0).getTaxEndTime() != null) {
                        GetTaxTabResp getTaxTabResp = new GetTaxTabResp();
                        getTaxTabResp.setTaxId(System.currentTimeMillis());
                        getTaxTabResp.setTaxType(getFinancialReportPushType(taxTabList.get(0).getTaxEndTime()));
                        getTaxTabResp.setTaxEndTime(v.get(0).get(qTaxTable.taxEndTime));
                        getTaxTabResp.setTaxState("未申报（补充）");
                        taxTabList.add(getTaxTabResp);
                    }
                    if (CollUtil.isNotEmpty(taxTabList)) {
                        resp.setTaxTabRespList(taxTabList);
                    }
                }
                respList.add(resp);
            });
        }
        return respList;
    }

    public String getFinancialReportPushType(LocalDate time) {
        int month = time.getMonth().getValue();
        String taxType = "";
        switch (month) {
            case 1:
                taxType = "财务报表报送（年报）";
                break;
            case 2:
            case 3:
                taxType = "财务报表报送（月报）";
                break;
            case 4:
                taxType = "财务报表报送（季报）";
                break;
            case 5:
            case 6:
                taxType = "财务报表报送（月报）";
                break;
            case 7:
                taxType = "财务报表报送（季报）";
                break;
            case 8:
            case 9:
                taxType = "财务报表报送（月报）";
                break;
            case 10:
                taxType = "财务报表报送（季报）";
                break;
            case 11:
            case 12:
                taxType = "财务报表报送（月报）";
                break;
        }
        return taxType;

    }
@Transactional(rollbackFor = Exception.class)
    public void updateIndividualTaxCodeByCreditCode(String creditCode,String individualTaxCode) {
         jqf.update(qAccountSets).set(qAccountSets.individualTaxCode, individualTaxCode).where(qAccountSets.creditCode.eq(creditCode)).execute();
    }
}
