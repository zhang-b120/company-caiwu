package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 开放 API — 结转模板批量初始化（FN-CARRYOVER-V5-BATCH-INIT）。
 *
 * <p>用户 2026-05-28 需求 ③：批量初始化结转模板；④：已有数据则跳过。
 *
 * <p>端点：POST /openapi/v1/carryover-templates/batch-init
 * 入参：{ accountSetIds: string[]（≤100）, force?: boolean }
 *
 * <p>逻辑：
 * <ul>
 *   <li>force=false（默认）：先 SELECT 检查每个账套是否已有 carryover_rule + entries 非空 → 跳过</li>
 *   <li>force=true：跳过「已有数据」判断，强制重新 init（覆盖含 accountant_custom 的 entries）</li>
 *   <li>每个账套独立事务，单账套失败不让整批回滚</li>
 *   <li>返响应：{ total, skipped[], succeeded[], failed[], elapsedMs }</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/carryover-templates")
@SaIgnore
public class OpenApiCarryoverTemplatesBatchController {

    static final int MAX_ACCOUNT_SET_IDS = 100;

    private final OpenApiCarryoverTemplatesController carryoverTemplatesController;

    @PostMapping("/batch-init")
    public JsonResult batchInit(@RequestBody BatchInitReq req) {
        if (req == null || req.getAccountSetIds() == null || req.getAccountSetIds().isEmpty()) {
            return JsonResult.failure("accountSetIds 不能为空");
        }
        if (req.getAccountSetIds().size() > MAX_ACCOUNT_SET_IDS) {
            return JsonResult.failure("accountSetIds 长度超限：" + req.getAccountSetIds().size()
                    + " > " + MAX_ACCOUNT_SET_IDS);
        }
        boolean force = Boolean.TRUE.equals(req.getForce());
        long startMs = System.currentTimeMillis();

        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Map<String, Object>> succeeded = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();

        for (String accIdStr : req.getAccountSetIds()) {
            Long accId;
            try {
                accId = Long.parseLong(accIdStr.trim());
            } catch (NumberFormatException e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("accountSetId", accIdStr);
                f.put("errorCode", "ACCOUNT_SET_ID_INVALID");
                f.put("errorMsg", "accountSetId 不是合法 long：" + accIdStr);
                failed.add(f);
                continue;
            }
            try {
                processOne(accId, force, skipped, succeeded, failed);
            } catch (Exception e) {
                log.warn("[FN-CARRYOVER-V5-BATCH-INIT] 账套 {} init 异常：{}", accId, e.toString());
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("accountSetId", String.valueOf(accId));
                f.put("errorCode", "INIT_EXCEPTION");
                f.put("errorMsg", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                failed.add(f);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", req.getAccountSetIds().size());
        data.put("skipped", skipped);
        data.put("succeeded", succeeded);
        data.put("failed", failed);
        data.put("elapsedMs", System.currentTimeMillis() - startMs);
        data.put("force", force);
        return JsonResult.successful(data);
    }

    /** 单账套处理（独立事务以保证 skipped 计数 / 整批不回滚） */
    @Transactional
    protected void processOne(Long accId, boolean force,
                              List<Map<String, Object>> skipped,
                              List<Map<String, Object>> succeeded,
                              List<Map<String, Object>> failed) {
        if (!carryoverTemplatesController.accountSetExists(accId)) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("accountSetId", String.valueOf(accId));
            f.put("errorCode", "ACCOUNT_SET_NOT_FOUND");
            f.put("errorMsg", "账套不存在：" + accId);
            failed.add(f);
            return;
        }
        // force=false 时检查是否已有数据
        if (!force && carryoverTemplatesController.hasExistingCarryoverData(accId)) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("accountSetId", String.valueOf(accId));
            s.put("reason", "already_has_data");
            skipped.add(s);
            return;
        }
        JsonResult r = carryoverTemplatesController.initInternal(accId, force);
        if (r.isSuccess()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> initData = (Map<String, Object>) r.getData();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("accountSetId", String.valueOf(accId));
            s.put("taxpayerType", initData.get("taxpayerType"));
            s.put("templateVersion", initData.get("templateVersion"));
            @SuppressWarnings("unchecked")
            Map<String, Object> tpl = (Map<String, Object>) initData.get("templates");
            s.put("templatesCreated", tpl.get("created"));
            s.put("templatesUpdated", tpl.get("updated"));
            s.put("overriddenCustomCount", initData.get("overriddenCustomCount"));
            succeeded.add(s);
        } else {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("accountSetId", String.valueOf(accId));
            f.put("errorCode", "INIT_FAILED");
            f.put("errorMsg", r.getMsg());
            failed.add(f);
        }
    }

    @Data
    public static class BatchInitReq {
        private List<String> accountSetIds;
        private Boolean force;
    }
}
