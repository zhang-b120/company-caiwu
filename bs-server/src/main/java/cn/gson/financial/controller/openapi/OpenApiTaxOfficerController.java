package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.task.workbench.service.TaskWorkbenchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放 API — 办税员信息（brower-tax 使用）
 * <p>
 * GET /openapi/v1/tax-officers?accountSetId=xxx
 * 返回账套下所有办税员及其登录凭证，全部接口免鉴权（@SaIgnore）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/tax-officers")
@SaIgnore
public class OpenApiTaxOfficerController {

    private final TaskWorkbenchService taskWorkbenchService;

    /**
     * GET /openapi/v1/tax-officers?accountSetId=xxx
     * 返回该账套下所有办税员列表，默认办税员排在第一位（isDefault=true）
     */
    @GetMapping
    public JsonResult getTaxOfficers(@RequestParam Long accountSetId) {
        try {
            return JsonResult.successful(taskWorkbenchService.getTaxOfficers(accountSetId));
        } catch (Exception e) {
            log.error("openapi tax-officers 查询失败 accountSetId={}", accountSetId, e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "查询失败");
        }
    }
}
