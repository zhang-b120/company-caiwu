package cn.gson.financial.ua.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.gson.financial.kernel.common.CopierCopyUtils;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.controller.Page;
import cn.gson.financial.kernel.model.dto.SalaryEmployeeDto;
import cn.gson.financial.kernel.model.dto.SalarySocialEmployeeDto;
import cn.gson.financial.kernel.model.entity.AcDetails;
import cn.gson.financial.kernel.model.entity.Employee;
import cn.gson.financial.kernel.model.entity.SalarySocial;
import cn.gson.financial.kernel.model.entity.SalarySocialEmployee;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.individualTax.service.IndividualTaxService;
import cn.gson.financial.kernel.model.vo.BatchSocialVo;
import cn.gson.financial.kernel.model.vo.SalaryEmpImportVo;
import cn.gson.financial.kernel.service.DepartmentService;
import cn.gson.financial.kernel.service.EmployeeService;
import cn.gson.financial.kernel.service.SalaryEmployeeService;
import cn.gson.financial.kernel.service.SalarySocialService;
import cn.gson.financial.kernel.tool.JxlsUtils;
import cn.gson.financial.kernel.model.ua.request.GetCommonReq;
import cn.gson.financial.kernel.model.ua.request.SaveEmployeeReq;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.lang.Dict;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.poi.excel.ExcelReader;
import cn.hutool.poi.excel.ExcelUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/ua/salary/employee")
@Slf4j
public class UaSalaryEmployeeController {

    private final SalaryEmployeeService salaryEmployeeService;


    private final SalarySocialService salarySocialService;

    private final DepartmentService departmentService;

    private final EmployeeService employeeService;

    private final IndividualTaxService individualTaxService;

    /**
     * 查询员工列表
     * @param accountSetsId
     * @param page
     * @param queryVo
     * @return
     */
    @SaIgnore
    @GetMapping("/uaGetEmployeeList")
    public JsonResult uaGetEmployeeList(Long accountSetsId,  Page page,  SalaryEmployeeService.QueryVo queryVo) {
        if(ObjectUtils.isEmpty(accountSetsId)){
            return JsonResult.successful();
        }
        return JsonResult.successful(salaryEmployeeService.employeeList(accountSetsId, page, queryVo));
    }

    /**
     * 新增员工
     * @param req 员工信息
     * @return 操作结果
     */
    @SaIgnore
    @PostMapping("/uaSaveEmployee")
    public JsonResult uaSaveEmployee(@RequestBody SaveEmployeeReq req) {
        //Employee employee = CopierCopyUtils.convertTo(req.getEmployee(), Employee.class);
        if(ObjectUtils.isEmpty(req.getAccountSetsId())){
            return JsonResult.failure();
        }
       Boolean isExist = individualTaxService.individualTaxTaskIsExist(req.getAccountSetsId(),null);
        if (!isExist){
            return JsonResult.failure("个税任务已添加，如需新增员工，请联系群聊会计");
        }
        if(StringUtils.isEmpty(req.getEmployee().getCode())) {
            req.getEmployee().setCode(employeeService.getEmployeeCode(req.getAccountSetsId()));
        }
        req.getEmployee().setAccountSetsId(req.getAccountSetsId());
        req.getEmployee().setCategoryId((long) AccountingCategoryType.EMPLOYEE.ordinal());
        req.getEmployee().setCategoryType(AccountingCategoryType.EMPLOYEE);
        AcDetails emp = employeeService.save(req.getEmployee());
        if (req.getEmployee().getPurchase()) {
            //关联社保信息
            BatchSocialVo socialVo = new BatchSocialVo();
            socialVo.setRecords(CollUtil.newArrayList(emp.getId()));
            socialVo.setSocials(CopierCopyUtils
                    .convertTo(req.getSocials(), SalarySocialEmployeeDto.class));
            salaryEmployeeService.updateSocial(req.getAccountSetsId(), socialVo);
        }
        return JsonResult.successful();
    }

    /**
     * 获取员工导入模板
     * @param req
     * @param response
     * @throws Exception
     */
    @SaIgnore
    @PostMapping("/uaTemplate")
    public void uaTemplate(@RequestBody GetCommonReq req, HttpServletResponse response) throws Exception {
        Dict params = Dict.create();
        List<? extends AcDetails> departments = departmentService.loadData(req.getAccountSetsId());
        List<SalarySocial> socials = salarySocialService.list(req.getAccountSetsId());
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
     * 导入员工数据
     * @param file
     * @param accountSetsId
     * @return
     */
    @SaIgnore
    @PostMapping("/uaImport")
    public JsonResult uaImport(@RequestParam("file") MultipartFile file, @RequestParam Long accountSetsId) {
        try {
            ExcelReader reader = ExcelUtil.getReader(file.getInputStream());
            List<SalaryEmployeeDto> salaryEmployeeDtos = reader.readAll(SalaryEmployeeDto.class);
            List<Map<String, Object>> rows = reader.readAll();
            List<String> errors = new ArrayList<>();
            Map<String, SalaryEmpImportVo> importData = new HashMap<>();
            Map<String, AcDetails> depMap = new HashMap<>();

            Long categoryId = Long.valueOf(AccountingCategoryType.EMPLOYEE.ordinal() + "");

            for (int i = 0; i < salaryEmployeeDtos.size(); i++) {
                SalaryEmployeeDto dto = salaryEmployeeDtos.get(i);
                if (StrUtil.isNotEmpty(dto.getCode()) && StrUtil.isNotEmpty(dto.getName())) {
                    AcDetails acDetails = employeeService.loadByCode(accountSetsId, categoryId, dto.getCode());
                    if (acDetails == null) {
                        AcDetails department = null;
                        if (StrUtil.isNotEmpty(dto.getDepartmentName())) {
                            department = depMap.get(dto.getDepartmentName());
                            if (department == null) {
                                department = departmentService.loadByName(accountSetsId, Long.valueOf(AccountingCategoryType.DEPARTMENT.ordinal() + ""), dto.getDepartmentName());
                            }
                        }

                        if (department == null && StrUtil.isNotEmpty(dto.getDepartmentName())) {
                            errors.add("第" + (i + 2) + "行数据，部门名称 [" + dto.getDepartmentName() + "] 错误，请参考部门辅助核算～");
                        } else {
                            Employee employee = new Employee();
                            BeanUtils.copyProperties(dto, employee);
                            employee.setAccountSetsId(accountSetsId);
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
                    } else {
                        errors.add("第" + (i + 2) + "行数据，职员编码 [" + dto.getCode() + "] 和系统已有职员重复～");
                    }
                } else {
                    errors.add("第" + (i + 2) + "行数据，编码或名称未填写～");
                }
            }
            if (CollUtil.isNotEmpty(errors)) {
                return JsonResult.successful().setData(errors);
            }

            List<SalarySocial> socials = salarySocialService.list(accountSetsId);
            for (int i = 0; i < rows.size(); i++) {
                JSONObject row = new JSONObject(rows.get(i));
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
                importData.get(StrUtil.removePrefix(row.getStr("编码"), "`")).setSocialEmployees(socialEmployees);
            }
            if (CollUtil.isNotEmpty(importData)) {
                salaryEmployeeService.importData(importData);
            }
            return JsonResult.successful();
        } catch (IOException e) {
            log.error("薪资职员导入失败~", e);
            return JsonResult.failure(e.getMessage());
        }
    }

    /**
     * 获取员工工资汇总信息
     * @param req 员工信息
     * @return 操作结果
     */
    @SaIgnore
    @PostMapping("/uaGetEmployeeSalarySumInfo")
    public JsonResult uaGetEmployeeSalarySumInfo(@RequestBody GetCommonReq req) {
        return JsonResult.successful(salaryEmployeeService.uaGetEmployeeSalarySumInfo(req.getAccountSetsId(), req.getPeriod()));
    }

}
