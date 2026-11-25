package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.Assets;
import cn.gson.financial.kernel.model.entity.AssetsDepreciation;
import cn.gson.financial.kernel.model.entity.AssetsDetail;
import cn.gson.financial.kernel.model.entity.QAssets;
import cn.gson.financial.kernel.model.entity.QAssetsDepreciation;
import cn.gson.financial.kernel.model.entity.QAssetsDetail;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;
import java.util.*;

/**
 * 开放 API — 资产折旧
 * 路径前缀 /openapi/v1/assets/depreciation
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/assets/depreciation")
@SaIgnore
public class OpenApiAssetsController {

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QAssetsDepreciation qDep = QAssetsDepreciation.assetsDepreciation;
    private static final QAssetsDetail qDetail = QAssetsDetail.assetsDetail;
    private static final QAssets qAssets = QAssets.assets;

    /**
     * §2.7.1 当月折旧记录
     * GET /openapi/v1/assets/depreciation?accountSetsId=88&period=2026-05
     */
    @GetMapping
    public JsonResult monthly(@RequestParam Long accountSetsId,
                              @RequestParam String period) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        YearMonth ym;
        try {
            ym = YearMonth.parse(period);
        } catch (Exception e) {
            return JsonResult.failure("period 格式错误，应为 YYYY-MM：" + period);
        }

        AssetsDepreciation row = bqf.selectFrom(qDep)
                .where(qDep.accountSetsId.eq(accountSetsId)
                        .and(qDep.checkYear.eq(ym.getYear()))
                        .and(qDep.checkMonth.eq(ym.getMonthValue())))
                .fetchFirst();

        if (row == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("status", 1); // 1=无数据
            empty.put("checkDate", ym.atDay(1));
            empty.put("year", ym.getYear());
            empty.put("month", ym.getMonthValue());
            return JsonResult.successful(empty);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("accountSetsId", row.getAccountSetsId());
        m.put("checkDate", row.getCheckDate());
        m.put("year", row.getCheckYear());
        m.put("month", row.getCheckMonth());
        m.put("status", row.getStatus()); // 1=无数据 2=已计算 3=已生成凭证 4=无需折旧
        m.put("voucherId", row.getVoucherId());
        m.put("cancel", Boolean.TRUE.equals(row.getCancel()));
        return JsonResult.successful(m);
    }

    /**
     * §2.7.2 折旧明细
     * GET /openapi/v1/assets/depreciation/{depreciationId}/detail?accountSetsId=88
     */
    @GetMapping("/{depreciationId}/detail")
    public JsonResult detail(@PathVariable Long depreciationId,
                             @RequestParam Long accountSetsId) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }

        List<AssetsDetail> details = bqf.selectFrom(qDetail)
                .where(qDetail.accountSetsId.eq(accountSetsId)
                        .and(qDetail.depreciationId.eq(depreciationId)))
                .fetch();

        // 批量加载资产信息
        Set<Long> assetIds = new HashSet<>();
        for (AssetsDetail d : details) {
            if (d.getAssertId() != null) assetIds.add(d.getAssertId());
        }
        Map<Long, Assets> assetMap = new HashMap<>();
        if (!assetIds.isEmpty()) {
            List<Assets> as = bqf.selectFrom(qAssets)
                    .where(qAssets.accountSetsId.eq(accountSetsId).and(qAssets.id.in(assetIds)))
                    .fetch();
            for (Assets a : as) assetMap.put(a.getId(), a);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (AssetsDetail d : details) {
            Assets a = assetMap.get(d.getAssertId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("depreciationId", d.getDepreciationId());
            m.put("checkDate", d.getCheckDate());
            m.put("digest", d.getDigest());
            m.put("assetId", d.getAssertId());
            m.put("assetCode", a != null ? a.getCode() : null);
            m.put("assetName", a != null ? a.getName() : null);
            m.put("deptId", d.getDeptId());
            m.put("deptName", d.getDeptName());
            m.put("currentDepreciation", d.getDepreciationDebit());
            m.put("originalValue", d.getOriginalDebit());
            m.put("netValue", d.getNetValue());
            m.put("voucherId", d.getVoucherId());
            // 资产对应的费用科目和累计折旧科目（来自 Assets 实体配置）
            m.put("expenseSubjectCode", a != null ? a.getDepreciationExpenseSubjectCode() : null);
            m.put("accumulatedSubjectCode", a != null ? a.getTotalDepreciationSubjectCode() : null);
            m.put("assetsSubjectCode", a != null ? a.getAssetsSubjectCode() : null);
            result.add(m);
        }
        return JsonResult.successful(result);
    }
}
