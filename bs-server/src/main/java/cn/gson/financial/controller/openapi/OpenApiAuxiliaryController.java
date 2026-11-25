package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.AccountingCategoryDto;
import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.entity.AccountingCategory;
import cn.gson.financial.kernel.model.entity.AuxiliaryDetail;
import cn.gson.financial.kernel.model.entity.Customer;
import cn.gson.financial.kernel.model.entity.Department;
import cn.gson.financial.kernel.model.entity.Project;
import cn.gson.financial.kernel.model.entity.QAuxiliaryDetail;
import cn.gson.financial.kernel.model.entity.Vendor;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.hibernate.PrimaryKey;
import cn.gson.financial.kernel.model.repo.AuxiliaryDetailRepository;
import cn.gson.financial.kernel.service.AbsAuxiliaryDataService;
import cn.gson.financial.kernel.service.AccountingCategoryService;
import cn.gson.financial.kernel.service.CustomerService;
import cn.gson.financial.kernel.service.DepartmentService;
import cn.gson.financial.kernel.service.ProjectService;
import cn.gson.financial.kernel.service.VendorService;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 开放 API — 辅助核算
 * 路径前缀 /openapi/v1/auxiliary
 *
 * 包含：
 *  §2.2.1 GET  /categories                              类别列表
 *  §2.2.2 GET  /categories/{categoryId}/items           明细项列表
 *  §P2.1  POST /items                                   创建明细项（4 种类别）
 *  §P2.1  POST /items/batch                             批量创建
 *  §P2.2  POST /details/find-or-create                  组合查询/创建
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/auxiliary")
@SaIgnore
public class OpenApiAuxiliaryController {

    private final AccountingCategoryService accountingCategoryService;
    private final CustomerService customerService;
    private final VendorService vendorService;
    private final DepartmentService departmentService;
    private final ProjectService projectService;
    private final AuxiliaryDetailRepository auxiliaryDetailRepository;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QAuxiliaryDetail qAuxiliaryDetail = QAuxiliaryDetail.auxiliaryDetail;

    /** 接口 1 / 1b 支持的辅助类别（决策：常用四种） */
    private static final Set<AccountingCategoryType> SUPPORTED_TYPES = EnumSet.of(
            AccountingCategoryType.CUSTOMER,
            AccountingCategoryType.VENDOR,
            AccountingCategoryType.DEPARTMENT,
            AccountingCategoryType.PROJECT
    );

    // ═══════════════════════════════════════════════════════════════
    // §2.2.1 辅助核算类别列表
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/categories")
    public JsonResult categories(@RequestParam Long accountSetsId) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        List<AccountingCategoryDto> categories = accountingCategoryService.listByAccountSetsId(accountSetsId);
        if (categories == null) {
            categories = Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (AccountingCategoryDto c : categories) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("accountSetsId", c.getAccountSetsId());
            m.put("name", c.getName());
            m.put("categoryType", c.getCategoryType() != null ? c.getCategoryType().name() : null);
            m.put("systemDefault", Boolean.TRUE.equals(c.getSystemDefault()));
            m.put("canEdit", Boolean.TRUE.equals(c.getCanEdit()));
            result.add(m);
        }
        return JsonResult.successful(result);
    }

    // ═══════════════════════════════════════════════════════════════
    // §2.2.2 某类别下的明细项
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/categories/{categoryId}/items")
    public JsonResult items(@PathVariable Long categoryId,
                            @RequestParam Long accountSetsId) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }

        Set<Long> idSet = new HashSet<>();
        idSet.add(categoryId);
        List<AccountingCategoryDto> categories = accountingCategoryService.listByIdsWidthData(accountSetsId, idSet);
        if (categories == null || categories.isEmpty()) {
            return JsonResult.failure("辅助核算类别不存在：" + categoryId);
        }

        AccountingCategoryDto category = categories.get(0);
        List<? extends AcDetails> details = category.getDetails();
        if (details == null) {
            details = Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (AcDetails d : details) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("accountSetsId", d.getAccountSetsId());
            m.put("categoryId", categoryId);
            m.put("code", d.getCode());
            m.put("name", d.getName());
            m.put("enabled", d.getEnable() == null || Boolean.TRUE.equals(d.getEnable()));
            result.add(m);
        }
        return JsonResult.successful(result);
    }

    // ═══════════════════════════════════════════════════════════════
    // 接口 1：创建辅助核算明细项
    // POST /openapi/v1/auxiliary/items
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/items")
    @Transactional
    public JsonResult createItem(@RequestBody AuxiliaryItemReq req) {
        return doCreateItem(req);
    }

    // ═══════════════════════════════════════════════════════════════
    // 接口 1b：批量创建辅助核算明细项
    // POST /openapi/v1/auxiliary/items/batch
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/items/batch")
    public JsonResult createItemBatch(@RequestBody AuxiliaryItemBatchReq req) {
        if (req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (req.getItems() == null || req.getItems().isEmpty()) {
            return JsonResult.failure("items 不能为空");
        }

        OpenApiAuxiliaryController self = cn.hutool.extra.spring.SpringUtil.getBean(OpenApiAuxiliaryController.class);

        List<Map<String, Object>> results = new ArrayList<>();
        int succeeded = 0;
        int failed = 0;

        for (int i = 0; i < req.getItems().size(); i++) {
            AuxiliaryItemReq item = req.getItems().get(i);
            if (item.getAccountSetsId() == null) {
                item.setAccountSetsId(req.getAccountSetsId());
            }

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("index", i);
            try {
                JsonResult single = self.createItemInNewTransaction(item);
                if (single.isSuccess()) {
                    Object data = single.getData();
                    r.put("status", "ok");
                    if (data instanceof Map) {
                        r.put("item", data);
                    }
                    succeeded++;
                } else {
                    r.put("status", "error");
                    r.put("message", single.getMsg());
                    if (single.getData() instanceof Map<?, ?> m && m.get("errorType") != null) {
                        r.put("errorType", m.get("errorType"));
                    } else {
                        r.put("errorType", "INTERNAL_ERROR");
                    }
                    failed++;
                }
            } catch (Exception e) {
                r.put("status", "error");
                r.put("errorType", "INTERNAL_ERROR");
                r.put("message", e.getMessage());
                failed++;
            }
            results.add(r);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalSubmitted", req.getItems().size());
        data.put("succeeded", succeeded);
        data.put("failed", failed);
        data.put("results", results);
        return JsonResult.successful(data);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public JsonResult createItemInNewTransaction(AuxiliaryItemReq req) {
        return doCreateItem(req);
    }

    private JsonResult doCreateItem(AuxiliaryItemReq req) {
        if (req.getAccountSetsId() == null) {
            return failureWithType("accountSetsId 不能为空", "INVALID_PARAM");
        }
        if (req.getCategoryId() == null) {
            return failureWithType("categoryId 不能为空", "INVALID_PARAM");
        }
        if (StrUtil.isBlank(req.getName())) {
            return failureWithType("name 不能为空", "INVALID_PARAM");
        }

        // 1. 类别存在性 & 类型校验
        AccountingCategory category = accountingCategoryService.getOne(
                new PrimaryKey(req.getCategoryId(), req.getAccountSetsId()));
        if (category == null) {
            return failureWithType("辅助核算类别不存在：" + req.getCategoryId(), "CATEGORY_NOT_FOUND");
        }
        AccountingCategoryType type = category.getCategoryType();
        if (type == null || !SUPPORTED_TYPES.contains(type)) {
            return failureWithType("不支持的类别类型：" + type + "（仅支持 CUSTOMER/VENDOR/DEPARTMENT/PROJECT）",
                    "UNSUPPORTED_CATEGORY_TYPE");
        }

        AbsAuxiliaryDataService<?> service = pickService(type);

        // 2. 查重 by name
        AcDetails existedByName = service.loadByName(req.getAccountSetsId(), req.getCategoryId(),
                req.getName().trim());
        if (existedByName != null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errorType", "DUPLICATE_NAME");
            data.put("existingId", existedByName.getId());
            data.put("existingCode", existedByName.getCode());
            return JsonResult.failure("辅助项名称已存在：" + req.getName()).setData(data);
        }

        // 3. code 校验/生成
        String code = req.getCode() != null ? req.getCode().trim() : null;
        if (StrUtil.isBlank(code)) {
            code = generateNextCode(service, req.getAccountSetsId(), req.getCategoryId());
        } else {
            AcDetails existedByCode = service.loadByCode(req.getAccountSetsId(), req.getCategoryId(), code);
            if (existedByCode != null) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("errorType", "DUPLICATE_CODE");
                data.put("existingId", existedByCode.getId());
                return JsonResult.failure("编码已存在：" + code).setData(data);
            }
        }

        // 4. 实例化对应实体并写入
        try {
            AcDetails entity = buildEntity(type, req, code);
            entity.setAccountSetsId(req.getAccountSetsId());
            entity.setCategoryId(req.getCategoryId());
            entity.setEnable(req.getEnabled() == null ? Boolean.TRUE : req.getEnabled());
            entity.setRemark(req.getRemark());

            AcDetails saved = service.save(entity);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", saved.getId());
            data.put("accountSetsId", saved.getAccountSetsId());
            data.put("categoryId", saved.getCategoryId());
            data.put("categoryType", type.name());
            data.put("code", saved.getCode());
            data.put("name", saved.getName());
            data.put("enabled", saved.getEnable() == null || Boolean.TRUE.equals(saved.getEnable()));
            return JsonResult.successful(data);
        } catch (Exception e) {
            log.warn("openapi 创建辅助项失败: type={}, name={}, msg={}", type, req.getName(), e.getMessage());
            String msg = e.getMessage() != null ? e.getMessage() : "创建失败";
            String errorType = msg.contains("编码") && msg.contains("已重复") ? "DUPLICATE_CODE" : "INTERNAL_ERROR";
            return failureWithType(msg, errorType);
        }
    }

    /**
     * 取下一个 code：用 service.loadMaxCode 得到当前最大 code（数字字符串），+1 转回字符串。
     * 没有任何记录时返回 "1"。
     */
    private String generateNextCode(AbsAuxiliaryDataService<?> service, Long accountSetsId, Long categoryId) {
        String maxCode = service.loadMaxCode(accountSetsId, categoryId);
        if (StrUtil.isBlank(maxCode)) {
            return "1";
        }
        try {
            long next = Long.parseLong(maxCode.trim()) + 1;
            return String.valueOf(next);
        } catch (NumberFormatException e) {
            // 极少数情况下 max code 不是纯数字（用户手动建过非数字 code），用时间戳兜底
            return String.valueOf(System.currentTimeMillis() % 1_000_000_000L);
        }
    }

    /**
     * 按类型选 service。仅支持 4 种。
     */
    private AbsAuxiliaryDataService<?> pickService(AccountingCategoryType type) {
        return switch (type) {
            case CUSTOMER -> customerService;
            case VENDOR -> vendorService;
            case DEPARTMENT -> departmentService;
            case PROJECT -> projectService;
            default -> throw new IllegalStateException("Unsupported category type: " + type);
        };
    }

    /**
     * 按类型实例化对应 entity，并把 extra 里能识别的字段塞进去；未知字段静默忽略。
     */
    private AcDetails buildEntity(AccountingCategoryType type, AuxiliaryItemReq req, String code) {
        Map<String, Object> extra = req.getExtra() != null ? req.getExtra() : Collections.emptyMap();

        switch (type) {
            case CUSTOMER -> {
                Customer c = new Customer();
                c.setName(req.getName().trim());
                c.setCode(code);
                c.setCategory(strOrNull(extra.get("category")));
                c.setCreditCode(strOrNull(extra.get("creditCode")));
                c.setAddress(strOrNull(extra.get("address")));
                c.setContact(strOrNull(extra.get("contact")));
                c.setTelPhone(firstNonBlank(strOrNull(extra.get("telPhone")), strOrNull(extra.get("phone"))));
                return c;
            }
            case VENDOR -> {
                Vendor v = new Vendor();
                v.setName(req.getName().trim());
                v.setCode(code);
                v.setCategory(strOrNull(extra.get("category")));
                v.setCreditCode(strOrNull(extra.get("creditCode")));
                v.setAddress(strOrNull(extra.get("address")));
                v.setContact(strOrNull(extra.get("contact")));
                v.setTelPhone(firstNonBlank(strOrNull(extra.get("telPhone")), strOrNull(extra.get("phone"))));
                return v;
            }
            case DEPARTMENT -> {
                Department d = new Department();
                d.setName(req.getName().trim());
                d.setCode(code);
                d.setLeader(strOrNull(extra.get("leader")));
                d.setTelPhone(firstNonBlank(strOrNull(extra.get("telPhone")), strOrNull(extra.get("phone"))));
                d.setStartDate(parseDate(extra.get("startDate")));
                d.setEndDate(parseDate(extra.get("endDate")));
                return d;
            }
            case PROJECT -> {
                Project p = new Project();
                p.setName(req.getName().trim());
                p.setCode(code);
                Object deptId = extra.get("departmentId");
                if (deptId instanceof Number n) {
                    p.setDepartmentId(n.longValue());
                } else if (deptId instanceof String s && !s.isBlank()) {
                    try {
                        p.setDepartmentId(Long.parseLong(s.trim()));
                    } catch (NumberFormatException ignored) {}
                }
                p.setDepartmentName(strOrNull(extra.get("departmentName")));
                p.setLeader(strOrNull(extra.get("leader")));
                p.setTelPhone(firstNonBlank(strOrNull(extra.get("telPhone")), strOrNull(extra.get("phone"))));
                p.setStartDate(parseDate(extra.get("startDate")));
                p.setAcceptanceDate(parseDate(extra.get("acceptanceDate")));
                return p;
            }
            default -> throw new IllegalStateException("Unsupported category type: " + type);
        }
    }

    private static String strOrNull(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (StrUtil.isNotBlank(v)) return v;
        }
        return null;
    }

    private static LocalDate parseDate(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return null;
        try {
            return LocalDate.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static JsonResult failureWithType(String msg, String errorType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorType", errorType);
        return JsonResult.failure(msg).setData(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 接口 2：辅助核算组合 find-or-create
    // POST /openapi/v1/auxiliary/details/find-or-create
    // ═══════════════════════════════════════════════════════════════

    @PostMapping("/details/find-or-create")
    @Transactional
    public JsonResult findOrCreateDetail(@RequestBody AuxiliaryDetailReq req) {
        if (req.getAccountSetsId() == null) {
            return failureWithType("accountSetsId 不能为空", "INVALID_PARAM");
        }
        if (req.getItems() == null || req.getItems().isEmpty()) {
            return failureWithType("items 不能为空", "INVALID_PARAM");
        }

        // 1. 校验每个 item 存在；用对应 service.loadById 查
        List<AcDetails> resolved = new ArrayList<>();
        for (AuxiliaryItemRef ref : req.getItems()) {
            if (ref.getCategoryId() == null || ref.getItemId() == null) {
                return failureWithType("items[].categoryId/itemId 不能为空", "INVALID_PARAM");
            }
            AccountingCategory category = accountingCategoryService.getOne(
                    new PrimaryKey(ref.getCategoryId(), req.getAccountSetsId()));
            if (category == null) {
                return failureWithType("辅助类别不存在：categoryId=" + ref.getCategoryId(),
                        "CATEGORY_NOT_FOUND");
            }
            AccountingCategoryType type = category.getCategoryType();
            if (type == null || !SUPPORTED_TYPES.contains(type)) {
                return failureWithType("不支持的类别类型：" + type + "（仅支持 CUSTOMER/VENDOR/DEPARTMENT/PROJECT）",
                        "UNSUPPORTED_CATEGORY_TYPE");
            }
            AbsAuxiliaryDataService<?> service = pickService(type);
            Optional<AcDetails> opt = service.loadById(req.getAccountSetsId(), ref.getCategoryId(), ref.getItemId());
            if (opt.isEmpty()) {
                return failureWithType("辅助项不存在：itemId=" + ref.getItemId()
                        + "，categoryId=" + ref.getCategoryId(), "ITEM_NOT_FOUND");
            }
            // 强制把传入的 categoryType 设上（以 category 表的为准），以保证后续 calCol 排序稳定
            AcDetails det = opt.get();
            det.setCategoryType(type);
            resolved.add(det);
        }

        // 2. 构造 calCol（按 categoryId 升序）
        List<AcDetails> sorted = resolved.stream()
                .sorted(Comparator.comparing(AcDetails::getCategoryId))
                .collect(Collectors.toList());
        String calCol = sorted.stream()
                .map(d -> String.valueOf(d.getId()))
                .collect(Collectors.joining("_"));

        // 3. 查 auxiliary_detail
        AuxiliaryDetail existing = bqf.selectFrom(qAuxiliaryDetail)
                .where(qAuxiliaryDetail.calCol.eq(calCol)
                        .and(qAuxiliaryDetail.accountSetsId.eq(req.getAccountSetsId())))
                .fetchFirst();

        boolean created;
        Long detailId;
        if (existing != null) {
            created = false;
            detailId = existing.getId();
        } else {
            // 4. 不存在 → 新建（与 VoucherService.getAuxiliaryDetailId 同样的逻辑）
            // id 由 AbsEntity 上的 CosIdGenerator 自动生成
            AuxiliaryDetail ad = new AuxiliaryDetail();
            ad.setAccountSetsId(req.getAccountSetsId());
            ad.setCalCol(calCol);
            ad.setDetails(sorted.stream().map(s -> {
                AuxiliaryDetail.Detail d = new AuxiliaryDetail.Detail();
                d.setCategoryId(s.getCategoryId());
                d.setCategoryType(s.getCategoryType());
                d.setValue(s.getId());
                return d;
            }).collect(Collectors.toList()));
            AuxiliaryDetail saved = auxiliaryDetailRepository.saveAndFlush(ad);
            detailId = saved.getId();
            created = true;
        }

        // 5. 返回快照
        List<Map<String, Object>> detailsSnap = new ArrayList<>();
        for (AcDetails d : sorted) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.getId());
            m.put("categoryId", d.getCategoryId());
            m.put("categoryType", d.getCategoryType() != null ? d.getCategoryType().name() : null);
            m.put("code", d.getCode());
            m.put("name", d.getName());
            detailsSnap.add(m);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", detailId);
        data.put("calCol", calCol);
        data.put("created", created);
        data.put("details", detailsSnap);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // 请求 DTO
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class AuxiliaryItemReq {
        private Long accountSetsId;
        private Long categoryId;
        private String name;
        private String code;
        private Boolean enabled;
        private String remark;
        private Map<String, Object> extra;
    }

    @Data
    public static class AuxiliaryItemBatchReq {
        private Long accountSetsId;
        private List<AuxiliaryItemReq> items;
    }

    @Data
    public static class AuxiliaryDetailReq {
        private Long accountSetsId;
        private List<AuxiliaryItemRef> items;
    }

    @Data
    public static class AuxiliaryItemRef {
        private Long categoryId;
        private Long itemId;
    }
}
