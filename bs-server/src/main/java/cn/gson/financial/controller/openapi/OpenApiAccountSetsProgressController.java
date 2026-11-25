package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.service.AccountSetsProgressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 开放 API — 账套 6 步进度批量聚合（FN-NEW-PROGRESS）。
 *
 * <p>给 BT 工作台一次性渲染 6000 户的 6 步状态用 —— 替代"一户调 5 次"模式。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/account-sets")
@SaIgnore
public class OpenApiAccountSetsProgressController {

    private final AccountSetsProgressService service;

    @GetMapping("/progress")
    public JsonResult progress(@RequestParam("period") String period,
                               @RequestParam(value = "keyword", required = false) String keyword,
                               @RequestParam(value = "pending_only", required = false, defaultValue = "false")
                                   boolean pendingOnly,
                               @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
                               @RequestParam(value = "size", required = false, defaultValue = "50") Integer size,
                               // FN-PROGRESS-SCOPE-BY-OWNER：按登录账号归属过滤（BT 传 owner_user_id；或 owner_phone 解析）
                               @RequestParam(value = "owner_user_id", required = false) Long ownerUserId,
                               @RequestParam(value = "owner_phone", required = false) String ownerPhone) {
        try {
            // owner_user_id 优先；否则按 owner_phone 解析；都拿不到 → 保守返空+提示（绝不默认全量）
            Long resolvedOwner = ownerUserId != null ? ownerUserId : service.resolveUserIdByPhone(ownerPhone);
            if (resolvedOwner == null) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("total", 0);
                data.put("page", page);
                data.put("size", size);
                data.put("records", new java.util.ArrayList<>());
                data.put("msg", "未识别登录账号：请传 owner_user_id 或可解析的 owner_phone（保守不返全量）");
                return JsonResult.successful(data);
            }
            AccountSetsProgressService.Result r = service.query(period, keyword, pendingOnly, page, size, resolvedOwner);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("total", r.total);
            data.put("page", r.page);
            data.put("size", r.size);
            data.put("records", r.records);
            return JsonResult.successful(data);
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-NEW-PROGRESS] progress failed period={} keyword={}", period, keyword, e);
            return JsonResult.failure("查询进度失败：" + e.getMessage());
        }
    }
}
