package cn.gson.financial.controller.openapi;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.bean.FieldValue;
import cn.gson.financial.kernel.model.bean.Value;
import cn.gson.financial.kernel.model.entity.Department;
import cn.gson.financial.kernel.model.entity.Employee;
import cn.gson.financial.kernel.model.entity.QDepartment;
import cn.gson.financial.kernel.model.entity.QEmployee;
import cn.gson.financial.kernel.model.entity.QSalary;
import cn.gson.financial.kernel.model.entity.QSalaryFieldValue;
import cn.gson.financial.kernel.model.entity.Salary;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.SalaryFieldValue;
import cn.gson.financial.kernel.model.entity.SalaryVoucher;
import cn.gson.financial.kernel.model.entity.QSalaryVoucher;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.service.SalaryVoucherTemplateService;
import com.querydsl.core.BooleanBuilder;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/**
 * 开放 API — 工资
 * 路径前缀 /openapi/v1/salary
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/openapi/v1/salary")
@SaIgnore
public class OpenApiSalaryController {

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    // FN-NEW-OPENAPI-SALARY-AUTOGEN: 工资凭证一键生成 — 直接复用 SalaryVoucherTemplateService.autoGenerateVouchers
    private final SalaryVoucherTemplateService salaryVoucherTemplateService;

    private static final QSalary qSalary = QSalary.salary;
    private static final QSalaryFieldValue qSfv = QSalaryFieldValue.salaryFieldValue;
    private static final QEmployee qEmployee = QEmployee.employee;
    private static final QDepartment qDept = QDepartment.department;
    private static final QSalaryVoucher qSalaryVoucher = QSalaryVoucher.salaryVoucher;
    private static final QAccountSets qAccountSets = QAccountSets.accountSets;

    /**
     * §2.6.1 工资表头
     * GET /openapi/v1/salary?accountSetsId=88&period=2026-05
     */
    @GetMapping
    public JsonResult list(@RequestParam Long accountSetsId,
                           @RequestParam(required = false) String period,
                           @RequestParam(required = false) Boolean hasVoucher) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }

        BooleanBuilder where = new BooleanBuilder();
        where.and(qSalary.accountSetsId.eq(accountSetsId));
        if (period != null && !period.isBlank()) {
            try {
                YearMonth ym = YearMonth.parse(period);
                where.and(qSalary.period.eq(ym.atDay(1)));
            } catch (Exception e) {
                return JsonResult.failure("period 格式错误，应为 YYYY-MM：" + period);
            }
        }

        List<Salary> salaries = bqf.selectFrom(qSalary)
                .where(where)
                .orderBy(qSalary.period.desc())
                .fetch();

        // 批量查询是否已生成凭证
        List<Long> salaryIds = new ArrayList<>();
        for (Salary s : salaries) salaryIds.add(s.getId());
        Set<Long> hasVoucherIds = new HashSet<>();
        if (!salaryIds.isEmpty()) {
            List<SalaryVoucher> svs = bqf.selectFrom(qSalaryVoucher)
                    .where(qSalaryVoucher.accountSetsId.eq(accountSetsId)
                            .and(qSalaryVoucher.salaryId.in(salaryIds))
                            .and(qSalaryVoucher.voucherId.isNotNull()))
                    .fetch();
            for (SalaryVoucher sv : svs) hasVoucherIds.add(sv.getSalaryId());
        }

        // hasVoucher 过滤：true=只返回已做账，false=只返回未做账，null=全部返回
        if (hasVoucher != null) {
            final boolean wantVoucher = hasVoucher;
            salaries = salaries.stream()
                    .filter(s -> wantVoucher == hasVoucherIds.contains(s.getId()))
                    .collect(java.util.stream.Collectors.toList());
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Salary s : salaries) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("accountSetsId", s.getAccountSetsId());
            m.put("period", s.getPeriod());
            m.put("year", s.getYear());
            m.put("month", s.getMonth());
            m.put("state", Boolean.TRUE.equals(s.getState()));
            m.put("hasVoucher", hasVoucherIds.contains(s.getId()));
            m.put("fieldValue", flattenFieldValue(s.getFieldValue()));
            result.add(m);
        }
        return JsonResult.successful(result);
    }

    /**
     * §2.6.2 工资明细（按员工，含员工名+部门名）
     * GET /openapi/v1/salary/{salaryId}/detail?accountSetsId=88
     */
    @GetMapping("/{salaryId}/detail")
    public JsonResult detail(@PathVariable Long salaryId,
                             @RequestParam Long accountSetsId) {
        if (accountSetsId == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }

        Salary salary = bqf.selectFrom(qSalary)
                .where(qSalary.accountSetsId.eq(accountSetsId).and(qSalary.id.eq(salaryId)))
                .fetchFirst();
        if (salary == null) {
            return JsonResult.failure("工资单不存在：" + salaryId);
        }

        List<SalaryFieldValue> values = bqf.selectFrom(qSfv)
                .where(qSfv.accountSetsId.eq(accountSetsId).and(qSfv.salaryId.eq(salaryId)))
                .orderBy(qSfv.empId.asc())
                .fetch();

        // 批量加载员工信息
        Set<Long> empIds = new HashSet<>();
        for (SalaryFieldValue v : values) empIds.add(v.getEmpId());

        Map<Long, Employee> empMap = new HashMap<>();
        Map<Long, String> deptNameMap = new HashMap<>();
        if (!empIds.isEmpty()) {
            List<Employee> emps = bqf.selectFrom(qEmployee)
                    .where(qEmployee.accountSetsId.eq(accountSetsId).and(qEmployee.id.in(empIds)))
                    .fetch();
            Set<Long> deptIds = new HashSet<>();
            for (Employee e : emps) {
                empMap.put(e.getId(), e);
                if (e.getDepartmentId() != null) deptIds.add(e.getDepartmentId());
            }
            if (!deptIds.isEmpty()) {
                List<Department> depts = bqf.selectFrom(qDept)
                        .where(qDept.accountSetsId.eq(accountSetsId).and(qDept.id.in(deptIds)))
                        .fetch();
                for (Department d : depts) {
                    deptNameMap.put(d.getId(), d.getName());
                }
            }
        }

        List<Map<String, Object>> fieldValues = new ArrayList<>();
        for (SalaryFieldValue v : values) {
            Employee emp = empMap.get(v.getEmpId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("empId", v.getEmpId());
            row.put("empName", emp != null ? emp.getName() : null);
            row.put("empCode", emp != null ? emp.getCode() : null);
            row.put("deptId", emp != null ? emp.getDepartmentId() : null);
            row.put("deptName", emp != null && emp.getDepartmentId() != null
                    ? deptNameMap.get(emp.getDepartmentId()) : null);
            row.put("fieldValue", flattenFieldValue(v.getFieldValue()));
            fieldValues.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> sa = new LinkedHashMap<>();
        sa.put("id", salary.getId());
        sa.put("period", salary.getPeriod());
        sa.put("year", salary.getYear());
        sa.put("month", salary.getMonth());
        sa.put("state", Boolean.TRUE.equals(salary.getState()));
        data.put("salary", sa);
        data.put("fieldValues", fieldValues);
        return JsonResult.successful(data);
    }

    // ═══════════════════════════════════════════════════════════════
    // POST /openapi/v1/salary/auto-generate-vouchers
    // body: { accountSetsId, year, month }
    //
    // 直接 wrap 业务方法 SalaryVoucherTemplateService.autoGenerateVouchers，
    // 入参组装 LocalDate period（当月 1 号）→ 返回原方法 Map（含 voucherIds 用于 XW 审计）。
    //
    // 模板规则：按 title="计提发放工资" 匹配账套下的工资凭证模板（单条合并模板）。
    // 账套需要在前端建好这条模板；service 内部找不到时返回 status=skipped。
    // ═══════════════════════════════════════════════════════════════
    @PostMapping("/auto-generate-vouchers")
    @Transactional
    public JsonResult autoGenerateVouchers(@RequestBody SalaryAutoGenReq req) {
        if (req == null) {
            return JsonResult.failure("请求体不能为空");
        }
        if (req.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (req.getYear() == null || req.getMonth() == null) {
            return JsonResult.failure("year/month 不能为空");
        }
        if (req.getMonth() < 1 || req.getMonth() > 12) {
            return JsonResult.failure("month 取值范围 1-12：" + req.getMonth());
        }
        // 账套存在性校验（autoGenerateVouchers 内部也会校验，但提早返回更友好）
        if (!accountSetExists(req.getAccountSetsId())) {
            return JsonResult.failure("账套不存在：" + req.getAccountSetsId());
        }

        LocalDate period = LocalDate.of(req.getYear(), req.getMonth(), 1);

        // 模板选择规则：按 title="计提发放工资" 匹配（参见 SalaryVoucherTemplateService.AUTO_GEN_TEMPLATE_TITLE）。
        // 账套需要事先在前端建好这条合并模板；找不到时 service 内部返回 status=skipped。
        Map<String, Object> result = salaryVoucherTemplateService.autoGenerateVouchers(
                req.getAccountSetsId(), period);
        return JsonResult.successful(result);
    }

    @Data
    public static class SalaryAutoGenReq {
        private Long accountSetsId;
        private Integer year;
        private Integer month;
    }

    // ═══════════════════════════════════════════════════════════════
    // FN-SALARY-AUTOGEN-SELF-HEAL（配套）：查缺 + 批量补建 计提发放工资 模板
    // ═══════════════════════════════════════════════════════════════

    /** GET /openapi/v1/salary/templates/missing?accountSetIds=1,2,3 —— 返回缺 title='计提发放工资' 的账套。 */
    @GetMapping("/templates/missing")
    public JsonResult missingTemplates(@RequestParam(required = false) String accountSetIds) {
        List<Long> ids = parseIds(accountSetIds);
        if (ids.isEmpty()) return JsonResult.failure("accountSetIds 不能为空（逗号分隔账套 id）");
        List<Long> missing = salaryVoucherTemplateService.findAccountsMissingAutoGenTemplate(ids);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checked", ids.size());
        data.put("missingCount", missing.size());
        data.put("missing", missing);
        return JsonResult.successful(data);
    }

    /**
     * POST /openapi/v1/salary/templates/batch-init —— 批量补建合并模板。
     * 循环每户<b>独立事务</b>调 {@code ensureAutoGenTemplate}（一户失败不拖垮其它）；返回 created/skipped/failed 三类。
     */
    @PostMapping("/templates/batch-init")
    public JsonResult batchInitTemplates(@RequestBody BatchInitReq req) {
        if (req == null || req.getAccountSetIds() == null || req.getAccountSetIds().isEmpty()) {
            return JsonResult.failure("accountSetIds 不能为空");
        }
        List<Map<String, Object>> created = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(req.getAccountSetIds())) {   // 去重
            if (id == null) continue;
            try {
                // 跨 bean 调用 → 各自 @Transactional 独立事务（一户失败不影响其它）
                Map<String, Object> res = salaryVoucherTemplateService.ensureAutoGenTemplate(id);
                if (Boolean.TRUE.equals(res.get("created"))) {
                    created.add(res);
                } else if ("already_exists".equals(res.get("reason"))) {
                    skipped.add(res);
                } else {
                    failed.add(res);
                }
            } catch (Exception e) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("accountSetsId", id);
                f.put("created", false);
                f.put("reason", "exception:" + e.getMessage());
                failed.add(f);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", created.size() + skipped.size() + failed.size());
        data.put("createdCount", created.size());
        data.put("skippedCount", skipped.size());
        data.put("failedCount", failed.size());
        data.put("created", created);     // 含 templateId + autoCreatedSubjects + unboundFields（待复核）
        data.put("skipped", skipped);
        data.put("failed", failed);
        return JsonResult.successful(data);
    }

    /** 逗号分隔 id 串 → List<Long>（忽略空白/非法项）。 */
    private static List<Long> parseIds(String csv) {
        List<Long> ids = new ArrayList<>();
        if (csv == null || csv.isBlank()) return ids;
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            try { ids.add(Long.valueOf(t)); } catch (NumberFormatException ignore) { }
        }
        return ids;
    }

    @Data
    public static class BatchInitReq {
        private List<Long> accountSetIds;
    }

    /** package-private test hook：单测覆写避开 BlazeJPAQuery 难 mock 的问题 */
    boolean accountSetExists(Long accountSetsId) {
        return bqf.selectFrom(qAccountSets).select(qAccountSets.id)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst() != null;
    }

    /**
     * 把 FieldValue（HashMap<String, Value>）扁平成 {名称: 值}。
     */
    private Map<String, Object> flattenFieldValue(FieldValue fv) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (fv == null) return m;
        for (Value v : fv.values()) {
            if (v == null || v.getName() == null) continue;
            m.put(v.getName(), v.getValue());
        }
        return m;
    }
}
