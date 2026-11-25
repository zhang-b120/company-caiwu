package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.service.PendingCompaniesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 开放 API — 未完成企业查询（FN-WB-02 ⭐）。
 *
 * <p>给 BT AI 秘书答"这个月还有哪些企业没做账报税"。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/pending-companies")
@SaIgnore
public class OpenApiPendingCompaniesController {

    private final PendingCompaniesService service;

    @GetMapping
    public JsonResult query(@RequestParam("period") String period,
                            @RequestParam(value = "pending_type", required = false) String pendingType) {
        try {
            PendingCompaniesService.Result r = service.query(period, pendingType);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("total", r.total);
            data.put("records", r.records);
            return JsonResult.successful(data);
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-WB-02] pending-companies failed period={} pt={}", period, pendingType, e);
            return JsonResult.failure("查询未完成企业失败：" + e.getMessage());
        }
    }
}
