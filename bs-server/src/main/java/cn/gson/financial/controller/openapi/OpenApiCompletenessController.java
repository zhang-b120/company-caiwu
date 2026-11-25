package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.service.AccountCompletenessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 开放 API — 账套数据完整性查询（FN-COMPLETENESS-01）。
 *
 * <p>给 BT 桌面端 AI 秘书 / Orchestrator 在派发"做账+申报"任务前**预检数据**，
 * 缺资料时 AI 秘书直接驳回 / 提示用户补；不要等 RPA 跑到一半才发现。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/account-sets")
@SaIgnore
public class OpenApiCompletenessController {

    private final AccountCompletenessService service;

    @GetMapping("/{id}/completeness")
    public JsonResult check(@PathVariable("id") Long accountSetsId,
                            @RequestParam("period") String period) {
        try {
            AccountCompletenessService.Result r = service.check(accountSetsId, period);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("accountSetsId", accountSetsId);
            data.put("period", period);
            data.put("ready", r.ready);
            data.put("required", r.required);
            data.put("missingCount", r.missingCount);
            return JsonResult.successful(data);
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-COMPLETENESS-01] check failed acc={} period={}", accountSetsId, period, e);
            return JsonResult.failure("查询失败：" + e.getMessage());
        }
    }
}
