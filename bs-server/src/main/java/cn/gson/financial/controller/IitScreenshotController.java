package cn.gson.financial.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.IitScreenshotService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 个税截图看板
 *
 * <p>GET  /iit-screenshot          - 分页列表（账套+账期维度，聚合截图 URL）</p>
 * <p>GET  /iit-screenshot/image    - 流式输出压缩图（thumb/medium），带缓存</p>
 * <p>POST /iit-screenshot/send-status - 标记截图是否已发给客户（upsert）</p>
 */
@Slf4j
@SaCheckLogin
@RestController
@RequiredArgsConstructor
@RequestMapping("/iit-screenshot")
public class IitScreenshotController {

    private final IitScreenshotService iitScreenshotService;

    /** 分页列表：每行一个账套在指定账期的截图汇总 */
    @GetMapping
    public JsonResult list(ListQuery q, @SessionUser UserVo userVo) {
        if (q.getTaxPeriod() == null || !q.getTaxPeriod().matches("\\d{4}-\\d{2}")) {
            throw new IllegalArgumentException("报税账期(taxPeriod)必填，格式 YYYY-MM");
        }
        Page page = new Page().setPage(q.getPage()).setPageSize(q.getPageSize());
        Map<String, Object> filters = new HashMap<>();
        filters.put("taxPeriod", q.getTaxPeriod());
        filters.put("keyword",   q.getKeyword());
        filters.put("province",  q.getProvince());
        // sent: null=不过滤, true=1, false=0
        if (q.getSent() != null) {
            filters.put("sent", q.getSent() ? 1 : 0);
        }
        // hasShot: null=不过滤, true=仅有截图(1), false=仅无截图(0)
        if (q.getHasShot() != null) {
            filters.put("hasShot", q.getHasShot() ? 1 : 0);
        }
        return JsonResult.successful(
                iitScreenshotService.listPage(userVo.getId(), filters, page));
    }

    /**
     * 图片代理，variant：
     *   thumb（≤50KB）/ medium（≤400KB）：压缩图，首次生成并落盘，后续命中缓存；带 ETag + Cache-Control。
     *   original：透传原图（attachment），用于下载备查。
     */
    @GetMapping("/image")
    public void image(@RequestParam Long ocTaskId,
                      @RequestParam(defaultValue = "0") int idx,
                      @RequestParam(defaultValue = "thumb") String variant,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        if (!"thumb".equalsIgnoreCase(variant) && !"medium".equalsIgnoreCase(variant)
                && !"original".equalsIgnoreCase(variant)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "variant 只能是 thumb / medium / original");
            return;
        }
        iitScreenshotService.serveImage(ocTaskId, idx, variant, request, response);
    }

    /** 标记截图是否已发给客户（upsert tax_filing_status） */
    @PostMapping("/send-status")
    public JsonResult sendStatus(@RequestBody SendStatusRequest body,
                                 @SessionUser UserVo userVo) {
        if (body.getAccountSetId() == null) throw new IllegalArgumentException("accountSetId 不能为空");
        if (body.getTaxPeriod() == null || body.getTaxPeriod().isBlank())
            throw new IllegalArgumentException("taxPeriod 不能为空");
        if (body.getSent() == null) throw new IllegalArgumentException("sent 不能为空");
        iitScreenshotService.setSendStatus(
                body.getAccountSetId(), body.getTaxPeriod(), body.getSent(), userVo.getId());
        return JsonResult.successful();
    }

    @Data
    public static class ListQuery {
        private Integer page = 1;
        private Integer pageSize = 20;
        /** 必填，YYYY-MM 格式 */
        private String taxPeriod;
        /** 公司名 / 税号模糊搜索 */
        private String keyword;
        /** 省份 */
        private String province;
        /** null=全部, true=已发送, false=未发送 */
        private Boolean sent;
        /** null=全部, true=仅有截图, false=仅无截图 */
        private Boolean hasShot;
    }

    @Data
    public static class SendStatusRequest {
        private Long accountSetId;
        private String taxPeriod;
        private Boolean sent;
    }
}
