package cn.gson.financial.ua.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;

/**
 * 小程序端 — 客户资料完整性校验（FN-AL-01 BL-DAY3-06）
 * <p>
 * 路径前缀：/ua/customer（小程序专属域，区别于 BT/XW 桌面端的 /openapi/v1/account-sets/{id}/completeness）
 * <p>
 * 对应 wx 端 financial-mock.checkCompleteness。
 * 字段对齐 wx/utils/financial-mock.js#193-206 与 wx 端
 * components/completeness-card 渲染需要。
 * <p>
 * 数据来源（TODO）：
 * <ul>
 *   <li>BUSINESS_LICENSE / LEGAL_ID_FRONT/BACK / TAX_REGISTRATION
 *       → 接 Customer 实体下的相关资料字段 + FileInfo 表</li>
 *   <li>BANK_OPENING → 接 BankStatement 是否已上传过流水</li>
 *   <li>required[].code 后续做成 required_doc_type 字典表，便于运营追加</li>
 * </ul>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/customer")
@SaIgnore
public class UaCompletenessController {

    /**
     * 客户资料完整性校验
     * <p>GET /ua/customer/uaRequiredDocs/{companyId}
     */
    @GetMapping("/uaRequiredDocs/{companyId}")
    public JsonResult uaRequiredDocs(@PathVariable Long companyId) {
        if (companyId == null) return JsonResult.failure("companyId 不能为空");
        log.info("[uaRequiredDocs] companyId={}", companyId);

        // TODO 接 CustomerService / FileInfoService / BankStatementService 真实判断
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("companyId", companyId);

        List<Map<String, Object>> required = new ArrayList<>();
        required.add(doc("BUSINESS_LICENSE", "营业执照",         true,  false, null));
        required.add(doc("LEGAL_ID_FRONT",   "法人身份证正面",   true,  false, null));
        required.add(doc("LEGAL_ID_BACK",    "法人身份证反面",   false, true,
                "/pages/company-info-guide/company-info-guide?focus=legalIdBack"));
        required.add(doc("BANK_OPENING",     "银行开户许可证",   false, true,
                "/pages/bankStatement/bankStatement"));
        required.add(doc("TAX_REGISTRATION", "税务登记证",       true,  false, null));
        data.put("required", required);

        long missingCount = required.stream()
                .filter(d -> Boolean.TRUE.equals(d.get("missing"))).count();
        data.put("missingCount", missingCount);

        return JsonResult.successful(data);
    }

    private Map<String, Object> doc(String code, String name, boolean uploaded, boolean missing, String guideUrl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", code);
        m.put("name", name);
        m.put("uploaded", uploaded);
        m.put("missing", missing);
        if (guideUrl != null) m.put("guideUrl", guideUrl);
        return m;
    }
}
