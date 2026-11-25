package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.service.VoucherCompletenessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * FN-NEW-COMPLETENESS-REPORT — 凭证完整度报告。
 *
 * <p>GET /openapi/v1/voucher-completeness/{accountSetId}/{period}
 *
 * <p>返回各数据源 vs 已生成凭证的对账结果，以及月末结转完成状态。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/voucher-completeness")
@SaIgnore
public class OpenApiVoucherCompletenessController {

    private final VoucherCompletenessService service;

    @GetMapping("/{accountSetId}/{period}")
    public JsonResult report(@PathVariable Long accountSetId,
                             @PathVariable String period) {
        try {
            VoucherCompletenessService.Report r = service.report(accountSetId, period);
            return JsonResult.successful(r);
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-NEW-COMPLETENESS-REPORT] failed acc={} period={}", accountSetId, period, e);
            return JsonResult.failure("查询失败：" + e.getMessage());
        }
    }
}
