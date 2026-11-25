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
 * 开放 API — 预申报财务确认（FN-PREDECLARE-CONFIRM-FLAG）。
 * 路径前缀 {@code /openapi/v1/pre-declare}。
 *
 * <p>报税前先出税务局样式申报表预览给财务确认，本端点把「已确认」从 BT 前端 gate 落库到
 * {@code TaskPeriod.preDeclareConfirmed(+At/+By)}——刷新/换设备不丢、可审计、可防重报。
 * 确认状态由 {@code TaskWorkbenchService.getDetail()} 暴露给 BT 渲染。
 *
 * <ul>
 *   <li>{@code POST /confirm}：置 {@code pre_declare_confirmed=1 + at + by}，幂等（重复确认刷新时间/操作人）。</li>
 *   <li>{@code POST /cancel}：撤销确认回 0（数据有误退回重做），幂等。</li>
 * </ul>
 *
 * <p><b>不改 declare-data(FN-RPA-02) / 做账 / 报税逻辑</b>；报税硬校验为可选开关、默认关闭，
 * 本端点只做持久化（见工单 §2.4/§3）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/pre-declare")
@SaIgnore
public class OpenApiPreDeclareController {

    private final TaskWorkbenchService taskWorkbenchService;

    @PostMapping("/confirm")
    public JsonResult confirm(@RequestBody PreDeclareReq req) {
        JsonResult bad = validate(req);
        if (bad != null) return bad;
        try {
            return JsonResult.successful(taskWorkbenchService.confirmPreDeclare(
                    req.getAccountSetsId(), req.getPeriod().trim(), req.getConfirmedBy()));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("预申报确认失败 accountSetsId={} period={}", req.getAccountSetsId(), req.getPeriod(), e);
            return JsonResult.failure("预申报确认失败：" + e.getMessage());
        }
    }

    @PostMapping("/cancel")
    public JsonResult cancel(@RequestBody PreDeclareReq req) {
        JsonResult bad = validate(req);
        if (bad != null) return bad;
        try {
            return JsonResult.successful(taskWorkbenchService.cancelPreDeclare(
                    req.getAccountSetsId(), req.getPeriod().trim()));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("预申报撤销失败 accountSetsId={} period={}", req.getAccountSetsId(), req.getPeriod(), e);
            return JsonResult.failure("预申报撤销失败：" + e.getMessage());
        }
    }

    private JsonResult validate(PreDeclareReq req) {
        if (req == null || req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (StrUtil.isBlank(req.getPeriod())) {
            return JsonResult.failure("period 不能为空（yyyy-MM）");
        }
        return null;
    }

    @Data
    public static class PreDeclareReq {
        private Long accountSetsId;
        private String period;
        /** 操作人（BT 传入；@SaIgnore 取不到登录人时由服务端回退 system） */
        private String confirmedBy;
    }
}
