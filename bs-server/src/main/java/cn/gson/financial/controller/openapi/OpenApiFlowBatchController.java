package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.FlowBatch;
import cn.gson.financial.kernel.model.entity.FlowBatchItem;
import cn.gson.financial.service.FlowBatchService;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * FN-FLOW-BATCH-STATE：桌面批量三态看板。前缀 {@code /openapi/v1/flow}，@SaIgnore + 内部 token。
 * <ul>
 *   <li>{@code POST /flow/batch} {period, createdBy?, source?, accountSetsIds[]} → 建批次 + 批插 queued，返 batchId。</li>
 *   <li>{@code PATCH /flow/batch/item} {batchId, accountSetsId, period, status?, currentStage?, note?} → 改单户三态。</li>
 *   <li>{@code GET /flow/batch/latest?period=} / {@code GET /flow/batch/{id}} → 每户三态 + current_stage（看板）。</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/flow")
@SaIgnore
public class OpenApiFlowBatchController {

    private final FlowBatchService flowBatchService;

    /** flow 内部 token（与 declare-screenshot 共用）；未配置则 dev 放行。 */
    @Value("${flow.upload.token:}")
    private String flowToken;

    @PostMapping("/batch")
    public JsonResult createBatch(@RequestHeader(value = "Authorization", required = false) String authorization,
                                  @RequestBody BatchCreateReq req) {
        if (!checkToken(authorization)) return JsonResult.failure("Token 验证失败", 401);
        if (req == null || StrUtil.isBlank(req.getPeriod())) return JsonResult.failure("period 不能为空（格式 YYYY-MM）");
        if (req.getAccountSetsIds() == null || req.getAccountSetsIds().isEmpty()) {
            return JsonResult.failure("accountSetsIds 不能为空");
        }
        try {
            FlowBatch b = flowBatchService.createBatch(
                    req.getPeriod().trim(), req.getCreatedBy(), req.getSource(), req.getAccountSetsIds());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("batchId", b.getId());
            data.put("total", b.getTotal());
            data.put("period", b.getPeriod());
            return JsonResult.successful(data);
        } catch (Exception e) {
            log.error("[FN-FLOW-BATCH] 建批次失败 period={}", req.getPeriod(), e);
            return JsonResult.failure("建批次失败：" + e.getMessage());
        }
    }

    /** BATCH-ITEM-CONTRACT：POST 别名（BT flowCall 仅支持 GET/POST），body 同 ItemPatchReq，与 PATCH 同逻辑。 */
    @PostMapping("/batch/item")
    public JsonResult patchItemPost(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestBody ItemPatchReq req) {
        return patchItem(authorization, req);
    }

    @PatchMapping("/batch/item")
    public JsonResult patchItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                @RequestBody ItemPatchReq req) {
        if (!checkToken(authorization)) return JsonResult.failure("Token 验证失败", 401);
        if (req == null || req.getBatchId() == null || req.getAccountSetsId() == null
                || StrUtil.isBlank(req.getPeriod())) {
            return JsonResult.failure("batchId / accountSetsId / period 不能为空");
        }
        try {
            FlowBatchItem it = flowBatchService.patchItem(req.getBatchId(), req.getAccountSetsId(),
                    req.getPeriod().trim(), req.getStatus(), req.getCurrentStage(), req.getNote());
            return JsonResult.successful(toItemMap(it));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-FLOW-BATCH] 改批次项失败 batchId={}", req.getBatchId(), e);
            return JsonResult.failure("改批次项失败：" + e.getMessage());
        }
    }

    @GetMapping("/batch/latest")
    public JsonResult latest(@RequestParam String period) {
        if (StrUtil.isBlank(period)) return JsonResult.failure("period 不能为空（格式 YYYY-MM）");
        return batchView(flowBatchService.latest(period.trim()));
    }

    @GetMapping("/batch/{id}")
    public JsonResult byId(@PathVariable Long id) {
        if (id == null) return JsonResult.failure("id 不能为空");
        return batchView(flowBatchService.findBatch(id));
    }

    // ─── 视图 / 校验 ───

    private JsonResult batchView(FlowBatch batch) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (batch == null) {
            data.put("batch", null);
            data.put("exists", false);
            data.put("items", List.of());
            return JsonResult.successful(data);
        }
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", batch.getId());
        b.put("period", batch.getPeriod());
        b.put("createdBy", batch.getCreatedBy());
        b.put("total", batch.getTotal());
        b.put("source", batch.getSource());
        b.put("createdAt", batch.getCreatedAt());
        data.put("batch", b);
        data.put("exists", true);
        List<Map<String, Object>> items = new ArrayList<>();
        for (FlowBatchItem it : flowBatchService.items(batch.getId())) items.add(toItemMap(it));
        data.put("items", items);
        return JsonResult.successful(data);
    }

    private Map<String, Object> toItemMap(FlowBatchItem it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.getId());
        m.put("batchId", it.getBatchId());
        m.put("accountSetId", it.getAccountSetId());
        m.put("period", it.getPeriod());
        m.put("status", it.getStatus());
        m.put("currentStage", it.getCurrentStage());
        m.put("note", it.getNote());
        m.put("startedAt", it.getStartedAt());
        m.put("endedAt", it.getEndedAt());
        m.put("updatedAt", it.getUpdatedAt());
        return m;
    }

    private boolean checkToken(String authorization) {
        if (StrUtil.isBlank(flowToken)) return true;
        if (StrUtil.isBlank(authorization)) return false;
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        return flowToken.equals(token);
    }

    @Data
    public static class BatchCreateReq {
        private String period;
        private String createdBy;
        private String source;
        private List<Long> accountSetsIds;
    }

    @Data
    public static class ItemPatchReq {
        private Long batchId;
        private Long accountSetsId;
        private String period;
        private String status;
        private String currentStage;
        private String note;
    }
}
