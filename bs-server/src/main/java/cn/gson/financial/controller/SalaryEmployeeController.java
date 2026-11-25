package cn.gson.financial.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.controller.PageResults;
import cn.gson.financial.kernel.model.dto.SalaryEmployeeDto;
import cn.gson.financial.kernel.model.dto.SalarySocialEmployeeDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.common.IdCardUtils;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.vo.*;
import cn.gson.financial.kernel.service.DepartmentService;
import cn.gson.financial.kernel.service.EmployeeService;
import cn.gson.financial.kernel.service.SalaryEmployeeService;
import cn.gson.financial.kernel.service.SalarySocialService;
import cn.gson.financial.kernel.tool.JxlsUtils;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : PACKAGE_NAME</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年12月25日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/salary/employee")
public class SalaryEmployeeController {

    private final SalaryEmployeeService employeeDetailService;

    private final SalarySocialService salarySocialService;

    private final DepartmentService departmentService;

    private final EmployeeService employeeService;

    @GetMapping
    public JsonResult list(@SessionAccountSet AccountSets accountSets, Page page, SalaryEmployeeService.QueryVo queryVo) {
        PageResults<SalaryEmployeeDto> results = employeeDetailService.employeeList(accountSets.getId(), page, queryVo);

        return JsonResult.successful(results);
    }

    /**
     * 员工信息保存
     *
     * @param accountSetId
     * @param employeeForm
     * @return
     */
    @PostMapping
    public JsonResult employeeSave(@SessionAccountSet AccountSets accountSets, @RequestBody EmployeeFormVo employeeForm) {
        employeeForm.getEmployee().setAccountSetsId(accountSets.getId());
        employeeForm.getEmployee().setCategoryId((long) AccountingCategoryType.EMPLOYEE.ordinal());
        employeeForm.getEmployee().setCategoryType(AccountingCategoryType.EMPLOYEE);
        AcDetails emp = employeeService.save(employeeForm.getEmployee());
        if (employeeForm.getEmployee().getPurchase()) {
            //关联社保信息
            BatchSocialVo socialVo = new BatchSocialVo();
            socialVo.setRecords(CollUtil.newArrayList(emp.getId()));
            socialVo.setSocials(employeeForm.getSocials());
            employeeDetailService.updateSocial(accountSets.getId(), socialVo);
        }
        return JsonResult.successful();
    }

    /**
     * 批量设置计提科目
     *
     * @param accountSetId
     * @return
     */
    @PutMapping("provision")
    public JsonResult provision(@SessionAccountSet AccountSets accountSets, @RequestBody BatchProvisionVo provisionVo) {
        employeeDetailService.updateProvision(accountSets.getId(), provisionVo);
        return JsonResult.successful();
    }
    @PostMapping("/deleteEmployee")
    public JsonResult deleteEmployee(@RequestBody BatchDeleteEmployeeVo employeeVo){
        employeeService.deleteEmployee(employeeVo.getRecords());
        return JsonResult.successful();
    }
    /**
     * 批量设置社保信息
     *
     * @param accountSetId
     * @return
     */
    @PutMapping("social")
    public JsonResult updateSocial(@SessionAccountSet AccountSets accountSets, @RequestBody BatchSocialVo socialVo) {
        employeeDetailService.updateSocial(accountSets.getId(), socialVo);
        return JsonResult.successful();
    }

    /**
     * 批量设置员工状态
     *
     * @param accountSetId
     * @return
     */
    @PutMapping("status")
    public JsonResult updateStatus(@SessionAccountSet AccountSets accountSets, @RequestBody BatchStatusVo statusVo) {
        employeeDetailService.updateStatus(accountSets.getId(), statusVo);
        return JsonResult.successful();
    }

    /**
     * 加载员工社保信息
     *
     * @param accountSetId
     * @return
     */
    @GetMapping("social/{empId}")
    public JsonResult social(@SessionAccountSet AccountSets accountSets, @PathVariable Long empId) {
        return JsonResult.successful(employeeDetailService.loadSocial(accountSets.getId(), empId));
    }

    /**
     * 删除单个员工社保项目
     *
     * @param accountSetId
     * @param empId
     * @param socialEmployeeId
     * @return
     */
    @DeleteMapping("social/{empId}/{socialEmployeeId}")
    public JsonResult deleteSocial(@SessionAccountSet AccountSets accountSets,
                                    @PathVariable Long empId,
                                    @PathVariable Long socialEmployeeId) {
        employeeDetailService.deleteSocialEmployee(accountSets.getId(), empId, socialEmployeeId);
        return JsonResult.successful();
    }

    /**
     * 导入模板
     *
     * @param accountSetId
     * @return
     */
    @GetMapping("template")
    public void template(@SessionAccountSet AccountSets accountSets, HttpServletResponse response) throws Exception {
        Long accountSetId = accountSets.getId();
        Dict params = Dict.create();
        List<? extends AcDetails> departments = departmentService.loadData(accountSetId);
        List<SalarySocial> socials = salarySocialService.list(accountSetId);
        List<String> headers = new ArrayList<>();
        for (SalarySocial social : socials) {
            headers.add(social.getName() + "(缴纳基数)");
            headers.add(social.getName() + "(个人)");
            headers.add(social.getName() + "(企业)");
        }
        params.set("headers", headers);
        params.set("departments", departments);
        params.set("dataList", new ArrayList<>());
        response.setContentType(ExcelUtil.XLSX_CONTENT_TYPE);
        response.setHeader("Content-disposition", "attachment; filename=" + new String("薪资职员导入模板.xlsx".getBytes(StandardCharsets.UTF_8), "ISO8859-1"));
        JxlsUtils.exportExcel("template/职员导入模板.xlsx", response.getOutputStream(), params);
    }

    /**
     * 查询不在列表的员工
     *
     * @param idList
     * @return
     */
    @PostMapping("notin/id")
    public JsonResult listNotInIdList(@SessionAccountSet AccountSets accountSets, @RequestBody List<Long> idList) {
        return JsonResult.successful(employeeDetailService.listNotInIdList(accountSets.getId(), idList));
    }

    /**
     * 查询不在列表且在当前账期没有工资记录的员工
     *
     * @param accountSetId 账套ID
     * @param idList 已存在的员工ID列表
     * @param period 账期（格式：YYYY-MM）
     * @return
     */
    @PostMapping("notin/id/period")
    public JsonResult listNotInIdListAndPeriod(@SessionAccountSet AccountSets accountSets,
                                                @RequestParam(required = false) String period,
                                                @RequestBody List<Long> idList) {
        Long accountSetId = accountSets.getId();
        java.time.LocalDate periodDate = null;
        if (period != null && !period.isEmpty()) {
            try {
                periodDate = java.time.LocalDate.parse(period + "-01", java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            } catch (Exception e) {
                // 如果解析失败，使用null，将使用原有逻辑
            }
        }
        return JsonResult.successful(employeeDetailService.listNotInIdListAndPeriod(accountSetId, idList, periodDate));
    }

    /**
     * 员工导出
     *
     * @param accountSetId
     * @return
     */
    @GetMapping("export")
    public void export(@SessionAccountSet AccountSets accountSets, HttpServletResponse response, Page page) throws Exception {
        Long accountSetId = accountSets.getId();
        page.setPageSize(100000);
        page.setPage(1);
        PageResults<SalaryEmployeeDto> results = employeeDetailService.employeeList(accountSetId, page, new SalaryEmployeeService.QueryVo());

        List<Map<String, Object>> list = results.getResults().stream().map(BeanUtil::beanToMap).toList();
        for (Map<String, Object> item : list) {
            List<SalarySocialEmployeeDto> socialEmployees = employeeDetailService.loadSocial(accountSetId, Long.parseLong(item.get("id").toString()));
            socialEmployees.forEach(sse -> {
                item.put(sse.getName() + "(缴纳基数)", sse.getDepositBase());
                item.put(sse.getName() + "(个人比例)", sse.getPersonRatio());
                item.put(sse.getName() + "(个人金额)", sse.getPersonNum());
                item.put(sse.getName() + "(企业比例)", sse.getCompanyRatio());
                item.put(sse.getName() + "(企业金额)", sse.getCompanyNum());
            });
        }
        Dict params = Dict.create();
        List<? extends AcDetails> departments = departmentService.loadData(accountSetId);
        List<SalarySocial> socials = salarySocialService.list(accountSetId);
        List<String> headers = new ArrayList<>();
        for (SalarySocial social : socials) {
            headers.add(social.getName() + "(缴纳基数)");
            headers.add(social.getName() + "(个人比例)");
            headers.add(social.getName() + "(个人金额)");
            headers.add(social.getName() + "(企业比例)");
            headers.add(social.getName() + "(企业金额)");
        }
        params.set("headers", headers);
        params.set("departments", departments);
        params.set("dataList", list);
        response.setContentType(ExcelUtil.XLSX_CONTENT_TYPE);
        response.setHeader("Content-disposition", "attachment; filename=" + new String((accountSets.getCompanyName() + "-职员列表.xlsx").getBytes(StandardCharsets.UTF_8), "ISO8859-1"));
        JxlsUtils.exportExcel("template/职员导入模板.xlsx", response.getOutputStream(), params);
    }

    @PostMapping("import")
    public JsonResult importEmp(@RequestParam("file") MultipartFile file, @SessionAccountSet AccountSets accountSets) {
        Long accountSetsId = accountSets.getId();
        try {
            ExcelReader reader = ExcelUtil.getReader(file.getInputStream());
            List<SalaryEmployeeDto> salaryEmployeeDtos = reader.readAll(SalaryEmployeeDto.class);
            List<Map<String, Object>> rows = reader.readAll();
            List<String> errors = new ArrayList<>();
            Map<String, SalaryEmpImportVo> importData = new HashMap<>();
            Map<String, AcDetails> depMap = new HashMap<>();

            // 用于检查导入数据内部的重复（身份证号+姓名）
            Map<String, Integer> importDuplicateMap = new HashMap<>();
            int duplicateCount = 0;

            Long categoryId = Long.valueOf(AccountingCategoryType.EMPLOYEE.ordinal() + "");

            // 查询系统中已有的员工（用于检查重复）
            // 使用 EmployeeService 的 list 方法查询所有员工（分页查询，pageSize 设置一个很大的值）
            Page page = new Page();
            page.setPageSize(10000); // 设置一个足够大的值
            PageResults<cn.gson.financial.kernel.model.dto.EmployeeDto> employeePageResults = employeeService.list(page, null, accountSetsId);
            // getResults() 返回 Collection，需要转换为 List
            List<cn.gson.financial.kernel.model.dto.EmployeeDto> existingEmployees = new ArrayList<>(employeePageResults.getResults());
            Map<String, Employee> existingEmployeeMap = new HashMap<>();
            for (cn.gson.financial.kernel.model.dto.EmployeeDto empDto : existingEmployees) {
                if (StrUtil.isNotEmpty(empDto.getIdCard()) && StrUtil.isNotEmpty(empDto.getName())) {
                    // 去除身份证号和姓名的前后空格
                    String trimmedIdCard = empDto.getIdCard().trim();
                    String trimmedName = empDto.getName().trim();
                    String key = trimmedIdCard + "|" + trimmedName;
                    // 从 DTO 创建 Employee 对象用于检查
                    Employee emp = new Employee();
                    emp.setId(empDto.getId());
                    emp.setIdCard(trimmedIdCard);
                    emp.setName(trimmedName);
                    existingEmployeeMap.put(key, emp);
                }
            }

            // 获取当前最大编码，用于自动生成编码
            // 使用 getEmployeeCode 获取下一个编码，然后解析它
            String nextCodeStr = employeeService.getEmployeeCode(accountSetsId);
            int nextCodeNum;
            try {
                nextCodeNum = Integer.parseInt(nextCodeStr);
            } catch (NumberFormatException e) {
                // 如果解析失败，从1开始
                nextCodeNum = 1;
            }

            for (int i = 0; i < salaryEmployeeDtos.size(); i++) {
                SalaryEmployeeDto dto = salaryEmployeeDtos.get(i);
                int rowNum = i + 2; // Excel行号（从2开始，第1行是表头）

                // 根据身份证号自动填充出生日期和性别（未填写时）
                if (StrUtil.isNotEmpty(dto.getIdCard())) {
                    if (dto.getGender() == null) {
                        dto.setGender(IdCardUtils.parseGender(dto.getIdCard()));
                    }
                    if (dto.getBirthDate() == null) {
                        dto.setBirthDate(IdCardUtils.parseBirthDate(dto.getIdCard()));
                    }
                }

                // 验证必填字段
                if (StrUtil.isEmpty(dto.getIdCard())) {
                    errors.add("第" + rowNum + "行数据，身份证号不能为空～");
                    continue;
                }
                if (StrUtil.isEmpty(dto.getName())) {
                    errors.add("第" + rowNum + "行数据，名称不能为空～");
                    continue;
                }
                if (dto.getGender() == null) {
                    errors.add("第" + rowNum + "行数据，性别不能为空（请填写性别或填写正确的18位/15位身份证号以自动识别）～");
                    continue;
                }
                if (StrUtil.isEmpty(dto.getTelPhone())) {
                    errors.add("第" + rowNum + "行数据，手机不能为空～");
                    continue;
                }
                if (dto.getBirthDate() == null) {
                    errors.add("第" + rowNum + "行数据，出生日期不能为空（请填写出生日期或填写正确的18位/15位身份证号以自动识别）～");
                    continue;
                }
                if (dto.getOnboardingDate() == null) {
                    errors.add("第" + rowNum + "行数据，入职日期不能为空～");
                    continue;
                }

                // 去除身份证号和姓名的前后空格
                String trimmedIdCard = dto.getIdCard().trim();
                String trimmedName = dto.getName().trim();

                // 检查是否与系统已有员工重复（身份证号+姓名）
                String duplicateKey = trimmedIdCard + "|" + trimmedName;
                if (existingEmployeeMap.containsKey(duplicateKey)) {
                    errors.add("第" + rowNum + "行数据，身份证号 [" + trimmedIdCard + "] 和姓名 [" + trimmedName + "] 的员工已存在系统中～");
                    duplicateCount++;
                    continue;
                }

                // 检查导入数据内部是否重复
                if (importDuplicateMap.containsKey(duplicateKey)) {
                    importDuplicateMap.put(duplicateKey, importDuplicateMap.get(duplicateKey) + 1);
                    errors.add("第" + rowNum + "行数据，身份证号 [" + trimmedIdCard + "] 和姓名 [" + trimmedName + "] 在导入文件中重复～");
                    duplicateCount++;
                    continue;
                } else {
                    importDuplicateMap.put(duplicateKey, 1);
                }

                // 处理部门
                AcDetails department = null;
                if (StrUtil.isNotEmpty(dto.getDepartmentName())) {
                    department = depMap.get(dto.getDepartmentName());
                    if (department == null) {
                        department = departmentService.loadByName(accountSetsId, Long.valueOf(AccountingCategoryType.DEPARTMENT.ordinal() + ""), dto.getDepartmentName());
                        if (department != null) {
                            depMap.put(dto.getDepartmentName(), department);
                        }
                    }
                }

                if (department == null && StrUtil.isNotEmpty(dto.getDepartmentName())) {
                    errors.add("第" + rowNum + "行数据，部门名称 [" + dto.getDepartmentName() + "] 错误，请参考部门辅助核算～");
                    continue;
                }

                // 创建员工对象
                Employee employee = new Employee();
                BeanUtils.copyProperties(dto, employee);
                employee.setAccountSetsId(accountSetsId);

                // 确保身份证号和姓名已去除前后空格（在保存到数据库前）
                employee.setIdCard(trimmedIdCard);
                employee.setName(trimmedName);

                // 自动生成编码（根据已有员工数）
                // 保持编码格式与 getEmployeeCode 一致（3位数字，前导零）
                String generatedCode = String.format("%03d", nextCodeNum++);
                employee.setCode(generatedCode);

                if (department != null) {
                    employee.setDepartmentId(department.getId());
                }
                employee.setCategoryType(AccountingCategoryType.EMPLOYEE);
                employee.setCategoryId(categoryId);
                employee.setCategoryName(AccountingCategoryType.EMPLOYEE.name());
                employee.setEnable(true);
                employee.setPurchase(false);
                importData.put(employee.getCode(), new SalaryEmpImportVo(employee));
            }

            // 处理社保信息
            List<SalarySocial> socials = salarySocialService.list(accountSetsId);
            for (int i = 0; i < rows.size(); i++) {
                JSONObject row = new JSONObject(rows.get(i));
                String code = StrUtil.removePrefix(row.getStr("编码"), "`");
                if (importData.containsKey(code)) {
                    List<SalarySocialEmployee> socialEmployees = new ArrayList<>();
                    for (SalarySocial social : socials) {
                        SalarySocialEmployee socialEmployee = new SalarySocialEmployee();
                        socialEmployee.setSocialId(social.getId());
                        socialEmployee.setPersonRatio(row.getBigDecimal(social.getName() + "(个人比例)"));
                        socialEmployee.setPersonNum(row.getBigDecimal(social.getName() + "(个人金额)"));
                        socialEmployee.setCompanyRatio(row.getBigDecimal(social.getName() + "(企业比例)"));
                        socialEmployee.setCompanyNum(row.getBigDecimal(social.getName() + "(企业金额)"));
                        socialEmployee.setDepositBase(row.getBigDecimal(social.getName() + "(缴纳基数)"));
                        socialEmployee.setAccountSetsId(accountSetsId);
                        if (!socialEmployee.isEmpty()) {
                            socialEmployees.add(socialEmployee);
                        }
                    }
                    importData.get(code).setSocialEmployees(socialEmployees);
                }
            }

            // 执行导入
            int successCount = 0;
            if (CollUtil.isNotEmpty(importData)) {
                employeeDetailService.importData(importData);
                successCount = importData.size();
            }

            // 构建返回结果
            Map<String, Object> result = new HashMap<>();
            result.put("errors", errors);
            result.put("successCount", successCount);
            result.put("duplicateCount", duplicateCount);
            result.put("totalCount", salaryEmployeeDtos.size());

            return JsonResult.successful(result);
        } catch (IOException e) {
            log.error("薪资职员导入失败~", e);
            return JsonResult.failure(e.getMessage());
        }
    }
}
