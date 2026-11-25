package cn.gson.financial.yingDao.service;

import cn.gson.financial.kernel.common.DateUtil;
import cn.gson.financial.kernel.common.EasyExcelUtils;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.AccountingCategoryType;
import cn.gson.financial.kernel.model.enums.Gender;
import cn.gson.financial.kernel.model.repo.ExportFileRepository;
import cn.gson.financial.kernel.model.repo.IndividualTaxRepository;
import cn.gson.financial.kernel.model.repo.SyncEmployeeInformationRepository;
import cn.gson.financial.kernel.model.repo.SyncIndividualTaxEmployeeRepository;
import cn.gson.financial.kernel.model.yingDao.dto.ExportEmployeeInfoDTO;
import cn.gson.financial.kernel.model.yingDao.dto.ExportEmployeeSalaryDTO;
import cn.gson.financial.kernel.model.yingDao.dto.ImportEmployeeInfoDTO;
import cn.gson.financial.kernel.model.yingDao.req.*;
import cn.gson.financial.kernel.model.yingDao.resp.GetExportEmployeeInfoResp;
import cn.gson.financial.kernel.model.yingDao.resp.GetUserIndividualAccountSetResp;
import cn.gson.financial.kernel.service.AbsService;
import cn.gson.financial.kernel.service.EmployeeService;
import cn.gson.financial.kernel.tool.DateUtils;
import cn.gson.financial.yingDao.dto.IndividualTaxDto;
import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson.JSONObject;
import com.querydsl.core.Tuple;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * @Author zhb
 * @Date 2025-09-01
 * @Description
 */
@Service
@RequiredArgsConstructor
public class YingDaoService extends AbsService {

    private final QAccountSets qAccountSets = QAccountSets.accountSets;
    private final QUserAccountSets qUserAccountSets = QUserAccountSets.userAccountSets;
    private final QIndividualTax qIndividualTax = QIndividualTax.individualTax;
    private final EmployeeService employeeService;
    private final QEmployee qEmployee = QEmployee.employee;
    private final QSyncEmployeeInformation qSyncEmployeeInformation = QSyncEmployeeInformation.syncEmployeeInformation;
    private final SyncEmployeeInformationRepository syncEmployeeInformationRepository;
    private final QExportFile qExportFile = QExportFile.exportFile;
    private final ExportFileRepository exportFileRepository;
    private final QSalary qSalary = QSalary.salary;
    private final QSalaryFieldValue qSalaryFieldValue = QSalaryFieldValue.salaryFieldValue;
    private final QSalarySocial qSalarySocial = QSalarySocial.salarySocial;
    private final QSalarySocialEmployee qSalarySocialEmployee = QSalarySocialEmployee.salarySocialEmployee;
    private final IndividualTaxRepository individualTaxRepository;
    private final QSyncIndividualTaxEmployee qSyncIndividualTaxEmployee = QSyncIndividualTaxEmployee.syncIndividualTaxEmployee;
    private final SyncIndividualTaxEmployeeRepository syncIndividualTaxEmployeeRepository;
    //导出路径
    private final static String FILE_SAVE_URL = "C:\\Users\\23614\\Downloads\\";
    private final static String FILE_SAVE_SUFFIX = ".xls";

    /**
     * 获取用户账套
     *
     * @param req
     * @return
     */
    public GetUserIndividualAccountSetResp getUserIndividualAccountSet(GetUserIndividualAccountSetReq req) {
        GetUserIndividualAccountSetResp resp = new GetUserIndividualAccountSetResp();
        if ("gz".equals(req.getType())) {
//             查询出用户添加的上个月账期的个税任务进行遍历
            List<IndividualTaxDto> individualTaxDtoList  = lazyDao.findBySql("selectIndividualTaxList", req.toMap(), IndividualTaxDto.class);

            resp.setDeclareCount(individualTaxDtoList.size());
            if (CollUtil.isNotEmpty(individualTaxDtoList)) {
                IndividualTaxDto individualTax = individualTaxDtoList.get(0);
                resp.setAccountSetsId(individualTax.getAccountSetsId());
                resp.setCompanyName(individualTax.getCompanyName());
                resp.setCreditCode(individualTax.getCreditCode());
                resp.setIndividualTaxCode(individualTax.getIndividualTaxCode());
                resp.setId(individualTax.getId());
                resp.setPeriod(req.getPeriod());
            }
            return resp;
        } else if (("ryInSys".equals(req.getType()))) {
            // 排除同步员工信息账套
            List<SyncEmployeeInformation> syncEmployeeInformationList = bqf.selectFrom(qSyncEmployeeInformation)
                    .fetch();
            List<Long> accountSetIds = syncEmployeeInformationList.stream().map(SyncEmployeeInformation::getAccountSetsId).toList();

            List<AccountSets> accountSetsList = lazyDao.findBySql("selectRyInSys", req.toMap(), AccountSets.class);

            resp.setDeclareCount(accountSetsList.size());
            if (CollUtil.isNotEmpty(accountSetsList)) {
                AccountSets accountSets = accountSetsList.get(0);
                resp.setAccountSetsId(accountSets.getId());
                resp.setCompanyName(accountSets.getCompanyName());
                resp.setCreditCode(accountSets.getCreditCode());
                resp.setIndividualTaxCode(accountSets.getIndividualTaxCode());
            }
            return resp;
        }
        return resp;
    }

    /**
     * 解析账期：req.period 非空时按 YYYYMMDD 解析，否则取上个月第一天。
     */
    public LocalDate resolvePeriod(GetCommonReq req) {
        if (req != null && StringUtils.isNotBlank(req.getPeriod())) {
            return DateUtil.timeStrToLocalDate(req.getPeriod(), DateUtil.YYYYMMDD);
        }
        return LocalDate.now().withDayOfMonth(1).minusMonths(1L);
    }

    /**
     * 根据 accountSetsId 或 creditCode 解析账套。前者优先。
     */
    public AccountSets resolveAccountSets(GetCommonReq req) {
        if (req == null) {
            return null;
        }
        if (ObjectUtils.isNotEmpty(req.getAccountSetsId())) {
            return bqf.selectFrom(qAccountSets)
                    .where(qAccountSets.id.eq(req.getAccountSetsId()))
                    .fetchFirst();
        }
        if (StringUtils.isNotBlank(req.getCreditCode())) {
            return bqf.selectFrom(qAccountSets)
                    .where(qAccountSets.creditCode.eq(req.getCreditCode().trim()))
                    .fetchFirst();
        }
        return null;
    }

    /**
     * 导入员工信息
     *
     * @param req
     */
    @Transactional(rollbackFor = Exception.class)
    public void importEmployeeInfo(MultipartFile file, ImportEmployeeInfoReq req) {
        AccountSets accountSets = resolveAccountSets(req);
        if (ObjectUtils.isEmpty(accountSets)) {
            return;
        }
        req.setAccountSetsId(accountSets.getId());
        try {
            //读取file文件流，然后将其转换为ImportEmployeeInfoDTO
            List<ImportEmployeeInfoDTO> importEmployeeInfoDTOList = EasyExcelUtils.importFun(file.getInputStream(), ImportEmployeeInfoDTO.class);
            if (CollUtil.isNotEmpty(importEmployeeInfoDTOList)) {
                List<Employee> employeeList = bqf.selectFrom(qEmployee)
                        .where(qEmployee.accountSetsId.eq(accountSets.getId()))
                        .fetch();
                List<String> idCardList = new ArrayList<>();
                if (CollUtil.isNotEmpty(employeeList)) {
                    idCardList = employeeList.stream().map(Employee::getIdCard).toList();
                }

                List<String> finalIdCardList = idCardList;
                importEmployeeInfoDTOList.forEach(item -> {
                    Employee employee = new Employee();
                    //仅导入系统不存在、在职的员工数据
                    if (CollUtil.isNotEmpty(finalIdCardList) && finalIdCardList.contains(item.getIdCard())) {
                        return;
                    }
                    if ("非正常".equals(item.getEmployeeStatus())) {
                        return;
                    }
                    employee.setCode(employeeService.getEmployeeCode(accountSets.getId()));
                    employee.setAccountSetsId(accountSets.getId());
                    employee.setCategoryId((long) AccountingCategoryType.EMPLOYEE.ordinal());
                    employee.setCategoryType(AccountingCategoryType.EMPLOYEE);
                    employee.setName(item.getName());
                    employee.setIdCard(item.getIdCard());
                    employee.setPurchase(false);
                    if ("女".equals(item.getSex())) {
                        employee.setGender(Gender.女);
                    } else {
                        employee.setGender(Gender.男);
                    }
                    employee.setTelPhone(item.getTelPhone());
                    if ("正常".equals(item.getEmployeeStatus())) {
                        employee.setStatus("在职");
                        employee.setEnable(true);
                    } else {
                        employee.setStatus("离职");
                        employee.setEnable(false);
                    }
                    if (ObjectUtils.isNotEmpty(item.getBirthDate())) {
                        employee.setBirthDate(item.getBirthDate());
                    }
                    if (StringUtils.isNotEmpty(item.getOnboardingDate())) {
                        employee.setOnboardingDate(DateUtil.timeStrToLocalDate(item.getOnboardingDate(), DateUtil.YYYY_MM_DD));
                    }
                    if (StringUtils.isNotEmpty(item.getDimissionDate())) {
                        employee.setDimissionDate(DateUtil.timeStrToLocalDate(item.getDimissionDate(), DateUtil.YYYY_MM_DD));
                    }
                    employeeService.save(employee);
                });
            }
            addSyncEmployeeInformation(accountSets.getId(), 2, "系统同步人员成功");
        } catch (Exception e) {
            addSyncEmployeeInformation(accountSets.getId(), 1, "系统同步人员失败");
        }
    }

    /**
     * 保存个税员工文件导入结果
     * 开始新事务，保证出现异常也会存入导入数据
     *
     * @param accountSetsId
     * @param result
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void addSyncEmployeeInformation(Long accountSetsId, Integer result, String failedReason) {
        //查询是否存在导入数据，因为为一次性执行，存在直接覆盖
        SyncEmployeeInformation syncEmployeeInformation = bqf.selectFrom(qSyncEmployeeInformation)
                .where(qSyncEmployeeInformation.accountSetsId.eq(accountSetsId))
                .fetchFirst();
        if (ObjectUtils.isEmpty(syncEmployeeInformation)) {
            SyncEmployeeInformation save = new SyncEmployeeInformation();
            save.setAccountSetsId(accountSetsId);
            save.setResult(result);
            save.setFailedReason(failedReason);
            syncEmployeeInformationRepository.save(save);
        } else {
            jqf.update(qSyncEmployeeInformation)
                    .set(qSyncEmployeeInformation.result, result)
                    .set(qSyncEmployeeInformation.failedReason, failedReason)
                    .where(qSyncEmployeeInformation.accountSetsId.eq(accountSetsId)
                            .and(qSyncEmployeeInformation.id.eq(syncEmployeeInformation.getId())))
                    .execute();
        }
    }

    /**
     *影刀出现异常进行接收
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void addSyncEmployeeInformation2(SyncEmployeeInformation syncEmployeeInformation) {
        SyncEmployeeInformation syncEmployeeInformationOne = bqf.selectFrom(qSyncEmployeeInformation)
                .where(qSyncEmployeeInformation.accountSetsId.eq(syncEmployeeInformation.getAccountSetsId()))
                .fetchFirst();
            if (ObjectUtils.isEmpty(syncEmployeeInformationOne)) {
                SyncEmployeeInformation save = new SyncEmployeeInformation();
                save.setAccountSetsId(syncEmployeeInformation.getAccountSetsId());
                save.setResult(syncEmployeeInformation.getResult());
                save.setFailedReason(syncEmployeeInformation.getFailedReason());
                save.setImgName(syncEmployeeInformation.getImgName());
                syncEmployeeInformationRepository.save(save);
            } else {
                jqf.update(qSyncEmployeeInformation)
                        .set(qSyncEmployeeInformation.result, syncEmployeeInformation.getResult())
                        .set(qSyncEmployeeInformation.failedReason, syncEmployeeInformation.getFailedReason())
                        .set(qSyncEmployeeInformation.imgName,syncEmployeeInformation.getImgName())
                        .where(qSyncEmployeeInformation.accountSetsId.eq(syncEmployeeInformation.getAccountSetsId())
                                .and(qSyncEmployeeInformation.id.eq(syncEmployeeInformationOne.getId())))
                        .execute();
            }

        }


        /**
         * 导出员工信息
         *
         * @param req
         * @return ByteArrayOutputStream
         * @throws IOException
         */
        @Transactional(rollbackFor = Exception.class)
        public ByteArrayOutputStream exportEmployeeInfo(GetCommonReq req) throws IOException {
            AccountSets accountSets = resolveAccountSets(req);
            if (ObjectUtils.isEmpty(accountSets)) {
                return null;
            }
            req.setAccountSetsId(accountSets.getId());
            return exportEmployeeInfo(accountSets, resolvePeriod(req));
        }

    @Transactional(rollbackFor = Exception.class)
    public ByteArrayOutputStream exportEmployeeInfo(AccountSets accountSets, LocalDate period) throws IOException {
        List<Employee> employeeList = bqf.selectFrom(qEmployee)
                .where(qEmployee.accountSetsId.eq(accountSets.getId()))
                .fetch();
        if (CollUtil.isEmpty(employeeList)) {
            return null;
        }
        return exportEmployeeToStream(accountSets, employeeList, period);
    }

    /**
     * 导出员工薪资
     *
     * @param req
     * @return ByteArrayOutputStream
     * @throws IOException
     */
    @Transactional(rollbackFor = Exception.class)
    public ByteArrayOutputStream exportEmployeeSalary(GetCommonReq req) throws IOException {
        AccountSets accountSets = resolveAccountSets(req);
        if (ObjectUtils.isEmpty(accountSets)) {
            return null;
        }
        req.setAccountSetsId(accountSets.getId());
        return exportEmployeeSalary(accountSets, resolvePeriod(req));
    }

    @Transactional(rollbackFor = Exception.class)
    public ByteArrayOutputStream exportEmployeeSalary(AccountSets accountSets, LocalDate period) throws IOException {
        List<Employee> employeeList = bqf.selectFrom(qEmployee)
                .where(qEmployee.accountSetsId.eq(accountSets.getId()))
                .fetch();
        if (CollUtil.isNotEmpty(employeeList)) {
            List<ExportEmployeeInfoDTO> exportEmployeeInfoDTOList = getEmployeeList(accountSets, employeeList, period);
            //导出工资 务必使用导出员工数据作为导出工资数据的基本条件
            if (CollUtil.isNotEmpty(exportEmployeeInfoDTOList)) {
                return exportSalaryToStream(accountSets, exportEmployeeInfoDTOList, period);
            }
        }
        return null;
    }

    /**
     * 导出员工
     *
     * @param accountSets
     * @param employeeList
     * @param period
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public List<ExportEmployeeInfoDTO> exportEmployee(AccountSets accountSets, List<Employee> employeeList, LocalDate period) {
        //拼装导出数据 仅到处在职员工数据
        List<ExportEmployeeInfoDTO> exportEmployeeList = employeeList.stream().filter(f -> "在职".equals(f.getStatus())).map(item -> {
            ExportEmployeeInfoDTO dto = new ExportEmployeeInfoDTO();
            dto.setId(item.getId());
            dto.setName(strTrim(item.getName()));
            dto.setIdCard(strTrim(item.getIdCard()));
            fillIdentityFields(dto, item);
            if (ObjectUtils.isNotEmpty(item.getGender())) {
                dto.setSex(strTrim(item.getGender().name()));
            } else {
                dto.setSex(getGenderByIdCard(item.getIdCard()));
            }
            if (ObjectUtils.isNotEmpty(item.getBirthDate())) {
                dto.setBirthDate(item.getBirthDate());
            } else {
                dto.setBirthDate(getBirthdayLocalDate(item.getIdCard()));
            }

            dto.setOnboardingType(StringUtils.isNotBlank(strTrim(item.getEmploymentType())) ? item.getEmploymentType().trim() : "雇员");
            dto.setTelPhone(strTrim(item.getTelPhone()));
            dto.setDimissionDate(DateUtil.localDateToString(item.getDimissionDate(), DateUtil.YYYY_MM_DD));
            dto.setOnboardingDate(DateUtil.localDateToString(item.getOnboardingDate(), DateUtil.YYYY_MM_DD));
            return dto;
        }).toList();
        if (CollUtil.isEmpty(exportEmployeeList)) {
            return Collections.emptyList();
        }
        //文件名称
        String fileName = accountSets.getCompanyName() + "-人员" + System.currentTimeMillis() + FILE_SAVE_SUFFIX;
        //导出工具类
        EasyExcelUtils.exportToLocal(FILE_SAVE_URL + fileName, fileName, ExportEmployeeInfoDTO.class, exportEmployeeList);
        // 保存导出信息
        addExportFileInfo(accountSets.getId(), period, FILE_SAVE_URL, fileName, FILE_SAVE_SUFFIX, FILE_SAVE_URL + fileName, "ry", "yingdao");
        return exportEmployeeList;
    }

    /**
     * 导出员工
     *
     * @param accountSets
     * @param employeeList
     * @param period
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public List<ExportEmployeeInfoDTO> Employee(AccountSets accountSets, List<Employee> employeeList, LocalDate period) {
        //拼装导出数据 仅到处在职员工数据
        List<ExportEmployeeInfoDTO> exportEmployeeList = employeeList.stream().filter(f -> "在职".equals(f.getStatus())).map(item -> {
            ExportEmployeeInfoDTO dto = new ExportEmployeeInfoDTO();
            dto.setId(item.getId());
            dto.setName(strTrim(item.getName()));
            dto.setIdCard(strTrim(item.getIdCard()));
            fillIdentityFields(dto, item);
            if (ObjectUtils.isNotEmpty(item.getGender())) {
                dto.setSex(strTrim(item.getGender().name()));
            } else {
                dto.setSex(getGenderByIdCard(item.getIdCard()));
            }
            if (ObjectUtils.isNotEmpty(item.getBirthDate())) {
                dto.setBirthDate(item.getBirthDate());
            } else {
                dto.setBirthDate(getBirthdayLocalDate(item.getIdCard()));
            }

            dto.setOnboardingType(StringUtils.isNotBlank(strTrim(item.getEmploymentType())) ? item.getEmploymentType().trim() : "雇员");
            dto.setTelPhone(strTrim(item.getTelPhone()));
            dto.setDimissionDate(DateUtil.localDateToString(item.getDimissionDate(), DateUtil.YYYY_MM_DD));
            dto.setOnboardingDate(DateUtil.localDateToString(item.getOnboardingDate(), DateUtil.YYYY_MM_DD));
            return dto;
        }).toList();
        if (CollUtil.isEmpty(exportEmployeeList)) {
            return Collections.emptyList();
        }
        return exportEmployeeList;
    }


    /**
     * 导出工资
     *
     * @param accountSets
     * @param exportEmployeeList
     * @param period
     */
    @Transactional(rollbackFor = Exception.class)
    public void exportSalary(AccountSets accountSets, List<ExportEmployeeInfoDTO> exportEmployeeList, LocalDate period) {
        List<Salary> salaryList = bqf.selectFrom(qSalary)
                .where(qSalary.accountSetsId.eq(accountSets.getId())
                        .and(qSalary.state.eq(true))
                        .and(qSalary.period.eq(period)))
                .fetch();
        if (CollUtil.isEmpty(salaryList)) {
            return;
        }
        //查询数据 工资信息在SalaryFieldValue表中获取
        List<Long> salaryIdList = salaryList.stream().map(Salary::getId).toList();
        List<Long> employeeIdList = exportEmployeeList.stream().map(ExportEmployeeInfoDTO::getId).toList();
        List<SalaryFieldValue> salaryFieldValueList = bqf.selectFrom(qSalaryFieldValue)
                .where(qSalaryFieldValue.salaryId.in(salaryIdList)
                        .and(qSalaryFieldValue.empId.in(employeeIdList))
                        .and(qSalaryFieldValue.accountSetsId.eq(accountSets.getId())))
                .fetch();
        if (CollUtil.isEmpty(salaryFieldValueList)) {
            return;
        }
        Map<Long, List<SalaryFieldValue>> fieldMap = salaryFieldValueList
                .stream().collect(Collectors.groupingBy(SalaryFieldValue::getEmpId));
        List<Tuple> tupleList = bqf.selectFrom(qSalarySocialEmployee)
                .select(qSalarySocialEmployee.empId, qSalarySocialEmployee.personNum, qSalarySocial.name)
                .leftJoin(qSalarySocial).on(qSalarySocial.id.eq(qSalarySocialEmployee.socialId))
                .where(qSalarySocialEmployee.accountSetsId.eq(accountSets.getId())
                        .and(qSalarySocial.accountSetsId.eq(accountSets.getId()))
                        .and(qSalarySocialEmployee.empId.in(employeeIdList)))
                .fetch();
        Map<Long, List<Tuple>> socialMap = new HashMap<>();
        if (CollUtil.isNotEmpty(tupleList)) {
            socialMap = tupleList.stream().collect(Collectors.groupingBy(item -> item.get(qSalarySocialEmployee.empId)));
        }
        //拼装导出数据
        Map<Long, List<SalaryFieldValue>> finalFieldMap = fieldMap;
        Map<Long, List<Tuple>> finalSocialMap = socialMap;
        List<ExportEmployeeSalaryDTO> exportEmployeeSalaryDTOList = exportEmployeeList.stream().map(item -> {
            ExportEmployeeSalaryDTO dto = new ExportEmployeeSalaryDTO();
            dto.setCode(item.getCode());
            dto.setName(item.getName());
            dto.setIdCardType(item.getIdCardType());
            dto.setIdCard(item.getIdCard());
            if (CollUtil.isEmpty(finalFieldMap.get(item.getId()))) {
                return null;
            }
            List<SalaryFieldValue> salaryFieldValues = finalFieldMap.get(item.getId());
            AtomicReference<Double> salarySum = new AtomicReference<>(0.00);
            salaryFieldValues.forEach(value -> {
                JSONObject jsonObject = JSONObject.parseObject(JSONObject.toJSONString(value.getFieldValue()));
                Map<String, Object> map = (Map<String, Object>) jsonObject;
                map.forEach((k, v) -> {
                    //仅使用岗位工资
                    Map<String, Object> vMap = (Map<String, Object>) v;
                    if ("岗位工资".equals(vMap.get("name"))) {
                        salarySum.set(salarySum.get() + (Double.parseDouble(vMap.get("value").toString())));
                    }
                });
            });
            dto.setIncome(salarySum.toString());
            //获取五险一金信息
            if (MapUtils.isNotEmpty(finalSocialMap) && CollUtil.isNotEmpty(finalSocialMap.get(item.getId()))) {
                List<Tuple> tuples = finalSocialMap.get(item.getId());
                tuples.forEach(tuple -> {
                    if ("养老保险".equals(tuple.get(qSalarySocial.name))) {
                        dto.setEndowmentInsurance(tuple.get(qSalarySocialEmployee.personNum).toString());
                    }
                    if ("医疗保险".equals(tuple.get(qSalarySocial.name))) {
                        dto.setMedicalInsurance(tuple.get(qSalarySocialEmployee.personNum).toString());
                    }
                    if ("失业保险".equals(tuple.get(qSalarySocial.name))) {
                        dto.setUnemploymentInsurance(tuple.get(qSalarySocialEmployee.personNum).toString());
                    }
                    if ("公积金".equals(tuple.get(qSalarySocial.name))) {
                        dto.setHousingProvidentFund(tuple.get(qSalarySocialEmployee.personNum).toString());
                    }
                });
            }
            return dto;
        }).filter(ObjectUtils::isNotEmpty).toList();
        if (CollUtil.isEmpty(exportEmployeeSalaryDTOList)) {
            return;
        }
        //文件名称
        String fileName = accountSets.getCompanyName() + "-工资" + System.currentTimeMillis() + FILE_SAVE_SUFFIX;
        //导出工具类
        EasyExcelUtils.exportToLocal(FILE_SAVE_URL + fileName, fileName, ExportEmployeeSalaryDTO.class, exportEmployeeSalaryDTOList);
        // 保存导出信息
        addExportFileInfo(accountSets.getId(), period, FILE_SAVE_URL, fileName, FILE_SAVE_SUFFIX, FILE_SAVE_URL + fileName, "gz", "yingdao");
    }

    /**
     * 保存导出文件信息
     *
     * @param accountSetsId
     * @param period
     * @param fileUrl
     * @param fileName
     * @param fileSuffix
     * @param filePath
     * @param type
     * @param source
     */
    @Transactional(rollbackFor = Exception.class)
    public void addExportFileInfo(Long accountSetsId, LocalDate period, String fileUrl, String fileName,
                                  String fileSuffix, String filePath, String type, String source) {
        //根据特定条件查询是否存在导出记录，不存在新增，存在修改
        ExportFile salaryExportFile = bqf.selectFrom(qExportFile)
                .where(qExportFile.accountSetsId.eq(accountSetsId)
                        .and(qExportFile.type.eq(type))
                        .and(qExportFile.source.eq(source))
                        .and(ObjectUtils.isEmpty(period) ? null : qExportFile.period.eq(period)))
                .fetchFirst();
        if (ObjectUtils.isEmpty(salaryExportFile)) {
            ExportFile save = new ExportFile();
            save.setAccountSetsId(accountSetsId);
            save.setFileUrl(fileUrl);
            save.setFileName(fileName);
            save.setFileSuffix(fileSuffix);
            save.setFilePath(filePath);
            save.setType(type);
            save.setSource(source);
            if (ObjectUtils.isNotEmpty(period)) {
                save.setPeriod(period);
            }
            exportFileRepository.save(save);
        } else {
            jqf.update(qExportFile)
                    .set(qExportFile.fileUrl, fileUrl)
                    .set(qExportFile.fileName, fileName)
                    .set(qExportFile.filePath, filePath)
                    .set(qExportFile.fileSuffix, fileSuffix)
                    .where(qExportFile.accountSetsId.eq(accountSetsId)
                            .and(qExportFile.id.eq(salaryExportFile.getId())))
                    .execute();
        }
    }

    /**
     * 字符串去空
     *
     * @param str
     * @return
     */
    private String strTrim(String str) {
        if (StringUtils.isEmpty(str)) {
            return null;
        }
        return str.trim();
    }

    /**
     * 按人员类型（境内/境外）填充导出 DTO 的证件类型、国籍、入职年度就业情形及境外专属字段。
     * <p>
     * 历史实现把证件类型/国籍写死成"居民身份证/中国"，导致境外人员导出后又变回境内。
     * 这里改为读取 {@link Employee} 实际保存的值：存了就用存的，没存才回退默认值；
     * 境外人员额外回填中文名、涉税事由、出生国家、出入境时间、其他证件等字段。
     * 注意：本方法不负责 onboardingType（任职受雇从业类型），该字段在各调用处单独读取设置。
     *
     * @param dto  导出 DTO
     * @param item 员工实体
     */
    private void fillIdentityFields(ExportEmployeeInfoDTO dto, Employee item) {
        boolean overseas = "境外".equals(item.getPersonType());

        // 证件类型：存了就用存的；境内默认"居民身份证"，境外不臆测、留空
        String idType = strTrim(item.getIdType());
        dto.setIdCardType(StringUtils.isNotBlank(idType) ? idType : (overseas ? null : "居民身份证"));

        // 国籍(地区)：存了就用存的；境内默认"中国"，境外留空
        String nationality = strTrim(item.getNationality());
        dto.setCountry(StringUtils.isNotBlank(nationality) ? nationality : (overseas ? null : "中国"));

        // 入职年度就业情形（境内外通用）
        dto.setEmploymentSituation(strTrim(item.getEmploymentSituation()));

        // 境外人员专属字段
        if (overseas) {
            dto.setChineseName(strTrim(item.getChineseName()));
            dto.setTaxRelatedMatters(strTrim(item.getTaxReason()));
            dto.setBirthCounty(strTrim(item.getBirthCountry()));
            dto.setFirstEntryTime(DateUtil.localDateToString(item.getFirstEntryDate(), DateUtil.YYYY_MM_DD));
            dto.setExpectedDepartureTime(DateUtil.localDateToString(item.getExpectedDepartureDate(), DateUtil.YYYY_MM_DD));
            dto.setOtherCertificateType(strTrim(item.getOtherIdType()));
            dto.setOtherCertificateNumber(strTrim(item.getOtherIdNumber()));
        }
    }

    /**
     * 根据身份证号获取性别
     *
     * @param idCard 身份证号码
     * @return 性别：男，女，未知
     */
    private String getGenderByIdCard(String idCard) {
        if (idCard == null || idCard.trim().isEmpty()) {
            return null;
        }
        // 去除空格
        idCard = idCard.trim();
        // 验证身份证长度（15位或18位）
        if (idCard.length() != 15 && idCard.length() != 18) {
            return null;
        }
        try {
            String genderStr;
            if (idCard.length() == 15) {
                // 15位身份证：最后一位是性别位
                genderStr = idCard.substring(14, 15);
            } else {
                // 18位身份证：倒数第二位是性别位
                genderStr = idCard.substring(16, 17);
            }

            int genderCode = Integer.parseInt(genderStr);
            // 奇数表示男性，偶数表示女性
            return genderCode % 2 == 1 ? "男" : "女";

        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 根据身份证号获取出生日期
     *
     * @param idCard 身份证号码
     * @return 出生日期（LocalDate类型），解析失败返回null
     */
    private static LocalDate getBirthdayLocalDate(String idCard) {
        if (idCard == null || idCard.trim().isEmpty()) {
            return null;
        }
        idCard = idCard.trim();
        // 验证身份证长度
        if (idCard.length() != 15 && idCard.length() != 18) {
            return null;
        }
        try {
            String birthDateStr;
            if (idCard.length() == 15) {
                // 15位身份证：7-12位是出生日期（YYMMDD）
                birthDateStr = "19" + idCard.substring(6, 12);
            } else {
                // 18位身份证：7-14位是出生日期（YYYYMMDD）
                birthDateStr = idCard.substring(6, 14);
            }

            // 解析为LocalDate
            return LocalDate.parse(birthDateStr, DateTimeFormatter.BASIC_ISO_DATE);

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取导出员工文件信息
     *
     * @param req
     * @return
     */
    public GetExportEmployeeInfoResp getExportEmployeeInfo(GetExportEmployeeInfoReq req) {
        GetExportEmployeeInfoResp resp = new GetExportEmployeeInfoResp();
        if (ObjectUtils.isEmpty(req.getAccountSetsId())) {
            return resp;
        }
        LocalDate period = LocalDate.now().withDayOfMonth(1).minusMonths(1L);
        if (ObjectUtils.isNotEmpty(req.getPeriod())) {
            period = DateUtil.timeStrToLocalDate(req.getPeriod(), DateUtil.YYYYMMDD);
        }
        //查询条件组装
        ExportFile exportFile = bqf.selectFrom(qExportFile)
                .where(qExportFile.accountSetsId.eq(req.getAccountSetsId())
                        .and(qExportFile.type.eq(req.getType()))
                        .and(qExportFile.source.eq(req.getSource()))
                        .and(qExportFile.period.eq(period)))
                .fetchFirst();
        //返回赋值
        if (ObjectUtils.isNotEmpty(exportFile)) {
            resp.setFileName(exportFile.getFileName());
            resp.setFileUrl(exportFile.getFileUrl());
            resp.setFileSuffix(exportFile.getFileSuffix());
            resp.setFilePath(exportFile.getFilePath());
        }
        return resp;
    }

    /**
     * 保存个税报税完成信息
     *
     * @param req
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateIndividualTax(UpdateIndividualTaxReq req) {

        // 处理失败原因：检查是否为 "''"（两个单引号）或空字符串
        if (Objects.equals(req.getFailedReason(), "''") || StringUtils.isBlank(req.getFailedReason())){
            req.setFailedReason("影刀流程出现未识别项");
        }
        if (ObjectUtils.isEmpty(req.getResult())){
            req.setResult(1);
        }

        // 处理图片名称：检查是否为 "''"（两个单引号）或空字符串
        if (Objects.equals(req.getImgName(), "''") || StringUtils.isBlank(req.getImgName())){
            req.setImgName("无");
        }

        /**
         * 根据id更新个税任务表中的状态
         */
        jqf.update(qIndividualTax)
                .set(qIndividualTax.failedReason,req.getFailedReason())
                .set(qIndividualTax.result,req.getResult())
                .set(qIndividualTax.imgName,req.getImgName())
                .where(qIndividualTax.id.eq(req.getId())).execute();
        return Boolean.TRUE;
    }
    /**
     * 导出员工信息到流
     *
     * @param accountSets
     * @param employeeList
     * @param period
     * @return ByteArrayOutputStream
     * @throws IOException
     */
    private ByteArrayOutputStream exportEmployeeToStream(AccountSets accountSets, List<Employee> employeeList, LocalDate period) throws IOException {
        //拼装导出数据 仅到处在职员工数据
        List<ExportEmployeeInfoDTO> exportEmployeeList = employeeList.stream().map(item -> {
            ExportEmployeeInfoDTO dto = new ExportEmployeeInfoDTO();
            dto.setId(item.getId());
            dto.setName(strTrim(item.getName()));
            dto.setIdCard(strTrim(item.getIdCard()));
            fillIdentityFields(dto, item);
            if (ObjectUtils.isNotEmpty(item.getGender())) {
                dto.setSex(strTrim(item.getGender().name()));
            } else {
                dto.setSex(getGenderByIdCard(item.getIdCard()));
            }
            if (ObjectUtils.isNotEmpty(item.getBirthDate())) {
                dto.setBirthDate(item.getBirthDate());
            } else {
                dto.setBirthDate(getBirthdayLocalDate(item.getIdCard()));
            }
            if ("在职".equals(item.getStatus())) {

            } else {
                if (item.getDimissionDate() != null) {
                    dto.setDimissionDate(DateUtil.localDateToString(item.getDimissionDate(), DateUtil.YYYY_MM_DD));
                } else {
                    LocalDate lastDayOfLastTwoMonth = DateUtils.getLastDayOfLastTwoMonth();
                    dto.setDimissionDate(DateUtil.localDateToString(lastDayOfLastTwoMonth, DateUtil.YYYY_MM_DD));
                }
            }

            dto.setOnboardingType(StringUtils.isNotBlank(strTrim(item.getEmploymentType())) ? item.getEmploymentType().trim() : "雇员");
            dto.setTelPhone(strTrim(item.getTelPhone()));
            if(item.getOnboardingDate() != null){
                dto.setOnboardingDate(DateUtil.localDateToString(item.getOnboardingDate(), DateUtil.YYYY_MM_DD));
            }else{
                LocalDate getFirstDayOfYear = DateUtils.getFirstDayOfYear();
                dto.setOnboardingDate(DateUtil.localDateToString(getFirstDayOfYear, DateUtil.YYYY_MM_DD));
            }
            return dto;
        }).toList();

        if (CollUtil.isEmpty(exportEmployeeList)) {
            return null;
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        EasyExcelUtils.exportToLocal(outputStream, "员工信息", ExportEmployeeInfoDTO.class, exportEmployeeList);
        return outputStream;
    }

    /**
     * 获取员工列表（用于工资导出）
     *
     * @param accountSets
     * @param employeeList
     * @param period
     * @return
     */
    private List<ExportEmployeeInfoDTO> getEmployeeList(AccountSets accountSets, List<Employee> employeeList, LocalDate period) {
        //拼装导出数据 仅到处在职员工数据
        List<ExportEmployeeInfoDTO> exportEmployeeList = employeeList.stream().filter(f -> "在职".equals(f.getStatus())).map(item -> {
            ExportEmployeeInfoDTO dto = new ExportEmployeeInfoDTO();
            dto.setId(item.getId());
            dto.setName(strTrim(item.getName()));
            dto.setIdCard(strTrim(item.getIdCard()));
            fillIdentityFields(dto, item);
            if (ObjectUtils.isNotEmpty(item.getGender())) {
                dto.setSex(strTrim(item.getGender().name()));
            } else {
                dto.setSex(getGenderByIdCard(item.getIdCard()));
            }
            if (ObjectUtils.isNotEmpty(item.getBirthDate())) {
                dto.setBirthDate(item.getBirthDate());
            } else {
                dto.setBirthDate(getBirthdayLocalDate(item.getIdCard()));
            }

            dto.setOnboardingType(StringUtils.isNotBlank(strTrim(item.getEmploymentType())) ? item.getEmploymentType().trim() : "雇员");
            dto.setTelPhone(strTrim(item.getTelPhone()));
            dto.setDimissionDate(DateUtil.localDateToString(item.getDimissionDate(), DateUtil.YYYY_MM_DD));
            dto.setOnboardingDate(DateUtil.localDateToString(item.getOnboardingDate(), DateUtil.YYYY_MM_DD));
            return dto;
        }).toList();
        return exportEmployeeList;
    }

    /**
     * 导出工资信息到流
     *
     * @param accountSets
     * @param exportEmployeeList
     * @param period
     * @return ByteArrayOutputStream
     * @throws IOException
     */
    private ByteArrayOutputStream exportSalaryToStream(AccountSets accountSets, List<ExportEmployeeInfoDTO> exportEmployeeList, LocalDate period) throws IOException {
        List<Salary> salaryList = bqf.selectFrom(qSalary)
                .where(qSalary.accountSetsId.eq(accountSets.getId())
                        .and(qSalary.state.eq(true))
                        .and(qSalary.period.eq(period)))
                .fetch();
        List<Long> salaryIdList = new ArrayList<>();
        //工资信息为空  添加一个空数据
        if (CollUtil.isEmpty(salaryList)) {
            salaryIdList.add(0L);
        } else {
            //查询数据 工资信息在SalaryFieldValue表中获取
            salaryIdList = salaryList.stream().map(Salary::getId).toList();
        }
        //所有员工ID
        List<Long> employeeIdList = exportEmployeeList.stream().map(ExportEmployeeInfoDTO::getId).toList();

        List<SalaryFieldValue> salaryFieldValueList = bqf.selectFrom(qSalaryFieldValue)
                .where(qSalaryFieldValue.salaryId.in(salaryIdList)
                        .and(qSalaryFieldValue.empId.in(employeeIdList))
                        .and(qSalaryFieldValue.accountSetsId.eq(accountSets.getId())))
                .fetch();
        if (CollUtil.isEmpty(salaryFieldValueList)) {
            employeeIdList.forEach(item -> {
                SalaryFieldValue unSalaryFieldValue = new SalaryFieldValue();
                unSalaryFieldValue.setPeriod(period);
                unSalaryFieldValue.setEmpId(item);
                salaryFieldValueList.add(unSalaryFieldValue);
            });
        }
        //对比全部员工 和 和 存在工资数据的员工
        //带工资的员工
        List<Long> empIdList = salaryFieldValueList.stream().map(SalaryFieldValue::getEmpId).toList();
        List<Long> unSalaryEmpIdList = employeeIdList.stream().filter(empId ->
                !empIdList.contains(empId)).toList();
        unSalaryEmpIdList.forEach(item -> {
            //给没有工资的员工新增空工资数据
            //添加至salaryFieldValueList
            SalaryFieldValue salaryFieldValue = new SalaryFieldValue();
            salaryFieldValue.setPeriod(period);
            salaryFieldValue.setEmpId(item);
            salaryFieldValueList.add(salaryFieldValue);
        });
        Map<Long, List<SalaryFieldValue>> fieldMap = salaryFieldValueList
                .stream().collect(Collectors.groupingBy(SalaryFieldValue::getEmpId));
        List<Tuple> tupleList = bqf.selectFrom(qSalarySocialEmployee)
                .select(qSalarySocialEmployee.empId, qSalarySocialEmployee.personNum, qSalarySocial.name)
                .leftJoin(qSalarySocial).on(qSalarySocial.id.eq(qSalarySocialEmployee.socialId))
                .where(qSalarySocialEmployee.accountSetsId.eq(accountSets.getId())
                        .and(qSalarySocial.accountSetsId.eq(accountSets.getId()))
                        .and(qSalarySocialEmployee.empId.in(employeeIdList)))
                .fetch();
        Map<Long, List<Tuple>> socialMap = new HashMap<>();
        if (CollUtil.isNotEmpty(tupleList)) {
            socialMap = tupleList.stream().collect(Collectors.groupingBy(item -> item.get(qSalarySocialEmployee.empId)));
        }
        //拼装导出数据
        Map<Long, List<SalaryFieldValue>> finalFieldMap = fieldMap;
        Map<Long, List<Tuple>> finalSocialMap = socialMap;
        List<ExportEmployeeSalaryDTO> exportEmployeeSalaryDTOList = exportEmployeeList.stream().map(item -> {
            ExportEmployeeSalaryDTO dto = new ExportEmployeeSalaryDTO();
            dto.setCode(item.getCode());
            dto.setName(item.getName());
            dto.setIdCardType(item.getIdCardType());
            dto.setIdCard(item.getIdCard());
            if (CollUtil.isEmpty(finalFieldMap.get(item.getId()))) {
                return null;
            }
            List<SalaryFieldValue> salaryFieldValues = finalFieldMap.get(item.getId());
            AtomicReference<Double> salarySum = new AtomicReference<>(0.00);
            salaryFieldValues.forEach(value -> {
                if (ObjectUtils.isNotEmpty(value.getFieldValue())) {
                    JSONObject jsonObject = JSONObject.parseObject(JSONObject.toJSONString(value.getFieldValue()));
                    Map<String, Object> map = (Map<String, Object>) jsonObject;
                    map.forEach((k, v) -> {
                        //仅使用岗位工资
                        Map<String, Object> vMap = (Map<String, Object>) v;
                        if ("岗位工资".equals(vMap.get("name"))) {
                            salarySum.set(salarySum.get() + (Double.parseDouble(vMap.get("value").toString())));
                        }
                    });
                }
            });
            dto.setIncome(salarySum.toString());
            //获取五险一金信息
            if (MapUtils.isNotEmpty(finalSocialMap) && CollUtil.isNotEmpty(finalSocialMap.get(item.getId()))) {
                List<Tuple> tuples = finalSocialMap.get(item.getId());
                tuples.forEach(tuple -> {
                    if ("养老保险".equals(tuple.get(qSalarySocial.name))) {
                        dto.setEndowmentInsurance(tuple.get(qSalarySocialEmployee.personNum).toString());
                    }
                    if ("医疗保险".equals(tuple.get(qSalarySocial.name))) {
                        dto.setMedicalInsurance(tuple.get(qSalarySocialEmployee.personNum).toString());
                    }
                    if ("失业保险".equals(tuple.get(qSalarySocial.name))) {
                        dto.setUnemploymentInsurance(tuple.get(qSalarySocialEmployee.personNum).toString());
                    }
                    if ("公积金".equals(tuple.get(qSalarySocial.name))) {
                        dto.setHousingProvidentFund(tuple.get(qSalarySocialEmployee.personNum).toString());
                    }
                });
            }
            return dto;
        }).filter(ObjectUtils::isNotEmpty).toList();

        if (CollUtil.isEmpty(exportEmployeeSalaryDTOList)) {
            return null;
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        EasyExcelUtils.exportToLocal(outputStream, "员工工资信息", ExportEmployeeSalaryDTO.class, exportEmployeeSalaryDTOList);
        return outputStream;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteExportFileInfo(Long accountSetsId, LocalDate period, String gz, String yingdao) {
        jqf.delete(qExportFile).where(qExportFile.accountSetsId.eq(accountSetsId).and(qExportFile.period.eq(period)).and(qExportFile.type.eq(gz)).and(qExportFile.source.eq(yingdao))).execute();
    }

}
