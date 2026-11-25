package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.service.WorkbenchPipelineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 开放 API — 工作台聚合（FN-PIPELINE-COUNTS-BY-USER）。前缀 {@code /openapi/v1/workbench}，免鉴权（@SaIgnore）。
 *
 * <p>{@code GET /openapi/v1/workbench/pipeline-counts?userId=&period=} —— 按登录账号 + period 查库全量统计
 * 各阶段户数：{@code {all, collect, bookkeep, carry, predeclare, tax, done}}。
 * 取代 BT「按分页数据前端派生」的不准计数（全量、不分页、按用户归属、口径同 flow_period.st_*）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/workbench")
@SaIgnore
public class OpenApiWorkbenchController {

    private final WorkbenchPipelineService workbenchPipelineService;

    @GetMapping("/pipeline-counts")
    public JsonResult pipelineCounts(@RequestParam(required = false) Long userId,
                                     @RequestParam String period) {
        if (userId == null) {
            return JsonResult.failure("userId 不能为空（按登录账号统计其名下账套）");
        }
        if (period == null || period.isBlank()) {
            return JsonResult.failure("period 不能为空（格式 YYYY-MM）");
        }
        try {
            return JsonResult.successful(workbenchPipelineService.pipelineCountsByUser(userId, period.trim()));
        } catch (Exception e) {
            log.error("[FN-PIPELINE-COUNTS] 统计失败 userId={} period={}", userId, period, e);
            return JsonResult.failure("流水线计数失败：" + e.getMessage());
        }
    }
}
