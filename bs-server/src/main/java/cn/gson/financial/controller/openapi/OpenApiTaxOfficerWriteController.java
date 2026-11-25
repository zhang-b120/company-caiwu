package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.task.workbench.service.TaskWorkbenchService;
import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 开放 API — 税局登录信息（办税员 / 代理通道）<b>写端点</b>（FN-TAX-LOGIN-CREDENTIALS-WRITE-API）。
 * 路径前缀 {@code /openapi/v1/account-sets/{accountSetId}/tax-officers}。
 *
 * <p>供 BT「每账套维护税局登录信息」用。<b>统一走 {@code tax_officer}</b>（读端点 {@code GET /openapi/v1/tax-officers}
 * 已返 loginMode），不再用分裂的 {@code tax_login_account}。复用内部 {@code TaskWorkbenchService.saveTaxOfficer/
 * deleteTaxOfficer/setDefaultOfficer}（与 {@code /api/task-workbench/tax-officers} 同 service，单一真相源）。
 *
 * <ul>
 *   <li>{@code POST   /openapi/v1/account-sets/{id}/tax-officers}        — 新增/更新（含 loginMode、proxy 代理通道）</li>
 *   <li>{@code DELETE /openapi/v1/account-sets/{id}/tax-officers/{oid}}  — 删除</li>
 *   <li>{@code PUT    /openapi/v1/account-sets/{id}/tax-officers/{oid}/default} — 设默认</li>
 * </ul>
 *
 * <p><b>代理通道</b>（{@code loginType=proxy}）：{@code proxyTaxNo}=代理机构税号、{@code phone}=代理平台账号、
 * {@code password}=代理平台密码。
 *
 * <p>⚠️ <b>安全债（jianye 2026-06-05 确认本期不做）</b>：写端点暂不鉴权（与现有 @SaIgnore 读口一致），
 * 密码明文存/下发，记入安全加固待办，后续统一补 token + 加密。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/account-sets/{accountSetId}/tax-officers")
@SaIgnore
public class OpenApiTaxOfficerWriteController {

    static final Set<String> ALLOWED_LOGIN_MODE = Set.of("auto", "manual");
    /** loginType 白名单（含旧值兼容）；缺省由 service 兜底 officer。 */
    static final Set<String> ALLOWED_LOGIN_TYPE = Set.of("officer", "proxy", "legal_person", "ca", "password");
    /** FN-TAX-LOGIN-MANUAL-METHOD-FIELD：手动登录子方式白名单。 */
    static final Set<String> ALLOWED_MANUAL_METHOD = Set.of("qrcode", "sms");

    private final TaskWorkbenchService taskWorkbenchService;

    @PostMapping
    public JsonResult save(@PathVariable Long accountSetId, @RequestBody TaxOfficerReq req) {
        if (accountSetId == null) return JsonResult.failure("accountSetId 不能为空");
        if (req == null || StrUtil.isBlank(req.getName())) return JsonResult.failure("name 不能为空");
        // loginMode 白名单（核心新字段，严格校验）；缺省 auto
        String loginMode = StrUtil.isBlank(req.getLoginMode()) ? "auto" : req.getLoginMode().trim();
        if (!ALLOWED_LOGIN_MODE.contains(loginMode)) {
            return JsonResult.failure("loginMode 取值非法：" + req.getLoginMode() + "，允许：" + ALLOWED_LOGIN_MODE);
        }
        String loginType = StrUtil.isBlank(req.getLoginType()) ? "officer" : req.getLoginType().trim();
        if (!ALLOWED_LOGIN_TYPE.contains(loginType)) {
            return JsonResult.failure("loginType 取值非法：" + req.getLoginType() + "，允许：" + ALLOWED_LOGIN_TYPE);
        }
        // FN-TAX-LOGIN-MANUAL-METHOD-FIELD：手动子方式校验
        String manualMethod = StrUtil.isBlank(req.getManualMethod()) ? null : req.getManualMethod().trim();
        if ("manual".equals(loginMode)) {
            // manual 必填 qrcode/sms
            if (manualMethod == null) {
                return JsonResult.failure("loginMode=manual 时 manualMethod 必填（qrcode/sms）");
            }
            if (!ALLOWED_MANUAL_METHOD.contains(manualMethod)) {
                return JsonResult.failure("manualMethod 取值非法：" + req.getManualMethod() + "，允许：" + ALLOWED_MANUAL_METHOD);
            }
            // manual+sms 需 phone+password；manual+qrcode 不强制凭证（省份取自账套，不在本表存）
            if ("sms".equals(manualMethod)
                    && (StrUtil.isBlank(req.getPhone()) || StrUtil.isBlank(req.getPassword()))) {
                return JsonResult.failure("loginMode=manual + manualMethod=sms 需 phone + password 非空");
            }
        } else {
            // auto 忽略 manualMethod（即使传了也置空，保持数据干净）
            manualMethod = null;
        }
        try {
            // 组装 saveTaxOfficer 期望的 snake_case body（复用内部 service，单一真相源）
            Map<String, Object> body = new LinkedHashMap<>();
            if (req.getId() != null) body.put("id", req.getId());
            body.put("name", req.getName());
            body.put("phone", req.getPhone());
            body.put("password", req.getPassword());
            body.put("login_type", loginType);
            body.put("login_mode", loginMode);
            body.put("manual_method", manualMethod);
            body.put("proxy_tax_no", req.getProxyTaxNo());
            body.put("id_card", req.getIdCard());
            body.put("is_default", Boolean.TRUE.equals(req.getIsDefault()));
            body.put("remark", req.getRemark());
            return JsonResult.successful(taskWorkbenchService.saveTaxOfficer(accountSetId, body));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-TAX-LOGIN] 保存办税员失败 accountSetId={}", accountSetId, e);
            return JsonResult.failure("保存失败：" + e.getMessage());
        }
    }

    @DeleteMapping("/{officerId}")
    public JsonResult delete(@PathVariable Long accountSetId, @PathVariable Long officerId) {
        if (accountSetId == null || officerId == null) return JsonResult.failure("accountSetId / officerId 不能为空");
        try {
            return JsonResult.successful(taskWorkbenchService.deleteTaxOfficer(officerId, accountSetId));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-TAX-LOGIN] 删除办税员失败 accountSetId={} officerId={}", accountSetId, officerId, e);
            return JsonResult.failure("删除失败：" + e.getMessage());
        }
    }

    @PutMapping("/{officerId}/default")
    public JsonResult setDefault(@PathVariable Long accountSetId, @PathVariable Long officerId) {
        if (accountSetId == null || officerId == null) return JsonResult.failure("accountSetId / officerId 不能为空");
        try {
            return JsonResult.successful(taskWorkbenchService.setDefaultOfficer(officerId, accountSetId));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("[FN-TAX-LOGIN] 设默认办税员失败 accountSetId={} officerId={}", accountSetId, officerId, e);
            return JsonResult.failure("设默认失败：" + e.getMessage());
        }
    }

    @Data
    public static class TaxOfficerReq {
        private Long id;            // 传则更新，不传则新增（同账套同 phone 也会命中更新）
        private String name;        // 办税员姓名 / 代理机构名 /（qrcode 时）占位名如"扫码登录"
        private String loginMode;   // auto | manual（缺省 auto）
        private String loginType;   // officer | proxy | legal_person | ca | password（缺省 officer；manual 下无意义）
        private String manualMethod;// qrcode | sms（仅 loginMode=manual 必填）
        private String phone;       // 登录手机号 / 办税员账号 /（proxy 时）代理平台账号
        private String password;    // 电税密码 /（proxy 时）代理平台密码
        private String proxyTaxNo;  // 代理机构税号（loginType=proxy）
        private String idCard;      // 法人身份证号（loginType=legal_person）
        private Boolean isDefault;  // 设为默认
        private String remark;
    }
}
