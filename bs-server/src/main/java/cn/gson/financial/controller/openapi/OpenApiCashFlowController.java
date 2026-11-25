package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.entity.CashFlow;
import cn.gson.financial.kernel.service.CashFlowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 开放 API — 现金流量项目
 * 路径前缀 /openapi/v1/cashflow
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/cashflow")
@SaIgnore
public class OpenApiCashFlowController {

    private final CashFlowService cashFlowService;

    /**
     * P1-3 现金流量项目列表
     * GET /openapi/v1/cashflow/items?accountSetsId=88
     */
    @GetMapping("/items")
    public JsonResult items(@RequestParam Long accountSetsId) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        List<? extends AcDetails> rows = cashFlowService.loadData(accountSetsId);
        if (rows == null) rows = Collections.emptyList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (AcDetails d : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("accountSetsId", d.getAccountSetsId());
            m.put("code", d.getCode());
            m.put("name", d.getName());
            // CashFlow 比 AcDetails 多一个 category 字段（如"经营活动现金流入"）
            if (d instanceof CashFlow cf) {
                m.put("category", cf.getCategory());
            }
            m.put("enabled", d.getEnable() == null || Boolean.TRUE.equals(d.getEnable()));
            result.add(m);
        }
        return JsonResult.successful(result);
    }
}
