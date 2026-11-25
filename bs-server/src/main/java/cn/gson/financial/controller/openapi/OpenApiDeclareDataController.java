package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.service.DeclareDataService;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.YearMonth;

/**
 * 开放 API — 申报数据（FN-RPA-02 + FN-API-GAPS ④）。
 *
 * <p>给 BT 桌面端 RPA 脚本拉数据填申报表用。
 * <ul>
 *   <li>{@code GET /openapi/v1/declare-data/{accountSetId}?type=vat&period=2026-05} — 单类申报底数（既有）</li>
 *   <li>{@code GET /openapi/v1/declare-data?accountSetId=&period=&taxType=vat} — 预申报真数三方
 *       （taxType=vat → INV/ACC/PREV，对齐 BT vat-precheck.ts；其他 taxType 走既有单类口径）</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/declare-data")
@SaIgnore
public class OpenApiDeclareDataController {

    private final DeclareDataService service;

    @GetMapping("/{accountSetId}")
    public JsonResult get(@PathVariable("accountSetId") Long accountSetsId,
                          @RequestParam("type") String type,
                          @RequestParam("period") String period) {
        try {
            return JsonResult.successful(service.getData(accountSetsId, type, period));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-RPA-02] declare-data failed acc={} type={} period={}",
                    accountSetsId, type, period, e);
            return JsonResult.failure("查询申报数据失败：" + e.getMessage());
        }
    }

    /**
     * FN-API-GAPS ④ 预申报真数：{@code GET /openapi/v1/declare-data?accountSetId=&period=&taxType=vat}。
     * <p>taxType=vat → {@code {INV, ACC, PREV}}（对齐 BT vat-precheck.ts VatPrecheckInput）；
     * 其他 taxType（iit/surcharge/annual）→ 复用既有单类口径。
     */
    @GetMapping
    public JsonResult precheck(@RequestParam("accountSetId") Long accountSetsId,
                               @RequestParam("period") String period,
                               @RequestParam(value = "taxType", required = false, defaultValue = "vat") String taxType,
                               @RequestParam(value = "taxpayerType", required = false) String taxpayerType) {
        if (accountSetsId == null) return JsonResult.failure("accountSetId 不能为空");
        if (StrUtil.isBlank(period)) return JsonResult.failure("period 不能为空（yyyy-MM）");
        String t = StrUtil.isBlank(taxType) ? "vat" : taxType.trim().toLowerCase();
        try {
            if ("vat".equals(t)) {
                YearMonth ym;
                try { ym = YearMonth.parse(period.trim()); }
                catch (Exception e) { return JsonResult.failure("period 格式非法（应为 yyyy-MM）：" + period); }
                // FN-BT-DECLDATA-VAT-ALIGN：taxpayerType=small_scale → BT 六字段口径；空则按账套 vatType 派生
                return JsonResult.successful(service.buildVatPrecheck(accountSetsId, ym, taxpayerType));
            }
            // 非 vat 走既有单类口径（保持本端点通用）
            return JsonResult.successful(service.getData(accountSetsId, t, period.trim()));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-API-GAPS] declare-data precheck failed acc={} taxType={} period={}",
                    accountSetsId, t, period, e);
            return JsonResult.failure("查询预申报真数失败：" + e.getMessage());
        }
    }
}
