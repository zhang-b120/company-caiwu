package cn.gson.financial.controller;

import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Employee;
import cn.gson.financial.kernel.model.entity.EmployeeInfo;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.enums.Gender;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.service.EmployeeService;
import cn.dev33.satoken.annotation.SaIgnore;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import jakarta.persistence.EntityManager;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 开放 API — 人员信息管理
 * <p>
 * 供外部系统调用，批量导入/查询人员信息。
 * 人员数据写入 Employee（辅助核算-员工）表，同时写入 EmployeeInfo（个税人员）表。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/open-api/employee")
@SaIgnore
public class OpenApiEmployeeController {

    private final EmployeeService employeeService;
    private final EntityManager entityManager;

    @Resource
    @Lazy
    private BlazeJPAQueryFactory bqf;

    private static final QAccountSets qAccountSets = QAccountSets.accountSets;

    // ═══════════════════════════════════════════════════════════════
    // 批量导入人员
    // ═══════════════════════════════════════════════════════════════

    /**
     * 批量导入人员信息
     * <p>
     * POST /open-api/employee/batch-import
     * <pre>
     * {
     *   "accountSetsId": 123456,        // 必填：账套ID
     *   "employees": [                  // 必填：人员列表
     *     {
     *       "name": "张三",              // 必填：姓名
     *       "idNumber": "370102199001011234", // 必填：证件号码（身份证）
     *       "phone": "13800138000",      // 选填：手机号
     *       "gender": "男",              // 选填：性别（男/女）
     *       "birthDate": "1990-01-01",   // 选填：出生日期
     *       "employmentDate": "2020-03-15", // 选填：入职日期
     *       "resignationDate": null,     // 选填：离职日期
     *       "status": "正常",            // 选填：人员状态（正常/非正常），默认"正常"
     *       "idType": "居民身份证",       // 选填：证件类型，默认"居民身份证"
     *       "nationality": "中国",       // 选填：国籍
     *       "email": "",                 // 选填：邮箱
     *       "position": "",              // 选填：职务
     *       "education": "",             // 选填：学历
     *       "bankName": "",              // 选填：开户银行
     *       "bankAccount": ""            // 选填：银行账号
     *     }
     *   ]
     * }
     * </pre>
     *
     * @return 导入结果：成功数、跳过数（已存在）、失败数及失败明细
     */
    @PostMapping("/batch-import")
    @Transactional
    public JsonResult batchImport(@RequestBody BatchImportRequest request) {
        if (request.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (request.getEmployees() == null || request.getEmployees().isEmpty()) {
            return JsonResult.failure("employees 不能为空");
        }

        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(request.getAccountSetsId()))
                .fetchFirst();
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + request.getAccountSetsId());
        }

        Long accountSetId = accountSets.getId();
        int successCount = 0;
        int skipCount = 0;
        int failCount = 0;
        List<Map<String, String>> failures = new ArrayList<>();

        for (EmployeeImportDto dto : request.getEmployees()) {
            try {
                if (StrUtil.isBlank(dto.getName())) {
                    failCount++;
                    failures.add(Map.of("name", "", "reason", "姓名不能为空"));
                    continue;
                }
                if (StrUtil.isBlank(dto.getIdNumber())) {
                    failCount++;
                    failures.add(Map.of("name", dto.getName(), "reason", "证件号码不能为空"));
                    continue;
                }

                // 检查是否已存在（按身份证号判重）
                Employee existing = employeeService.findByIdCardAndName(
                        accountSetId, dto.getIdNumber(), null, null);
                if (existing != null) {
                    skipCount++;
                    log.info("人员已存在，跳过: name={}, idNumber={}", dto.getName(), dto.getIdNumber());
                    continue;
                }

                // 创建 Employee（辅助核算-员工）
                Employee employee = new Employee();
                employee.setAccountSetsId(accountSetId);
                employee.setCategoryId((long) AccountingCategoryType.EMPLOYEE.ordinal());
                employee.setCategoryType(AccountingCategoryType.EMPLOYEE);
                employee.setCode(employeeService.getEmployeeCode(accountSetId));
                employee.setName(dto.getName());
                employee.setIdCard(dto.getIdNumber());
                employee.setTelPhone(dto.getPhone());
                employee.setEmail(dto.getEmail());
                employee.setPost(dto.getPosition());
                employee.setEdu(dto.getEducation());
                employee.setBankName(dto.getBankName());
                employee.setBankAccount(dto.getBankAccount());
                employee.setPurchase(false);

                if (StrUtil.isNotBlank(dto.getGender())) {
                    employee.setGender("女".equals(dto.getGender()) ? Gender.女 : Gender.男);
                }
                if (StrUtil.isNotBlank(dto.getBirthDate())) {
                    employee.setBirthDate(parseDate(dto.getBirthDate()));
                }
                if (StrUtil.isNotBlank(dto.getEmploymentDate())) {
                    employee.setOnboardingDate(parseDate(dto.getEmploymentDate()));
                }
                if (StrUtil.isNotBlank(dto.getResignationDate())) {
                    employee.setDimissionDate(parseDate(dto.getResignationDate()));
                }

                String status = StrUtil.isBlank(dto.getStatus()) ? "正常" : dto.getStatus();
                if ("正常".equals(status)) {
                    employee.setStatus("在职");
                    employee.setEnable(true);
                    employee.setEmployeeStatus("正常");
                } else {
                    employee.setStatus("离职");
                    employee.setEnable(false);
                    employee.setEmployeeStatus(status);
                }

                employeeService.save(employee);

                // 同步写入 EmployeeInfo（个税人员信息）
                saveEmployeeInfo(dto, accountSetId, accountSets.getCompanyName());

                successCount++;
                log.info("人员导入成功: name={}, idNumber={}", dto.getName(), dto.getIdNumber());

            } catch (Exception e) {
                failCount++;
                failures.add(Map.of("name", dto.getName() != null ? dto.getName() : "",
                        "reason", e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                log.warn("人员导入失败: name={}, error={}", dto.getName(), e.getMessage());
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", request.getEmployees().size());
        result.put("success", successCount);
        result.put("skipped", skipCount);
        result.put("failed", failCount);
        if (!failures.isEmpty()) {
            result.put("failures", failures);
        }

        log.info("批量导入人员完成: accountSetId={}, 总数={}, 成功={}, 跳过={}, 失败={}",
                accountSetId, request.getEmployees().size(), successCount, skipCount, failCount);
        return JsonResult.successful(result);
    }

    // ═══════════════════════════════════════════════════════════════
    // 查询人员列表
    // ═══════════════════════════════════════════════════════════════

    /**
     * 查询账套下的人员列表
     * <p>
     * GET /open-api/employee/list?accountSetsId=123456
     */
    @GetMapping("/list")
    public JsonResult list(@RequestParam Long accountSetsId,
                           @RequestParam(required = false) String status) {
        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst();
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + accountSetsId);
        }

        var qEmployee = cn.gson.financial.kernel.model.entity.QEmployee.employee;
        var query = bqf.selectFrom(qEmployee)
                .where(qEmployee.accountSetsId.eq(accountSetsId));

        if (StrUtil.isNotBlank(status)) {
            if ("正常".equals(status) || "在职".equals(status)) {
                query.where(qEmployee.enable.isTrue());
            } else {
                query.where(qEmployee.enable.isFalse());
            }
        }

        List<Employee> employees = query.orderBy(qEmployee.code.asc()).fetch();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Employee emp : employees) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", emp.getId());
            map.put("code", emp.getCode());
            map.put("name", emp.getName());
            map.put("idCard", emp.getIdCard());
            map.put("phone", emp.getTelPhone());
            map.put("gender", emp.getGender() != null ? emp.getGender().name() : null);
            map.put("birthDate", emp.getBirthDate());
            map.put("employmentDate", emp.getOnboardingDate());
            map.put("resignationDate", emp.getDimissionDate());
            map.put("status", emp.getStatus());
            map.put("bankName", emp.getBankName());
            map.put("bankAccount", emp.getBankAccount());
            result.add(map);
        }

        return JsonResult.successful(result);
    }

    // ═══════════════════════════════════════════════════════════════
    // 删除人员
    // ═══════════════════════════════════════════════════════════════

    /**
     * 删除人员（按身份证号）
     * <p>
     * DELETE /open-api/employee/delete?accountSetsId=123456&idNumber=370102199001011234
     */
    @DeleteMapping("/delete")
    @Transactional
    public JsonResult delete(@RequestParam Long accountSetsId, @RequestParam String idNumber) {
        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst();
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + accountSetsId);
        }

        // 删除 Employee 表
        var qEmployee = cn.gson.financial.kernel.model.entity.QEmployee.employee;
        Employee employee = bqf.selectFrom(qEmployee)
                .where(qEmployee.accountSetsId.eq(accountSetsId)
                        .and(qEmployee.idCard.eq(idNumber)))
                .fetchFirst();

        if (employee == null) {
            return JsonResult.failure("人员不存在：" + idNumber);
        }

        employeeService.delete(new cn.gson.financial.kernel.model.hibernate.PrimaryKey(
                employee.getId(), accountSetsId));

        // 同时删除 EmployeeInfo 表
        entityManager.createQuery(
                "DELETE FROM EmployeeInfo e WHERE e.accountSetsId = :asId AND e.idNumber = :idNum")
                .setParameter("asId", accountSetsId)
                .setParameter("idNum", idNumber)
                .executeUpdate();

        log.info("人员删除成功: accountSetId={}, name={}, idNumber={}", accountSetsId, employee.getName(), idNumber);
        return JsonResult.successful("删除成功：" + employee.getName());
    }

    /**
     * 批量删除人员（按身份证号列表）
     * <p>
     * POST /open-api/employee/batch-delete
     * <pre>
     * {
     *   "accountSetsId": 123456,
     *   "idNumbers": ["370102199001011234", "370102199002022345"]
     * }
     * </pre>
     */
    @PostMapping("/batch-delete")
    @Transactional
    public JsonResult batchDelete(@RequestBody BatchDeleteRequest request) {
        if (request.getAccountSetsId() == null) {
            return JsonResult.failure("accountSetsId 不能为空");
        }
        if (request.getIdNumbers() == null || request.getIdNumbers().isEmpty()) {
            return JsonResult.failure("idNumbers 不能为空");
        }

        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(request.getAccountSetsId()))
                .fetchFirst();
        if (accountSets == null) {
            return JsonResult.failure("账套不存在：" + request.getAccountSetsId());
        }

        int deleted = 0;
        for (String idNumber : request.getIdNumbers()) {
            var qEmployee = cn.gson.financial.kernel.model.entity.QEmployee.employee;
            Employee employee = bqf.selectFrom(qEmployee)
                    .where(qEmployee.accountSetsId.eq(request.getAccountSetsId())
                            .and(qEmployee.idCard.eq(idNumber)))
                    .fetchFirst();
            if (employee != null) {
                employeeService.delete(new cn.gson.financial.kernel.model.hibernate.PrimaryKey(
                        employee.getId(), request.getAccountSetsId()));
                deleted++;
            }
        }

        // 批量删除 EmployeeInfo
        entityManager.createQuery(
                "DELETE FROM EmployeeInfo e WHERE e.accountSetsId = :asId AND e.idNumber IN :idNums")
                .setParameter("asId", request.getAccountSetsId())
                .setParameter("idNums", request.getIdNumbers())
                .executeUpdate();

        log.info("批量删除人员: accountSetId={}, 请求={}, 实际删除={}",
                request.getAccountSetsId(), request.getIdNumbers().size(), deleted);
        return JsonResult.successful(Map.of("requested", request.getIdNumbers().size(), "deleted", deleted));
    }

    // ═══════════════════════════════════════════════════════════════
    // 私有方法
    // ═══════════════════════════════════════════════════════════════

    private void saveEmployeeInfo(EmployeeImportDto dto, Long accountSetId, String companyName) {
        EmployeeInfo info = new EmployeeInfo();
        info.setAccountSetsId(accountSetId);
        info.setCompanyName(companyName);
        info.setName(dto.getName());
        info.setIdNumber(dto.getIdNumber());
        info.setIdType(StrUtil.isBlank(dto.getIdType()) ? "居民身份证" : dto.getIdType());
        info.setGender(dto.getGender());
        info.setPhone(dto.getPhone());
        info.setEmail(dto.getEmail());
        info.setPosition(dto.getPosition());
        info.setEducation(dto.getEducation());
        info.setBankName(dto.getBankName());
        info.setBankAccount(dto.getBankAccount());
        info.setNationality(StrUtil.isBlank(dto.getNationality()) ? "中国" : dto.getNationality());
        info.setEmployeeStatus("正常".equals(dto.getStatus()) || StrUtil.isBlank(dto.getStatus()) ? "正常" : dto.getStatus());
        info.setUpdateTime(new Date());

        if (StrUtil.isNotBlank(dto.getBirthDate())) {
            try { info.setBirthDate(java.sql.Date.valueOf(parseDate(dto.getBirthDate()))); } catch (Exception ignored) {}
        }
        if (StrUtil.isNotBlank(dto.getEmploymentDate())) {
            try { info.setEmploymentDate(java.sql.Date.valueOf(parseDate(dto.getEmploymentDate()))); } catch (Exception ignored) {}
        }
        if (StrUtil.isNotBlank(dto.getResignationDate())) {
            try { info.setResignationDate(java.sql.Date.valueOf(parseDate(dto.getResignationDate()))); } catch (Exception ignored) {}
        }

        entityManager.persist(info);
    }

    private LocalDate parseDate(String dateStr) {
        if (StrUtil.isBlank(dateStr)) return null;
        dateStr = dateStr.trim();
        List<DateTimeFormatter> formats = List.of(
                DateTimeFormatter.ofPattern("yyyy-MM-dd"),
                DateTimeFormatter.ofPattern("yyyy/MM/dd"),
                DateTimeFormatter.ofPattern("yyyyMMdd")
        );
        for (DateTimeFormatter fmt : formats) {
            try { return LocalDate.parse(dateStr, fmt); } catch (Exception ignored) {}
        }
        throw new IllegalArgumentException("无法解析日期: " + dateStr);
    }

    // ═══════════════════════════════════════════════════════════════
    // 请求 DTO
    // ═══════════════════════════════════════════════════════════════

    @Data
    public static class BatchImportRequest {
        private Long accountSetsId;
        private List<EmployeeImportDto> employees;
    }

    @Data
    public static class BatchDeleteRequest {
        private Long accountSetsId;
        private List<String> idNumbers;  // 身份证号列表
    }

    @Data
    public static class EmployeeImportDto {
        private String name;            // 必填：姓名
        private String idNumber;        // 必填：证件号码
        private String idType;          // 选填：证件类型，默认"居民身份证"
        private String phone;           // 选填：手机号
        private String gender;          // 选填：性别（男/女）
        private String birthDate;       // 选填：出生日期 yyyy-MM-dd
        private String employmentDate;  // 选填：入职日期 yyyy-MM-dd
        private String resignationDate; // 选填：离职日期 yyyy-MM-dd
        private String status;          // 选填：人员状态（正常/非正常），默认"正常"
        private String nationality;     // 选填：国籍，默认"中国"
        private String email;           // 选填：邮箱
        private String position;        // 选填：职务
        private String education;       // 选填：学历
        private String bankName;        // 选填：开户银行
        private String bankAccount;     // 选填：银行账号
    }
}
