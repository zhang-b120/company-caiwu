package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.task.workbench.service.TaskWorkbenchService;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 开放 API — 账期阶段状态通用写端点（STAGE-STATUS-PERSIST 改造③）。
 * 路径前缀 {@code /openapi/v1/task-period}。
 *
 * <p>给"在 BT/XW 侧完成、FN 无现成钩子"的阶段（采集/做账/报税/归档…）回写 {@code task_period.step*}，
 * 使 FN {@code TaskPeriod} 成为各阶段状态的单一真相源。
 *
 * <p>{@code POST /openapi/v1/task-period/{accountSetId}/step} body {@code {period, step, status}}：
 * 经 {@code TaskWorkbenchService.writeStepStatusChecked} 走
 * <b>step 白名单 + status 白名单 + 状态机校验（done 不许跳级）</b> 后 getOrCreateTaskPeriod 落库。
 *
 * <p>越权防护：account 存在性校验 + step/status 白名单 + 状态机（不许把后置步骤直接跳级写 done）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/task-period")
@SaIgnore
public class OpenApiTaskPeriodStepController {

    /** 状态机校验失败业务码（不许跳级写 done）。 */
    static final int CODE_STATE_MACHINE = 4490;

    private final TaskWorkbenchService taskWorkbenchService;

    @PostMapping("/{accountSetId}/step")
    public JsonResult writeStep(@PathVariable Long accountSetId, @RequestBody StepReq req) {
        if (accountSetId == null) {
            return JsonResult.failure("accountSetId 不能为空");
        }
        if (req == null || StrUtil.isBlank(req.getPeriod())) {
            return JsonResult.failure("period 不能为空（yyyy-MM）");
        }
        if (StrUtil.isBlank(req.getStep())) {
            return JsonResult.failure("step 不能为空");
        }
        if (StrUtil.isBlank(req.getStatus())) {
            return JsonResult.failure("status 不能为空");
        }
        try {
            return JsonResult.successful(taskWorkbenchService.writeStepStatusChecked(
                    accountSetId, req.getPeriod().trim(), req.getStep().trim(), req.getStatus().trim()));
        } catch (IllegalStateException e) {
            // 状态机校验失败（跳级）
            return JsonResult.failure(e.getMessage(), CODE_STATE_MACHINE);
        } catch (IllegalArgumentException e) {
            // step/status 非法、账套不存在
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[STAGE-STATUS-PERSIST] 写 step 失败 acc={} req={}", accountSetId, req, e);
            return JsonResult.failure("写阶段状态失败：" + e.getMessage());
        }
    }

    @Data
    public static class StepReq {
        private String period;   // yyyy-MM
        /** 阶段名：verify/init/data/bookkeep/carry/tax_national/tax_iit/payment/tax_annual/archive */
        private String step;
        /** 状态：pending/running/done/error/skipped */
        private String status;
    }
}
