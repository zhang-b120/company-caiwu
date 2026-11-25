package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.CustomerGroup;
import cn.gson.financial.kernel.service.CustomerGroupService;
import cn.hutool.core.util.StrUtil;
import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.read.listener.ReadListener;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * 开放 API — 客户群路由（FN-09 + FN-FIX-07）
 * <p>
 * 提供给企微客服服务（wecom-cs-service / WC 团队）使用，全部接口免鉴权（@SaIgnore）。
 * <p>
 * <b>FN-FIX-07 路径迁移：</b>
 * <ul>
 *   <li>主路径：<code>/openapi/v1/customer-groups</code>（复数，WC 期望）</li>
 *   <li>兼容旧路径：<code>/openapi/v1/customer-group</code>（单数，保留至少 1 个 sprint，最终 deprecated）</li>
 * </ul>
 * <p>
 * <b>FN-FIX-07 DTO 迁移：</b>
 * <ul>
 *   <li>GET 接口（list / by-chat-id / detail / by-employee）统一返回平铺 DTO {@link CustomerGroupFlatDto}
 *       — 含 <code>employeeUserids[]</code> 与 <code>roles{}</code></li>
 *   <li>POST <code>/customer-groups</code>（新）接收平铺 DTO {@link CustomerGroupFlatReq}</li>
 *   <li>POST <code>/customer-group</code>（旧）保留接收嵌套 DTO {@link CustomerGroupReq}，向后兼容</li>
 * </ul>
 * <p>
 * 类级别 mapping 只到 <code>/openapi/v1</code>，每个方法自己声明完整的 customer-groups/customer-group 双路径数组，
 * 从而能让"新 POST 走 customer-groups + 旧 POST 走 customer-group"两条 distinct 路径并存。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1")
@SaIgnore
public class OpenApiCustomerGroupController {

    /** 复数主路径 + 单数兼容路径，用于所有 GET / PUT / 共享 endpoint。 */
    static final String[] PATHS_BOTH = {"/customer-groups", "/customer-group"};

    /** 新主路径（仅复数），用于平铺 DTO 的 POST。 */
    static final String PATH_PLURAL = "/customer-groups";

    /** 旧兼容路径（仅单数），用于嵌套 DTO 的 POST，标记 @Deprecated。 */
    static final String PATH_SINGULAR_LEGACY = "/customer-group";

    private final CustomerGroupService customerGroupService;

    // ═══════════════════════════════════════════════════════════════
    // 1. 按 wecom_chat_id 反查（企微客服服务高频调用）
    // ═══════════════════════════════════════════════════════════════

    @GetMapping({
            PATH_PLURAL + "/by-chat-id/{chatId}",
            PATH_SINGULAR_LEGACY + "/by-chat-id/{chatId}"
    })
    public JsonResult getByChatId(@PathVariable String chatId) {
        if (StrUtil.isBlank(chatId)) {
            return JsonResult.failure("chatId 不能为空");
        }
        return customerGroupService.findByWecomChatId(chatId)
                .map(g -> JsonResult.successful(toFlatDto(g)))
                .orElseGet(() -> JsonResult.failure("customer_group 不存在: chatId=" + chatId));
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. 按 ID 查详情
    // ═══════════════════════════════════════════════════════════════

    @GetMapping({
            PATH_PLURAL + "/{id}",
            PATH_SINGULAR_LEGACY + "/{id}"
    })
    public JsonResult detail(@PathVariable Long id) {
        return customerGroupService.findById(id)
                .map(g -> JsonResult.successful(toFlatDto(g)))
                .orElseGet(() -> JsonResult.failure("customer_group 不存在: " + id));
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. 单条创建 — 新平铺 DTO 入口（主路径，复数）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 新接口：POST /openapi/v1/customer-groups
     * 接收 {@link CustomerGroupFlatReq}（含 roles map），内部转换为 entity 后存储。
     */
    @PostMapping(PATH_PLURAL)
    public JsonResult createFlat(@RequestBody CustomerGroupFlatReq req) {
        if (req == null) {
            return JsonResult.failure("请求体不能为空");
        }
        // 转嵌套 req，复用现有 validate / toEntity / 重名校验
        CustomerGroupReq nested = flatToNested(req);
        String err = validate(nested);
        if (err != null) {
            return JsonResult.failure(err);
        }
        if (customerGroupService.existsByWecomChatId(nested.getWecomChatId().trim())) {
            return JsonResult.failure("wecomChatId 已存在: " + nested.getWecomChatId());
        }
        try {
            CustomerGroup saved = customerGroupService.save(toEntity(nested, null));
            return JsonResult.successful(toFlatDto(saved));
        } catch (Exception e) {
            log.error("openapi customer-groups 创建失败（flat）", e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "创建失败");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 3-legacy. 单条创建 — 旧嵌套 DTO 入口（兼容路径，单数）
    // ═══════════════════════════════════════════════════════════════

    /**
     * <b>@Deprecated</b> 旧接口：POST /openapi/v1/customer-group
     * 接收 {@link CustomerGroupReq}（嵌套 4 个 *_employee_id），保留 1 个 sprint 兼容。
     */
    @Deprecated
    @PostMapping(PATH_SINGULAR_LEGACY)
    public JsonResult create(@RequestBody CustomerGroupReq req) {
        String err = validate(req);
        if (err != null) {
            return JsonResult.failure(err);
        }
        if (customerGroupService.existsByWecomChatId(req.getWecomChatId().trim())) {
            return JsonResult.failure("wecomChatId 已存在: " + req.getWecomChatId());
        }
        try {
            CustomerGroup saved = customerGroupService.save(toEntity(req, null));
            // 返回平铺 DTO（统一对外 schema）
            return JsonResult.successful(toFlatDto(saved));
        } catch (Exception e) {
            log.error("openapi customer-group 创建失败（legacy nested）", e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "创建失败");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. 批量创建（独立事务，逐条提交，一条失败不影响其他）
    // ═══════════════════════════════════════════════════════════════

    @PostMapping({
            PATH_PLURAL + "/batch",
            PATH_SINGULAR_LEGACY + "/batch"
    })
    public JsonResult batch(@RequestBody BatchReq req) {
        if (req == null || req.getGroups() == null || req.getGroups().isEmpty()) {
            return JsonResult.failure("groups 不能为空");
        }
        return doBatch(req.getGroups());
    }

    private JsonResult doBatch(List<CustomerGroupReq> items) {
        // 通过 SpringUtil 拿到自身代理，确保 REQUIRES_NEW 事务生效
        CustomerGroupService self = customerGroupService;
        List<Map<String, Object>> results = new ArrayList<>(items.size());
        int succeeded = 0;
        int failed = 0;
        for (int i = 0; i < items.size(); i++) {
            CustomerGroupReq item = items.get(i);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("index", i);
            r.put("wecomChatId", item == null ? null : item.getWecomChatId());
            try {
                String err = validate(item);
                if (err != null) {
                    throw new IllegalArgumentException(err);
                }
                if (self.existsByWecomChatId(item.getWecomChatId().trim())) {
                    throw new IllegalArgumentException("wecomChatId 已存在");
                }
                CustomerGroup saved = self.saveInNewTransaction(toEntity(item, null));
                r.put("status", "ok");
                r.put("id", saved.getId());
                succeeded++;
            } catch (Exception e) {
                r.put("status", "error");
                r.put("message", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                failed++;
            }
            results.add(r);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalSubmitted", items.size());
        data.put("succeeded", succeeded);
        data.put("failed", failed);
        data.put("results", results);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. Excel 上传批量导入
    // 列顺序：wecom_chat_id | group_name | customer_id | sales_employee_id |
    //         cs_employee_id | accountant_employee_id | gov_employee_id
    // ═══════════════════════════════════════════════════════════════

    @PostMapping({
            PATH_PLURAL + "/import-excel",
            PATH_SINGULAR_LEGACY + "/import-excel"
    })
    public JsonResult importExcel(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return JsonResult.failure("file 不能为空");
        }
        List<CustomerGroupReq> rows;
        try {
            rows = parseExcel(file);
        } catch (Exception e) {
            log.error("openapi customer-group Excel 解析失败", e);
            return JsonResult.failure("Excel 解析失败: " + e.getMessage());
        }
        if (rows.isEmpty()) {
            return JsonResult.failure("Excel 没有有效数据行");
        }
        return doBatch(rows);
    }

    private List<CustomerGroupReq> parseExcel(MultipartFile file) throws Exception {
        List<CustomerGroupReq> rows = new ArrayList<>();
        try (java.io.InputStream is = file.getInputStream()) {
            EasyExcel.read(is)
                    .sheet(0)
                    .headRowNumber(1)
                    .registerReadListener(new ReadListener<Map<Integer, String>>() {
                        @Override
                        public void invoke(Map<Integer, String> data, AnalysisContext context) {
                            if (data == null || data.isEmpty()) {
                                return;
                            }
                            String wecomChatId = trim(data.get(0));
                            if (StrUtil.isBlank(wecomChatId)) {
                                // 整行 wecomChatId 为空跳过（可能是末尾空行）
                                return;
                            }
                            CustomerGroupReq req = new CustomerGroupReq();
                            req.setWecomChatId(wecomChatId);
                            req.setGroupName(trim(data.get(1)));
                            req.setCustomerId(parseLong(data.get(2)));
                            req.setSalesEmployeeId(parseLong(data.get(3)));
                            req.setCsEmployeeId(parseLong(data.get(4)));
                            req.setAccountantEmployeeId(parseLong(data.get(5)));
                            req.setGovEmployeeId(parseLong(data.get(6)));
                            rows.add(req);
                        }

                        @Override
                        public void doAfterAllAnalysed(AnalysisContext context) {
                        }
                    })
                    .doRead();
        }
        return rows;
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static Long parseLong(String s) {
        String t = trim(s);
        if (t == null) return null;
        try {
            // 兼容 Excel 数字单元格被读成 "12345.0"
            int dot = t.indexOf('.');
            if (dot >= 0) {
                t = t.substring(0, dot);
            }
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法解析为 Long: " + s);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 6. 调整 4 员工
    // ═══════════════════════════════════════════════════════════════

    @PutMapping({
            PATH_PLURAL + "/{id}/employees",
            PATH_SINGULAR_LEGACY + "/{id}/employees"
    })
    public JsonResult updateEmployees(@PathVariable Long id, @RequestBody UpdateEmployeesReq req) {
        if (req == null) {
            return JsonResult.failure("body 不能为空");
        }
        try {
            CustomerGroup saved = customerGroupService.updateEmployees(id,
                    req.getSalesEmployeeId(),
                    req.getCsEmployeeId(),
                    req.getAccountantEmployeeId(),
                    req.getGovEmployeeId());
            return JsonResult.successful(toFlatDto(saved));
        } catch (IllegalArgumentException e) {
            return JsonResult.failure(e.getMessage());
        } catch (Exception e) {
            log.error("openapi customer-group updateEmployees 失败", e);
            return JsonResult.failure(e.getMessage() != null ? e.getMessage() : "更新失败");
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 7. 查某员工负责的所有群
    // ═══════════════════════════════════════════════════════════════

    @GetMapping({
            PATH_PLURAL + "/by-employee/{eid}",
            PATH_SINGULAR_LEGACY + "/by-employee/{eid}"
    })
    public JsonResult byEmployee(@PathVariable("eid") Long employeeId) {
        if (employeeId == null) {
            return JsonResult.failure("employeeId 不能为空");
        }
        List<CustomerGroup> list = customerGroupService.findByEmployeeId(employeeId);
        List<CustomerGroupFlatDto> result = new ArrayList<>(list.size());
        for (CustomerGroup g : list) {
            result.add(toFlatDto(g));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("employeeId", employeeId);
        data.put("total", result.size());
        data.put("groups", result);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助
    // ═══════════════════════════════════════════════════════════════

    private static String validate(CustomerGroupReq req) {
        if (req == null) return "请求体不能为空";
        if (StrUtil.isBlank(req.getWecomChatId())) return "wecomChatId 不能为空";
        if (req.getCustomerId() == null) return "customerId 不能为空";
        return null;
    }

    private static CustomerGroup toEntity(CustomerGroupReq req, CustomerGroup base) {
        CustomerGroup g = base != null ? base : new CustomerGroup();
        g.setWecomChatId(req.getWecomChatId().trim());
        g.setGroupName(req.getGroupName());
        g.setCustomerId(req.getCustomerId());
        g.setSalesEmployeeId(req.getSalesEmployeeId());
        g.setCsEmployeeId(req.getCsEmployeeId());
        g.setAccountantEmployeeId(req.getAccountantEmployeeId());
        g.setGovEmployeeId(req.getGovEmployeeId());
        if (req.getIsActive() != null) {
            g.setIsActive(req.getIsActive());
        } else if (g.getIsActive() == null) {
            g.setIsActive(Boolean.TRUE);
        }
        return g;
    }

    /**
     * 平铺 → 嵌套 转换（仅供 createFlat 等新 endpoint 复用现有 validate / toEntity）。
     * 优先级：roles map 中的值 > flat req 顶层字段。
     * 这样调用方可以二选一：填 roles 或填顶层 *EmployeeId 都行。
     */
    static CustomerGroupReq flatToNested(CustomerGroupFlatReq flat) {
        CustomerGroupReq r = new CustomerGroupReq();
        r.setWecomChatId(flat.getWecomChatId());
        r.setGroupName(flat.getGroupName());
        r.setCustomerId(flat.getCustomerId());
        r.setIsActive(flat.getIsActive());
        Map<String, Long> roles = flat.getRoles();
        // 顶层字段作为 fallback
        Long sales = roles != null && roles.containsKey("sales") ? roles.get("sales") : flat.getSalesEmployeeId();
        Long cs = roles != null && roles.containsKey("cs") ? roles.get("cs") : flat.getCsEmployeeId();
        Long accountant = roles != null && roles.containsKey("accountant") ? roles.get("accountant") : flat.getAccountantEmployeeId();
        Long gov = roles != null && roles.containsKey("gov") ? roles.get("gov") : flat.getGovEmployeeId();
        r.setSalesEmployeeId(sales);
        r.setCsEmployeeId(cs);
        r.setAccountantEmployeeId(accountant);
        r.setGovEmployeeId(gov);
        return r;
    }

    /**
     * 实体 → 平铺 DTO（FN-FIX-07 统一对外 schema）。
     * <ul>
     *   <li><code>employeeUserids</code>：4 个 *_employee_id 去重后的有序列表
     *       （顺序 sales→cs→accountant→gov，null 跳过；用 LinkedHashSet 实现）</li>
     *   <li><code>roles</code>：仅放有值的 role → employee_id 反查 map
     *       （null 不放，避免 JSON 噪声；按 sales/cs/accountant/gov 顺序）</li>
     * </ul>
     */
    static CustomerGroupFlatDto toFlatDto(CustomerGroup g) {
        CustomerGroupFlatDto d = new CustomerGroupFlatDto();
        d.setId(g.getId());
        d.setWecomChatId(g.getWecomChatId());
        d.setCustomerId(g.getCustomerId());
        d.setGroupName(g.getGroupName());
        d.setIsActive(g.getIsActive());

        // employeeUserids: LinkedHashSet 按 sales/cs/accountant/gov 顺序去重
        LinkedHashSet<Long> set = new LinkedHashSet<>(4);
        if (g.getSalesEmployeeId() != null) set.add(g.getSalesEmployeeId());
        if (g.getCsEmployeeId() != null) set.add(g.getCsEmployeeId());
        if (g.getAccountantEmployeeId() != null) set.add(g.getAccountantEmployeeId());
        if (g.getGovEmployeeId() != null) set.add(g.getGovEmployeeId());
        d.setEmployeeUserids(new ArrayList<>(set));

        // roles: 用 LinkedHashMap 按字段顺序，null 不放
        LinkedHashMap<String, Long> roles = new LinkedHashMap<>(4);
        if (g.getSalesEmployeeId() != null) roles.put("sales", g.getSalesEmployeeId());
        if (g.getCsEmployeeId() != null) roles.put("cs", g.getCsEmployeeId());
        if (g.getAccountantEmployeeId() != null) roles.put("accountant", g.getAccountantEmployeeId());
        if (g.getGovEmployeeId() != null) roles.put("gov", g.getGovEmployeeId());
        d.setRoles(roles);

        d.setCreatedAt(g.getCreatedAt() != null ? g.getCreatedAt().toString() : null);
        d.setUpdatedAt(g.getUpdatedAt() != null ? g.getUpdatedAt().toString() : null);
        return d;
    }

    // ═══════════════════════════════════════════════════════════════
    // 请求 DTO（嵌套，旧接口兼容）
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class CustomerGroupReq {
        private String wecomChatId;
        private String groupName;
        private Long customerId;
        private Long salesEmployeeId;
        private Long csEmployeeId;
        private Long accountantEmployeeId;
        private Long govEmployeeId;
        private Boolean isActive;
    }

    @Data
    public static class BatchReq {
        private List<CustomerGroupReq> groups;
    }

    @Data
    public static class UpdateEmployeesReq {
        private Long salesEmployeeId;
        private Long csEmployeeId;
        private Long accountantEmployeeId;
        private Long govEmployeeId;
    }

    // ═══════════════════════════════════════════════════════════════
    // 请求 DTO（平铺，新接口 — 给 WC 使用）
    // ═══════════════════════════════════════════════════════════════

    /**
     * FN-FIX-07 新平铺创建请求体。
     * <p>
     * 优先 <code>roles</code> map（推荐写法）；若 map 缺某 key，fallback 到顶层 <code>*EmployeeId</code>。
     * 两种写法都接受，方便迁移期共存。
     */
    @Data
    public static class CustomerGroupFlatReq {
        private String wecomChatId;
        private String groupName;
        private Long customerId;
        private Boolean isActive;
        /** 推荐：用 roles map 传 4 个角色 */
        private Map<String, Long> roles;
        /** fallback：顶层字段（旧字段名兼容） */
        private Long salesEmployeeId;
        private Long csEmployeeId;
        private Long accountantEmployeeId;
        private Long govEmployeeId;
    }

    // ═══════════════════════════════════════════════════════════════
    // 响应 DTO（平铺 — FN-FIX-07 统一对外 schema）
    // ═══════════════════════════════════════════════════════════════

    /**
     * FN-FIX-07 平铺响应 DTO（给 WC 调用）。
     * <ul>
     *   <li><code>employeeUserids</code>：4 个 employee_id 去重后的列表</li>
     *   <li><code>roles</code>：role → employee_id 反查 map（仅含非 null）</li>
     * </ul>
     */
    @Data
    public static class CustomerGroupFlatDto {
        private Long id;
        private String wecomChatId;
        private Long customerId;
        private String groupName;
        private Boolean isActive;
        private List<Long> employeeUserids;
        private Map<String, Long> roles;
        private String createdAt;
        private String updatedAt;
    }
}
