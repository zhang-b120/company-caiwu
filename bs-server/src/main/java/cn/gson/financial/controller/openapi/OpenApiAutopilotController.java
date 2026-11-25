package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.service.AutopilotService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FN-AUTOPILOT-SWITCH：无人值守「自动做账+报税」开关端点（桌面工作台用）。前缀 {@code /openapi/v1/autopilot}，@SaIgnore。
 * <ul>
 *   <li>{@code GET /autopilot?accountSetsId=} —— 查某户开关（account/global/effective）。</li>
 *   <li>{@code GET /autopilot/list} —— 全局开关 + 白名单（生效自动账套 id）。</li>
 *   <li>{@code POST /autopilot} {accountSetsId, enabled, operator?} —— 设账套开关。</li>
 *   <li>{@code POST /autopilot/global} {enabled, operator?} —— 设全局总开关。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/autopilot")
@SaIgnore
public class OpenApiAutopilotController {

    private final AutopilotService autopilotService;

    @GetMapping
    public JsonResult get(@RequestParam Long accountSetsId) {
        if (accountSetsId == null) return JsonResult.failure("accountSetsId 不能为空");
        Map<String, Object> data = new LinkedHashMap<>();
        boolean global = autopilotService.globalEnabled();
        boolean account = autopilotService.accountEnabled(accountSetsId);
        data.put("accountSetsId", accountSetsId);
        data.put("enabled", account);        // 账套级
        data.put("globalEnabled", global);   // 全局总开关
        data.put("effective", global && account);  // 是否真正进 full-flow 队列
        return JsonResult.successful(data);
    }

    @GetMapping("/list")
    public JsonResult list() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("globalEnabled", autopilotService.globalEnabled());
        data.put("enabledAccountIds", autopilotService.autopilotAccountIds());
        return JsonResult.successful(data);
    }

    @PostMapping
    public JsonResult setAccount(@RequestBody SwitchReq req) {
        if (req == null || req.getAccountSetsId() == null) return JsonResult.failure("accountSetsId 不能为空");
        if (req.getEnabled() == null) return JsonResult.failure("enabled 不能为空");
        try {
            autopilotService.setAccount(req.getAccountSetsId(), req.getEnabled(), req.getOperator());
            return get(req.getAccountSetsId());
        } catch (Exception e) {
            log.error("[FN-AUTOPILOT] 设账套开关失败 accountSetsId={}", req.getAccountSetsId(), e);
            return JsonResult.failure("设置失败：" + e.getMessage());
        }
    }

    @PostMapping("/global")
    public JsonResult setGlobal(@RequestBody SwitchReq req) {
        if (req == null || req.getEnabled() == null) return JsonResult.failure("enabled 不能为空");
        try {
            autopilotService.setGlobal(req.getEnabled(), req.getOperator());
            return list();
        } catch (Exception e) {
            log.error("[FN-AUTOPILOT] 设全局开关失败", e);
            return JsonResult.failure("设置失败：" + e.getMessage());
        }
    }

    @Data
    public static class SwitchReq {
        private Long accountSetsId;   // global 端点忽略
        private Boolean enabled;
        private String operator;
    }
}
