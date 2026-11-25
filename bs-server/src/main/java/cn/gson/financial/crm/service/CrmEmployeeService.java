package cn.gson.financial.crm.service;

import cn.gson.financial.kernel.model.crm.req.GetMyAccountSetsReq;
import cn.gson.financial.kernel.model.crm.resp.GetMyAccountSetsResp;
import cn.gson.financial.kernel.model.crm.resp.GetTaxTabResp;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.service.AbsService;
import cn.hutool.core.collection.CollUtil;
import com.querydsl.core.Tuple;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CrmEmployeeService extends AbsService {

    private final QEmployee qEmployee = QEmployee.employee;
    public Long getEmployeecount(Long accountSetId){
        return bqf.selectFrom(qEmployee)
                .select(qEmployee.count())
                .where(qEmployee.accountSetsId.eq(accountSetId).and(qEmployee.status.eq("在职")))
                .fetchFirst();
    }
}