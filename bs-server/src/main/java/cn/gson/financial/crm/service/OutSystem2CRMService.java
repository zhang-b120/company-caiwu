package cn.gson.financial.crm.service;

import cn.gson.financial.kernel.common.CopierCopyUtils;
import cn.gson.financial.kernel.common.DateUtil;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.crm.dto.TaxDeclareItemsInfoDTO;
import cn.gson.financial.kernel.model.crm.dto.TaxTableDTO;
import cn.gson.financial.kernel.model.crm.dto.AddAccountSetMigrationDataDTO;
import cn.gson.financial.kernel.model.bean.FieldValue;
import cn.gson.financial.kernel.model.crm.req.AddEmployeeReq;
import cn.gson.financial.kernel.model.crm.req.AddTaskListReq;
import cn.gson.financial.kernel.model.crm.req.GetCommonReq;
import cn.gson.financial.kernel.model.crm.req.GetReportReq;
import cn.gson.financial.kernel.model.crm.req.GetTaxDeclareItemsReq;
import cn.gson.financial.kernel.model.crm.req.UpdateAccountSetsUserReq;
import cn.gson.financial.kernel.model.crm.resp.GetAccountSummaryDataResp;
import cn.gson.financial.kernel.model.crm.resp.GetExperienceMigrationDataResp;
import cn.gson.financial.kernel.model.crm.resp.GetReportResp;
import cn.gson.financial.kernel.model.crm.resp.GetTaxReportResultDataResp;
import cn.gson.financial.kernel.model.crm.resp.GetTaxTabResp;
import cn.gson.financial.kernel.model.dto.BankLibDto;
import cn.gson.financial.kernel.model.dto.ReportItemsDto;
import cn.gson.financial.kernel.model.dto.TaxDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.repo.*;
import cn.gson.financial.kernel.model.enums.Gender;
import cn.gson.financial.kernel.service.AbsService;
import cn.gson.financial.kernel.service.AccountSetsService;
import cn.gson.financial.kernel.service.CurrencyService;
import cn.gson.financial.kernel.service.EmployeeService;
import cn.gson.financial.kernel.service.ReportService;
import cn.gson.financial.kernel.service.RoleService;
import cn.gson.financial.crm.util.RpaUtil;
import cn.gson.financial.kernel.service.TaxService;
import com.querydsl.core.BooleanBuilder;
import cn.gson.financial.kernel.tool.DateUtils;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.querydsl.core.Tuple;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.temporal.ChronoField;
import java.util.*;
import java.util.stream.Collectors;

import static cn.gson.financial.kernel.model.crm.crmEnum.CodeEnum.*;

/**
 * 外部系统CRM服务层
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class OutSystem2CRMService extends AbsService {

    private final RpaUtil rpaUtil;
    private final AccountSetsService accountSetsService;
    private final EmployeeService employeeService;
    private final TaxService taxService;
    /** RPT-FN-01：CRM 取财务报表（资产负债/利润/现金流） */
    private final ReportService reportService;
    /** RPT-FN-01：取账套本位币 ID（避免外币账套同科目多行） */
    private final CurrencyService currencyService;
    /** 账套停用恢复时为创建人补建权限：取账套管理员角色 */
    private final RoleService roleService;
    /** 账套停用恢复时为创建人补建权限：用户-账套关联 */
    private final UserAccountSetsRepository userAccountSetsRepository;
    private final QTaxTableSort qTaxTableSort = QTaxTableSort.taxTableSort;
    private final QSalary qSalary = QSalary.salary;
    private final QSalaryFieldValue qSalaryFieldValue = QSalaryFieldValue.salaryFieldValue;
    private final QSalaryField qSalaryField = QSalaryField.salaryField;
    private final QSalarySocial qSalarySocial = QSalarySocial.salarySocial;
    private final QSalarySocialEmployee qSalarySocialEmployee = QSalarySocialEmployee.salarySocialEmployee;
    private final QBankStatement qBankStatement = QBankStatement.bankStatement;
    private final QBankStatementDetail qBankStatementDetail = QBankStatementDetail.bankStatementDetail;
    private final QEmployee qEmployee = QEmployee.employee;
    private final QFileInfo qFileInfo = QFileInfo.fileInfo;
    private final SalaryRepository salaryRepository;
    private final SalaryFieldValueRepository salaryFieldValueRepository;
    private final SalarySocialRepository salarySocialRepository;
    private final SalarySocialEmployeeRepository salarySocialEmployeeRepository;
    private final BankStatementRepository bankStatementRepository;
    private final BankStatementDetailRepository bankStatementDetailRepository;
    private final EmployeeRepository employeeRepository;
    private final AccountSetsRepository accountSetsRepository;
    private final FileInfoRepository fileInfoRepository;
    private final QUser qUser = QUser.user;
    private final QUserAccountSets qUserAccountSets = QUserAccountSets.userAccountSets;
    private final QAccountSets qAccountSets = QAccountSets.accountSets;
    private final QCheckout qCheckout = QCheckout.checkout;
    private final QAccountSetsTemplate qAccountSetsTemplate = QAccountSetsTemplate.accountSetsTemplate;
    private final QTaxTable qTaxTable = QTaxTable.taxTable;
    private final QTaxReportResult qTaxReportResult = QTaxReportResult.taxReportResult;
    private final QBankLib qBankLib = QBankLib.bankLib;
    /** RPT-FN-01：科目余额取数 */
    private final QBalance qBalance = QBalance.balance;
    private final QSubject qSubject = QSubject.subject;

    /** 自引用：用于在批量方法中走代理调用单条 @Transactional 方法（每条独立事务） */
    @Resource
    @Lazy
    private OutSystem2CRMService self;

    @Value("${static-url.base-url}")
    private String baseUrl;

    @Value("${static-url.img}")
    private String img;
    /**
     * 根据
     * @param province
     * @return
     */
    public List<GetPrivance> getProvinceList(String province,String mobile){
        //1、查询所有省份
        HashMap<String,Object> params = new HashMap<>();

        params.put("provinceName",province);
        params.put("electricityTaxPhone",mobile);
        List<GetPrivance> list = accountSetsService.getProvince(params);
        //2、遍历数据，逐个验证 企业登录状态
        list.forEach(item ->{
            //判断登录：1、查看浏览器对象是否存在，不存在直接返回离线，如果浏览器对象存在，则验证是否有效。
            if(rpaUtil.checkTaxSystemStatus(item.getTaxSystemKey().toString())){
                item.setStatus("onLine");
            }else{
                item.setStatus("offLine");
            }
        });
        return list;
    }

    /**
     * 进行登录
     * @param province
     * @param mobile
     * @param password
     * @return
     */
    public Boolean loginTaxSystem(String taxSystemKey,String province,String mobile,String password){
        return rpaUtil.loginTaxSystem(taxSystemKey,province,mobile,password);
    }

    /**
     * 注销登录
     * @param taxSystemKey
     * @return
     */
    public Boolean logoutTaxSystem(String taxSystemKey){
        return rpaUtil.logoutTaxSystem(taxSystemKey);
    }

    /**
     * 验证码验证
     * @param code
     * @param taxSystemKey
     * @return
     */
    public Boolean checkTaxSystemCode(String code,String taxSystemKey){
        return rpaUtil.checkTaxSystemCode(code,taxSystemKey);
    }

    /**
     * 获取报税条目
     * @param list
     * @return
     */
    public Boolean getTaxDeclareItems(List<GetTaxDeclareItemsReq> list) {
        if(CollUtil.isEmpty(list)){
            return Boolean.FALSE;
        }
        list.forEach(item -> {
            //将获取报税的条目
            TaxDeclareItemsInfoDTO taxDeclareItemsInfoDTO = rpaUtil.getTaxDeclareItems(item.getCreditCode(), item.getProvince() + "*" + item.getElectricityTaxPhone(),item.getAccountSetsId());



            //首先删除已存在任务
            Map<String, Object> taxDtoMap = new HashMap<>();
            taxDtoMap.put("accountSetsId", item.getAccountSetsId());
            taxService.deleteTaxItem(taxDtoMap);
            if(CODE_200.getCode().equals(taxDeclareItemsInfoDTO.getCode())){
                int successCount =0;
                for(int i=0;i<taxDeclareItemsInfoDTO.getData().size();i++){
                    Map<String,String> dto = taxDeclareItemsInfoDTO.getData().get(i);
                    TaxDto taxDto = new TaxDto();
                    taxDto.setAccountSetsId(item.getAccountSetsId());
                    taxDto.setCompanyName(item.getCompanyName());
                    taxDto.setTaxType(dto.get("事项名称"));
                    taxDto.setTaxState(dto.get("标签状态"));
                    taxDto.setTaxEndTime(LocalDate.parse(dto.get("办理期限")));
                    if(dto.get("标签状态").equals("已申报")){
                        successCount++;
                    }
                    taxService.saveTaxItem(taxDto);
                }
                TaxReportResult result = new TaxReportResult();
                result.setAccountSetId(item.getAccountSetsId());
                //改为上月第一天，因为报税期的时间有的时候是17天有的时候是15天，有的时候是27天容易错误
                result.setTaxTime(DateUtils.getFirstDayOfLastMonth());
                result.setImgPath(taxDeclareItemsInfoDTO.getImgUrl());
                result.setAllCount(taxDeclareItemsInfoDTO.getData().size());
                result.setSuccessCount(successCount);
                taxService.insertTaxReportResult(result);

            }else{
                TaxDto taxDto = new TaxDto();
                taxDto.setAccountSetsId(item.getAccountSetsId());
                taxDto.setCompanyName(item.getCompanyName());
                taxDto.setTaxType(taxDeclareItemsInfoDTO.getCode() + "-" + taxDeclareItemsInfoDTO.getMsg());
                taxService.saveTaxItem(taxDto);
            }
        });
        return Boolean.TRUE;
    }

    /**
     * 添加报税任务
     * @param list
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public JsonResult addTaskList(List<AddTaskListReq> list) {
        if(CollUtil.isEmpty(list)){
            return JsonResult.successful();
        }
        //获取当前传入的所有报税条目
        List<TaxTableDTO> mergedList  = list.stream()
                .filter(f -> CollUtil.isNotEmpty(f.getTaxTableDTOList()))
                .map(AddTaskListReq::getTaxTableDTOList)
                .flatMap(List::stream)
                .collect(Collectors.toList());
        if(CollUtil.isEmpty(mergedList)){
            return JsonResult.failure("请先获取报税条目");
        }
        List<String> addTaxTypeList = mergedList.stream().map(TaxTableDTO::getTaxType).distinct().toList();
        //获取所有已配置的报税条目
        List<TaxTableSort> taxTableSortList = bqf.selectFrom(qTaxTableSort).fetch();
        if(CollUtil.isEmpty(taxTableSortList)){
            return JsonResult.failure("请先配置报税条目排序:" + addTaxTypeList);
        }
        List<String> taxTypeList = taxTableSortList.stream().map(TaxTableSort::getTaxType).distinct().toList();
        //判断已配置的报税条目是否包含传入的报税条目
        List<String> checkList = addTaxTypeList.stream().filter(f -> !taxTypeList.contains(f)).toList();
        if(CollUtil.isNotEmpty(checkList)){
            return JsonResult.failure("请先配置报税条目排序:" + checkList);
        }
        Map<String, Integer> taxTableSortMap = taxTableSortList.stream().collect(Collectors.toMap(TaxTableSort::getTaxType, TaxTableSort::getSort));
        list.stream().filter(f -> CollUtil.isNotEmpty(f.getTaxTableDTOList())).forEach(item -> {
            item.getTaxTableDTOList().forEach(tax -> {
                if(tax.getTaxType().startsWith(CODE_201.getCode() + "-")
                        || tax.getTaxType().startsWith(CODE_202.getCode() + "-")
                        || tax.getTaxType().startsWith(CODE_203.getCode() + "-")){
                    return;
                }
                // 创建任务执行记录
                TaskExecuteList taskExecute = new TaskExecuteList();
                taskExecute.setSponsor(item.getElectricityTaxPhone());
                taskExecute.setTaskType(2);
                taskExecute.setTaskStatus("0");
                taskExecute.setTaskExecutionTime(LocalDateTime.now());
                taskExecute.setTaskResult("未执行");
                taskExecute.setProvince(item.getProvince());
                taskExecute.setSort(taxTableSortMap.get(tax.getTaxType()));
                // 构建taskData JSON数据
                JSONObject taskData = new JSONObject();
                taskData.put("companyName", item.getCompanyName());
                taskData.put("creditCode", item.getCreditCode());
                taskData.put("phoneNumber", item.getElectricityTaxPhone());
                taskData.put("electricityTaxPhone", item.getElectricityTaxPhone());
                taskData.put("accountSetsId", item.getAccountSetsId().toString());
                taskData.put("electricityTaxCode", item.getElectricityTaxCode());
                taskData.put("province", item.getProvince());
                taskData.put("vatType", item.getVatType());
                taskData.put("taxType", tax.getTaxType());
                taskData.put("taxId", tax.getTaxId().toString());
                taskExecute.setTaskData(taskData.toJSONString());
                // 保存任务执行记录
                taxService.saveTaskExecuteList(taskExecute);
            });
        });
        return JsonResult.successful();
    }

    /**
     * 获取需要迁移的数据
     * @param id
     * @return
     */
    public List<GetExperienceMigrationDataResp> getExperienceMigrationData(Long id) {
        List<GetExperienceMigrationDataResp> resp = new ArrayList<>();
        if(ObjectUtils.isEmpty(id)){
            return resp;
        }
        //Salary表
        List<Salary> salaryList = bqf.selectFrom(qSalary)
                .where(qSalary.accountSetsId.eq(id)).fetch();
        if(CollUtil.isNotEmpty(salaryList)){
            resp.add(new GetExperienceMigrationDataResp(id, Salary.class.getSimpleName(),
                    JSONObject.toJSONString(salaryList)));
        }
        //SalaryFieldValue表
        List<SalaryFieldValue> salaryFieldValueList = bqf.selectFrom(qSalaryFieldValue)
                .where(qSalaryFieldValue.accountSetsId.eq(id)).fetch();
        if(CollUtil.isNotEmpty(salaryFieldValueList)){
            resp.add(new GetExperienceMigrationDataResp(id, SalaryFieldValue.class.getSimpleName(),
                    JSONObject.toJSONString(salaryFieldValueList)));
        }
        //SalarySocial表
        List<SalarySocial> salarySocialList = bqf.selectFrom(qSalarySocial)
                .where(qSalarySocial.accountSetsId.eq(id)).fetch();
        if(CollUtil.isNotEmpty(salarySocialList)){
            resp.add(new GetExperienceMigrationDataResp(id, SalarySocial.class.getSimpleName(),
                    JSONObject.toJSONString(salarySocialList)));
        }
        //SalarySocialEmployee表
        List<SalarySocialEmployee> salarySocialEmployeeList = bqf.selectFrom(qSalarySocialEmployee)
                .where(qSalarySocialEmployee.accountSetsId.eq(id)).fetch();
        if(CollUtil.isNotEmpty(salarySocialEmployeeList)){
            resp.add(new GetExperienceMigrationDataResp(id, SalarySocialEmployee.class.getSimpleName(),
                    JSONObject.toJSONString(salarySocialEmployeeList)));
        }
        //Employee表
        List<Employee> employeeList = bqf.selectFrom(qEmployee)
                .where(qEmployee.accountSetsId.eq(id)).fetch();
        if(CollUtil.isNotEmpty(employeeList)){
            resp.add(new GetExperienceMigrationDataResp(id, Employee.class.getSimpleName(),
                    JSONObject.toJSONString(employeeList)));
        }
        //BankStatement表
        List<BankStatement> bankStatementList = bqf.selectFrom(qBankStatement)
                .where(qBankStatement.accountSetsId.eq(id)).fetch();
        if(CollUtil.isNotEmpty(bankStatementList)){
            resp.add(new GetExperienceMigrationDataResp(id, BankStatement.class.getSimpleName(),
                    JSONObject.toJSONString(bankStatementList)));
            //BankStatementDetail表
            List<Long> bankBankStatementIdList = bankStatementList.stream().map(BankStatement::getId).toList();
            List<BankStatementDetail> bankStatementDetailList = bqf.selectFrom(qBankStatementDetail)
                    .where(qBankStatementDetail.bankStatementId.in(bankBankStatementIdList)).fetch();
            if(CollUtil.isNotEmpty(bankStatementDetailList)){
                resp.add(new GetExperienceMigrationDataResp(id, BankStatementDetail.class.getSimpleName(),
                        JSONObject.toJSONString(bankStatementDetailList)));
            }
        }
        //FileInfo表
        List<FileInfo> fileList = bqf.selectFrom(qFileInfo)
                .where(qFileInfo.accountSetsId.eq(id)).fetch();
        if(CollUtil.isNotEmpty(fileList)){
            resp.add(new GetExperienceMigrationDataResp(id, FileInfo.class.getSimpleName(),
                    JSONObject.toJSONString(fileList)));
        }
        return resp;
    }

    /**
     * 数据迁移
     * @param map
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean addAccountSetMigrationData(Map<String, Object> map) {
        String jsonArray = map.get("list").toString();
        if(StringUtils.isNotEmpty(jsonArray)){
            List<AddAccountSetMigrationDataDTO> dtoList = JSON.parseArray(jsonArray, AddAccountSetMigrationDataDTO.class);
            dtoList.forEach(item -> {
                AccountSets accountSets = bqf.selectFrom(qAccountSets)
                        .where(qAccountSets.id.eq(item.getAccountSetId()))
                        .fetchFirst();
                if("Salary".equals(item.getEntityName())){
                    List<Salary> entityList = JSON.parseArray(item.getMigrationData(), Salary.class);
                    salaryRepository.saveAll(entityList.stream()
                            .peek(s -> {
                                s.setAccountSetsId(item.getAccountSetId());
                                s.setPeriod(accountSets.getCurrentAccountDate());
                                s.setYear(accountSets.getCurrentAccountDate().get(ChronoField.YEAR));
                                s.setMonth(accountSets.getCurrentAccountDate().get(ChronoField.MONTH_OF_YEAR));
                            }).toList());
                }
                if("SalaryFieldValue".equals(item.getEntityName())){
                    List<SalaryFieldValue> entityList = JSON.parseArray(item.getMigrationData(), SalaryFieldValue.class);
                    salaryFieldValueRepository.saveAll(entityList.stream()
                            .peek(s -> {
                                s.setAccountSetsId(item.getAccountSetId());
                                s.setPeriod(accountSets.getCurrentAccountDate());
                            }).toList());
                }
                // 无线一金模板 没有新增 存在进行修改
                if("SalarySocial".equals(item.getEntityName())){
                    List<SalarySocial> entityList = JSON.parseArray(item.getMigrationData(), SalarySocial.class);
                    entityList.forEach(entity -> {
                        SalarySocial salarySocial = bqf.selectFrom(qSalarySocial)
                                .where(qSalarySocial.accountSetsId.eq(item.getAccountSetId())
                                        .and(qSalarySocial.name.eq(entity.getName()))
                                ).fetchFirst();
                        if(ObjectUtils.isEmpty(salarySocial)){
                            entity.setAccountSetsId(item.getAccountSetId());
                            salarySocialRepository.save(entity);
                        }else{
                            jqf.update(qSalarySocial)
                                    .set(qSalarySocial.depositBase, entity.getDepositBase())
                                    .set(qSalarySocial.companyRatio, entity.getCompanyRatio())
                                    .set(qSalarySocial.companyNum, entity.getCompanyNum())
                                    .set(qSalarySocial.personRatio, entity.getPersonRatio())
                                    .set(qSalarySocial.personNum, entity.getPersonNum())
                                    .where(qSalarySocial.id.eq(salarySocial.getId())
                                            .and(qSalarySocial.accountSetsId.eq(salarySocial.getAccountSetsId()))
                                            .and(qSalarySocial.name.eq(salarySocial.getName()))
                                    )
                                    .execute();
                        }
                    });
                }
                if("SalarySocialEmployee".equals(item.getEntityName())){
                    List<SalarySocialEmployee> entityList = JSON.parseArray(item.getMigrationData(), SalarySocialEmployee.class);
                    salarySocialEmployeeRepository.saveAll(entityList.stream()
                            .peek(s -> s.setAccountSetsId(item.getAccountSetId())).toList());
                }
                if("Employee".equals(item.getEntityName())){
                    List<Employee> entityList = JSON.parseArray(item.getMigrationData(), Employee.class);
                    employeeRepository.saveAll(entityList.stream()
                            .peek(s -> s.setAccountSetsId(item.getAccountSetId())).toList());
                }
                if("BankStatement".equals(item.getEntityName())){
                    List<BankStatement> entityList = JSON.parseArray(item.getMigrationData(), BankStatement.class);
                    bankStatementRepository.saveAll(entityList.stream()
                            .peek(s -> s.setAccountSetsId(item.getAccountSetId())).toList());
                }
                if("BankStatementDetail".equals(item.getEntityName())){
                    List<BankStatementDetail> entityList = JSON.parseArray(item.getMigrationData(), BankStatementDetail.class);
                    bankStatementDetailRepository.saveAll(entityList);
                }
                if("FileInfo".equals(item.getEntityName())){
                    List<FileInfo> entityList = JSON.parseArray(item.getMigrationData(), FileInfo.class);
                    fileInfoRepository.saveAll(entityList.stream()
                            .peek(s -> {
                                s.setAccountSetsId(item.getAccountSetId());
                                s.setAccountSetPeriod(accountSets.getCurrentAccountDate());
                            }).toList());
                }
            });
        }
        return Boolean.TRUE;
    }


    /**
     * 切换默认账套
     * @param accountSetsId
     * @param mobile
     * @return
     */
    public Boolean changeAccountSet4CRM(Long accountSetsId,String mobile){
        try{
            User user = jqf.selectFrom(qUser).where(qUser.mobile.eq(mobile)).fetchFirst();
            jqf.update(qUserAccountSets)
                    .set(qUserAccountSets.current, false)
                    .where(qUserAccountSets.userId.eq(user.getId()))
                    .execute();
            jqf.update(qUserAccountSets)
                    .set(qUserAccountSets.current, true)
                    .where(qUserAccountSets.userId.eq(user.getId()).and(qUserAccountSets.accountSetsId.eq(accountSetsId)))
                    .execute();
            return true;
        }catch (Exception e){
            return false;
        }
    }

    /**
     * 获取账套汇总数据
     * @return
     */
    public GetAccountSummaryDataResp crmGetAccountSummaryData() {
        GetAccountSummaryDataResp resp = new GetAccountSummaryDataResp();
        List<Tuple> tupleList = bqf.selectFrom(qAccountSets)
                .select(qAccountSets.zeroDeclarations, qCheckout.status, qAccountSetsTemplate.vatType)
                .leftJoin(qCheckout).on(qCheckout.accountSetsId.eq(qAccountSets.id).and(qCheckout.checkDate.eq(qAccountSets.currentAccountDate)))
                .leftJoin(qAccountSetsTemplate).on(qAccountSetsTemplate.id.eq(qAccountSets.astId))
                .where(qAccountSets.id.goe(1))
                .fetch();

        if(tupleList.size() > 0){
            resp.setZeroDeclareCount(tupleList.stream().filter(f -> ObjectUtils.isNotEmpty(f.get(qAccountSets.zeroDeclarations)) && f.get(qAccountSets.zeroDeclarations)).count());
            resp.setSmallScaleCount(tupleList.stream().filter(f -> "SMALL_SCALE".equals(f.get(qAccountSetsTemplate.vatType).name())).count());
            resp.setCommonlyCount(tupleList.stream().filter(f -> "COMMONLY".equals(f.get(qAccountSetsTemplate.vatType).name())).count());
            resp.setAccountSetsSum(tupleList.stream().count());
            resp.setCheckOutCount(tupleList.stream().filter(f -> ObjectUtils.isNotEmpty(f.get(qCheckout.status)) && 2 == f.get(qCheckout.status)).count());
            resp.setNoCheckOutCount(tupleList.stream().count() - resp.getCheckOutCount());

            // 报税集合
            List<TaxTable> taxTableList = bqf.selectFrom(qTaxTable)
                    .where(qTaxTable.taxState.eq("未申报"))
                    .fetch();
            if(taxTableList.size() > 0){
                resp.setNoTaxDeclareCount(taxTableList.stream().count());
            }
        }
        return resp;
    }

    /**
     * 获取报税信息
     * @param req
     * @return
     */
    public GetTaxReportResultDataResp crmGetTaxReportResultData(GetCommonReq req) {
        GetTaxReportResultDataResp resp = new GetTaxReportResultDataResp();
        if(ObjectUtils.isEmpty(req.getAccountSetsId()) || StringUtils.isEmpty(req.getPeriod())){
            return resp;
        }
        LocalDate taxTime = DateUtil.timeStrToLocalDate(req.getPeriod(), DateUtil.YYYY_MM_DD);

        TaxReportResult taxReportResult = bqf.selectFrom(qTaxReportResult)
                .where(qTaxReportResult.accountSetId.eq(req.getAccountSetsId())
                        .and(qTaxReportResult.taxTime.eq(taxTime))
                ).fetchFirst();
        if(ObjectUtils.isNotEmpty(taxReportResult)){
            resp.setAllCount(taxReportResult.getAllCount());
            resp.setSuccessCount(taxReportResult.getSuccessCount());
            resp.setPendingCount(taxReportResult.getAllCount() - taxReportResult.getSuccessCount());
            resp.setImgPath(baseUrl + img + taxReportResult.getImgPath());
            List<TaxTable> taxTableList = bqf.selectFrom(qTaxTable)
                    .where(qTaxTable.accountSetsId.eq(req.getAccountSetsId()))
                    .fetch();
            if(CollUtil.isNotEmpty(taxTableList)){
                resp.setTaxTabList(
                        taxTableList.stream().map(item -> {
                            GetTaxTabResp taxTabResp = new GetTaxTabResp();
                            taxTabResp.setTaxId(item.getTaxId());
                            taxTabResp.setTaxState(item.getTaxState());
                            taxTabResp.setTaxType(item.getTaxType());
                            taxTabResp.setTaxEndTime(item.getTaxEndTime());
                            return taxTabResp;
                        }).collect(Collectors.toList())
                );
            }
        }
        return resp;
    }

    /**
     * RPT-FN-01 · CRM 调 financial 取财务报表 + 科目余额（86 §D4 契约）。
     * <p>
     * 4 块合一返：
     * <ul>
     *   <li>{@code zcfz} 资产负债表 ← {@code ReportService.reportData(items, accountSets, "zcfz", localCurrencyId, voucherDate, null)}</li>
     *   <li>{@code lr} 利润表 ← 同上 templateKey=lr</li>
     *   <li>{@code xjll} 现金流量表 ← 同上 templateKey=xjll</li>
     *   <li>{@code subjectBalances} 科目余额表（aux=0 + 本位币）← 直接查 {@code qBalance}</li>
     * </ul>
     * 单张报表计算失败不阻断其他三块，对应字段返空列表（log.warn 记录）。
     * <p>
     * ⚠️ <b>R6 限制</b>：86 §D4 说明 R6 "高新收入占比"非标准报表项，本接口不返；
     * CRM 端 RPT-AI-01 需另出来源（or R6 该维度先 stub）。
     *
     * @param req {@link GetReportReq} 入参 accountSetsId + period(YYYY-MM)
     * @return {@link GetReportResp}；账套/期间非法时返带空 4 块的 resp（不抛错，让上游 graceful）
     */
    public GetReportResp crmGetReport(GetReportReq req) {
        GetReportResp resp = new GetReportResp();
        List<GetReportResp.DataIssue> issues = new ArrayList<>();
        resp.setDataIssues(issues);

        if (req == null || req.getAccountSetsId() == null || StringUtils.isBlank(req.getPeriod())) {
            log.warn("[crmGetReport] 入参非法 req={}", req);
            issues.add(new GetReportResp.DataIssue("overall",
                    GetReportResp.IssueCode.INVALID_PERIOD, "入参非法：accountSetsId / period 不能为空"));
            return resp;
        }
        resp.setAccountSetsId(req.getAccountSetsId());
        resp.setPeriod(req.getPeriod());

        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(req.getAccountSetsId()))
                .fetchFirst();
        if (accountSets == null) {
            log.warn("[crmGetReport] 账套不存在 accountSetsId={}", req.getAccountSetsId());
            issues.add(new GetReportResp.DataIssue("overall",
                    GetReportResp.IssueCode.ACCOUNT_SETS_NOT_FOUND,
                    "账套不存在 accountSetsId=" + req.getAccountSetsId()));
            return resp;
        }

        YearMonth ym;
        LocalDate voucherDate;
        try {
            ym = YearMonth.parse(req.getPeriod());
            voucherDate = ym.atEndOfMonth();
        } catch (Exception e) {
            log.warn("[crmGetReport] period 格式错误 应为 YYYY-MM，传入={}", req.getPeriod());
            issues.add(new GetReportResp.DataIssue("overall",
                    GetReportResp.IssueCode.INVALID_PERIOD,
                    "period 格式错误，应为 YYYY-MM，传入=" + req.getPeriod()));
            return resp;
        }

        // 本位币（OpenApiBalanceController:251 同款用法）
        Long localCurrencyId;
        try {
            localCurrencyId = currencyService.fetchLocal(req.getAccountSetsId()).getId();
        } catch (Exception e) {
            log.warn("[crmGetReport] 账套未设置本位币 accountSetsId={}：{}", req.getAccountSetsId(), e.getMessage());
            issues.add(new GetReportResp.DataIssue("overall",
                    GetReportResp.IssueCode.LOCAL_CURRENCY_NOT_SET,
                    "账套未设置本位币 accountSetsId=" + req.getAccountSetsId()));
            return resp;
        }

        // ── 三表（复用 ReportService.reportData 真实计算）──
        resp.setZcfz(loadReportRows("zcfz", req.getAccountSetsId(), accountSets, localCurrencyId, voucherDate, issues));
        resp.setLr(loadReportRows("lr", req.getAccountSetsId(), accountSets, localCurrencyId, voucherDate, issues));
        resp.setXjll(loadReportRows("xjll", req.getAccountSetsId(), accountSets, localCurrencyId, voucherDate, issues));

        // ── 科目余额（aux=0 + 本位币，参 OpenApiBalanceController.fetchDetailedRows）──
        resp.setSubjectBalances(fetchSubjectBalances(
                req.getAccountSetsId(), ym.getYear(), ym.getMonthValue(), localCurrencyId, issues));

        log.info("[crmGetReport] accountSetsId={} period={} localCurrencyId={} zcfz={} lr={} xjll={} balances={} issues={}",
                req.getAccountSetsId(), req.getPeriod(), localCurrencyId,
                resp.getZcfz().size(), resp.getLr().size(), resp.getXjll().size(),
                resp.getSubjectBalances().size(), issues);
        return resp;
    }

    /**
     * 工单 122 · L1-MULTIPERIOD · 一次取多期科目余额（替 finmaster 116 buildTrends 逐期轮询）。
     * <p>复用 {@link #fetchSubjectBalances}，按 startPeriod~endPeriod 逐月查并合并；数值与逐期一致。
     *
     * @return { accountSetsId, startPeriod, endPeriod, periods:[{period, subjectBalances:[...]}], dataIssues:[...] }
     */
    public Map<String, Object> crmGetMultiPeriodBalance(Long accountSetsId, String startPeriod, String endPeriod) {
        Map<String, Object> resp = new LinkedHashMap<>();
        List<GetReportResp.DataIssue> issues = new ArrayList<>();
        resp.put("accountSetsId", accountSetsId);
        resp.put("startPeriod", startPeriod);
        resp.put("endPeriod", endPeriod);
        resp.put("periods", new ArrayList<>());
        resp.put("dataIssues", issues);

        if (accountSetsId == null || StringUtils.isBlank(startPeriod) || StringUtils.isBlank(endPeriod)) {
            issues.add(new GetReportResp.DataIssue("overall", GetReportResp.IssueCode.INVALID_PERIOD,
                    "accountSetsId / startPeriod / endPeriod 不能为空"));
            return resp;
        }
        YearMonth start, end;
        try {
            start = YearMonth.parse(startPeriod);
            end = YearMonth.parse(endPeriod);
        } catch (Exception e) {
            issues.add(new GetReportResp.DataIssue("overall", GetReportResp.IssueCode.INVALID_PERIOD,
                    "period 格式错误，应为 YYYY-MM，传入 start=" + startPeriod + " end=" + endPeriod));
            return resp;
        }
        if (start.isAfter(end)) {
            issues.add(new GetReportResp.DataIssue("overall", GetReportResp.IssueCode.INVALID_PERIOD,
                    "startPeriod 晚于 endPeriod"));
            return resp;
        }
        if (bqf.selectFrom(qAccountSets).where(qAccountSets.id.eq(accountSetsId)).fetchFirst() == null) {
            issues.add(new GetReportResp.DataIssue("overall", GetReportResp.IssueCode.ACCOUNT_SETS_NOT_FOUND,
                    "账套不存在 accountSetsId=" + accountSetsId));
            return resp;
        }
        Long localCurrencyId;
        try {
            localCurrencyId = currencyService.fetchLocal(accountSetsId).getId();
        } catch (Exception e) {
            issues.add(new GetReportResp.DataIssue("overall", GetReportResp.IssueCode.LOCAL_CURRENCY_NOT_SET,
                    "账套未设置本位币 accountSetsId=" + accountSetsId));
            return resp;
        }

        List<Map<String, Object>> periods = new ArrayList<>();
        for (YearMonth ym = start; !ym.isAfter(end); ym = ym.plusMonths(1)) {
            List<GetReportResp.SubjectBalanceRow> rows =
                    fetchSubjectBalances(accountSetsId, ym.getYear(), ym.getMonthValue(), localCurrencyId, issues);
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("period", ym.toString());
            one.put("subjectBalances", rows);
            periods.add(one);
        }
        resp.put("periods", periods);
        log.info("[crmGetMultiPeriodBalance] accountSetsId={} {}~{} periods={} issues={}",
                accountSetsId, startPeriod, endPeriod, periods.size(), issues.size());
        return resp;
    }

    /**
     * 单张报表计算（失败不阻断其他块，但<b>不再静默吞</b>——把原因写进 {@code issues}）。
     * <ul>
     *   <li>取报表项异常 / 项为空 → {@code REPORT_TEMPLATE_MISSING}（账套未初始化该报表模板）</li>
     *   <li>reportData 异常含"科目" → {@code FORMULA_SUBJECT_MISSING}；其他 → {@code REPORT_CALC_ERROR}</li>
     * </ul>
     */
    private List<ReportItemsDto> loadReportRows(String templateKey, Long accountSetsId,
                                                AccountSets accountSets, Long localCurrencyId,
                                                LocalDate voucherDate, List<GetReportResp.DataIssue> issues) {
        List<ReportItemsDto> items;
        try {
            items = reportService.items(templateKey, accountSetsId, Boolean.FALSE);
        } catch (Exception e) {
            // items 阶段失败：report 为 null 时 getReportItemsDtos 会 NPE → 该账套无该报表模板
            log.warn("[crmGetReport] 模板={} accountSets={} 取报表项失败：{}", templateKey, accountSetsId, e.toString());
            issues.add(new GetReportResp.DataIssue(templateKey,
                    GetReportResp.IssueCode.REPORT_TEMPLATE_MISSING,
                    "账套未配置 " + templateKey + " 报表模板（取报表项异常：" + safeMsg(e) + "）"));
            return Collections.emptyList();
        }
        if (CollUtil.isEmpty(items)) {
            issues.add(new GetReportResp.DataIssue(templateKey,
                    GetReportResp.IssueCode.REPORT_TEMPLATE_MISSING,
                    "账套 " + templateKey + " 报表模板无数据项（reportItems 为空，疑模板未初始化）"));
            return Collections.emptyList();
        }
        try {
            List<ReportItemsDto> rows = reportService.reportData(items, accountSets, templateKey, localCurrencyId, voucherDate, null);
            return rows != null ? rows : Collections.emptyList();
        } catch (Exception e) {
            String msg = safeMsg(e);
            String code = (msg != null && msg.contains("科目"))
                    ? GetReportResp.IssueCode.FORMULA_SUBJECT_MISSING
                    : GetReportResp.IssueCode.REPORT_CALC_ERROR;
            log.warn("[crmGetReport] 模板={} accountSets={} reportData 计算失败：{}", templateKey, accountSetsId, e.toString());
            issues.add(new GetReportResp.DataIssue(templateKey, code, templateKey + " 计算失败：" + msg));
            return Collections.emptyList();
        }
    }

    /**
     * 科目余额查询（detailed 模式：aux=0 + 本位币 + 本期，按 subjectCode 升序）。
     * 同 {@code OpenApiBalanceController.fetchDetailedRows}。
     * <p>
     * <b>RPT-FN-01-FIX</b>：空结果时跑探测查询区分"该期真无余额（疑未结账）"与
     * "有余额但币种/aux 口径不匹配"，把原因写进 {@code issues}，不再静默返空。
     */
    private List<GetReportResp.SubjectBalanceRow> fetchSubjectBalances(Long accountSetsId, int year, int month,
                                                                       Long localCurrencyId, List<GetReportResp.DataIssue> issues) {
        BooleanBuilder where = new BooleanBuilder();
        where.and(qBalance.accountSetsId.eq(accountSetsId));
        where.and(qBalance.year.eq(year));
        where.and(qBalance.period.eq(month));
        where.and(qBalance.auxiliaryDetailId.eq(0L));
        where.and(qBalance.currencyId.eq(localCurrencyId));

        List<Tuple> tuples = bqf.selectFrom(qBalance)
                .select(qBalance, qSubject.code, qSubject.name, qSubject.balanceDirection)
                .leftJoin(qSubject).on(qSubject.id.eq(qBalance.subjectId)
                        .and(qSubject.accountSetsId.eq(qBalance.accountSetsId)))
                .where(where)
                .orderBy(qSubject.code.asc())
                .fetch();

        List<GetReportResp.SubjectBalanceRow> rows = new ArrayList<>(tuples.size());
        for (Tuple t : tuples) {
            Balance b = t.get(qBalance);
            if (b == null) continue;
            GetReportResp.SubjectBalanceRow r = new GetReportResp.SubjectBalanceRow();
            r.setSubjectId(b.getSubjectId());
            r.setSubjectCode(t.get(qSubject.code));
            r.setSubjectName(t.get(qSubject.name));
            r.setBalanceDirection(t.get(qSubject.balanceDirection));
            r.setBeginBalance(orZero(b.getBeginBalance()));
            r.setEndBalance(orZero(b.getEndBalance()));
            r.setCurrentMonthDebit(orZero(b.getDebit()));
            r.setCurrentMonthCredit(orZero(b.getCredit()));
            r.setYearTotalDebit(orZero(b.getYearTotalDebit()));
            r.setYearTotalCredit(orZero(b.getYearTotalCredit()));
            rows.add(r);
        }

        if (rows.isEmpty()) {
            diagnoseEmptyBalance(accountSetsId, year, month, localCurrencyId, issues);
        }
        return rows;
    }

    /**
     * RPT-FN-01-FIX · 科目余额为空时探测根因：放开 currency/aux 过滤，看该期实际落库情况。
     */
    private void diagnoseEmptyBalance(Long accountSetsId, int year, int month,
                                      Long localCurrencyId, List<GetReportResp.DataIssue> issues) {
        try {
            List<Tuple> probe = bqf.selectFrom(qBalance)
                    .select(qBalance.currencyId, qBalance.auxiliaryDetailId)
                    .where(qBalance.accountSetsId.eq(accountSetsId)
                            .and(qBalance.year.eq(year))
                            .and(qBalance.period.eq(month)))
                    .fetch();

            if (probe.isEmpty()) {
                // 该期余额表无任何行 —— 疑未结账 / 该期无数据
                String checkoutInfo = probeCheckoutStatus(accountSetsId, year, month);
                issues.add(new GetReportResp.DataIssue("subjectBalances",
                        GetReportResp.IssueCode.NO_BALANCE_MAYBE_NOT_CHECKED_OUT,
                        String.format("账套 %d %d-%02d 余额表(fxy_financial_balance)无任何行，疑未结账或该期无数据；%s",
                                accountSetsId, year, month, checkoutInfo)));
            } else {
                // 有行但被 currency/aux 过滤掉 —— 落库口径不一致
                Set<Long> currencyIds = new LinkedHashSet<>();
                long aux0Count = 0;
                for (Tuple t : probe) {
                    Long cid = t.get(qBalance.currencyId);
                    Long aux = t.get(qBalance.auxiliaryDetailId);
                    if (cid != null) currencyIds.add(cid);
                    if (aux != null && aux == 0L) aux0Count++;
                }
                issues.add(new GetReportResp.DataIssue("subjectBalances",
                        GetReportResp.IssueCode.BALANCE_CURRENCY_OR_AUX_NO_MATCH,
                        String.format("账套 %d %d-%02d 余额表有 %d 行，但 currencyId=%d + aux=0 口径无匹配；" +
                                        "实际落库 currencyIds=%s，aux=0 行数=%d（请对齐本位币/辅助核算口径）",
                                accountSetsId, year, month, probe.size(), localCurrencyId, currencyIds, aux0Count)));
            }
        } catch (Exception e) {
            log.warn("[crmGetReport] 科目余额探测失败 accountSets={} {}-{}：{}", accountSetsId, year, month, e.toString());
            issues.add(new GetReportResp.DataIssue("subjectBalances",
                    GetReportResp.IssueCode.REPORT_CALC_ERROR, "科目余额探测异常：" + safeMsg(e)));
        }
    }

    /** 探测该期结账状态（best-effort，失败不影响主流程）。status==2 视为已结账（与 crmGetAccountSummaryData 一致）。 */
    private String probeCheckoutStatus(Long accountSetsId, int year, int month) {
        try {
            LocalDate ms = LocalDate.of(year, month, 1);
            LocalDate me = ms.withDayOfMonth(ms.lengthOfMonth());
            Integer status = bqf.selectFrom(qCheckout)
                    .select(qCheckout.status)
                    .where(qCheckout.accountSetsId.eq(accountSetsId)
                            .and(qCheckout.checkDate.between(ms, me)))
                    .fetchFirst();
            if (status == null) return "该期无结账记录(fxy_financial_checkout)";
            return "该期结账状态 status=" + status + (status == 2 ? "(已结账)" : "(未结账/进行中)");
        } catch (Exception e) {
            return "结账状态探测失败：" + safeMsg(e);
        }
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** 异常 message 安全取值（含类名兜底，避免 NPE message 为 null 时丢类型信息） */
    private static String safeMsg(Throwable e) {
        if (e == null) return "null";
        String m = e.getMessage();
        return (m != null && !m.isBlank()) ? m : e.getClass().getSimpleName();
    }

    /**
     * 修改账套归属人
     * @param req
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean updateAccountSetsUser(UpdateAccountSetsUserReq req) {
        if(StringUtils.isEmpty(req.getMobile()) || StringUtils.isEmpty(req.getPreMobile()) || StringUtils.isEmpty(req.getCreditCode())){
            return Boolean.FALSE;
        }
        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.creditCode.eq(req.getCreditCode()))
                .fetchFirst();
        if(Objects.isNull(accountSets)){
            return Boolean.FALSE;
        }

        User user = bqf.selectFrom(qUser)
                .where(qUser.mobile.eq(req.getMobile()))
                .fetchFirst();
        User preUser = bqf.selectFrom(qUser)
                .where(qUser.mobile.eq(req.getPreMobile()))
                .fetchFirst();
        if(Objects.isNull(user) || Objects.isNull(preUser)){
            return Boolean.FALSE;
        }

        jqf.update(qUserAccountSets)
                .set(qUserAccountSets.userId, user.getId())
                .where(qUserAccountSets.accountSetsId.eq(accountSets.getId())
                        .and(qUserAccountSets.userId.eq(preUser.getId())))
                .execute();
        return Boolean.TRUE;
    }

    /**
     * 账套停用
     * @param creditCode
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public Boolean deactivated(String creditCode) {
        List<String> creditCodeList = Arrays.asList(StringUtils.split(creditCode, ","));
        jqf.update(qAccountSets)
                .set(qAccountSets.deactivated, Boolean.TRUE)
                .where(qAccountSets.creditCode.in(creditCodeList))
                .execute();
        return Boolean.TRUE;
    }
    /**
     * 查询数据库中的验证码口令，存在于管理员账号里面
     */
     public String getVerifyCode() {
         //这里先设19554509313为管理员
         Long adminUserId = bqf.selectFrom(qUser).select(qUser.id).where(qUser.mobile.eq("19554509313")).fetchFirst();
        return jqf.selectFrom(qUser).select(qUser.verifyCode)
                .where(qUser.id.eq(adminUserId)).fetchFirst();
    }

    private static final java.util.regex.Pattern ID_CARD_PATTERN =
            java.util.regex.Pattern.compile("^\\d{15}$|^\\d{17}[\\dXx]$");

    /**
     * 对外添加员工接口：校验入参 + 查账套，委托 {@link #doAddEmployee} 执行事务写入。
     * 遇到员工编码并发冲突（uc_code_accountSetsId）时自动重试一次。
     */
    public Map<String, Object> addEmployee(String creditCode, AddEmployeeReq req) {
        log.info("[addEmployee] 入参: creditCode={}, reqJson={}", creditCode, JSON.toJSONString(req));
        Map<String, Object> result = new HashMap<>();

        if (StringUtils.isEmpty(creditCode)) {
            result.put("success", false);
            result.put("msg", "税号不能为空");
            return result;
        }
        String validErr = validateReq(req);
        if (validErr != null) {
            result.put("success", false);
            result.put("msg", validErr);
            return result;
        }
        req.getEmployee().setIdCard(req.getEmployee().getIdCard().trim());

        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.creditCode.eq(creditCode))
                .fetchFirst();
        if (Objects.isNull(accountSets)) {
            result.put("success", false);
            result.put("msg", "未找到对应税号的账套：" + creditCode);
            return result;
        }

        try {
            return self.doAddEmployee(accountSets, req);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            if (isCodeConstraintViolation(e)) {
                log.warn("[addEmployee] 员工编码冲突，重试: creditCode={}, idCard={}",
                        creditCode, req.getEmployee().getIdCard());
                return self.doAddEmployee(accountSets, req);
            }
            log.error("[addEmployee] 数据约束冲突: creditCode={}", creditCode, e);
            result.put("success", false);
            result.put("msg", "数据约束冲突：" + e.getMostSpecificCause().getMessage());
            return result;
        }
    }

    /**
     * 校验单条员工请求：员工/身份证/姓名非空，身份证格式合法。
     * 通过返回 null，失败返回中文错误信息。
     */
    private String validateReq(AddEmployeeReq req) {
        if (req == null || req.getEmployee() == null) return "员工信息不能为空";
        Employee emp = req.getEmployee();
        if (StringUtils.isEmpty(emp.getIdCard())) return "员工身份证号不能为空";
        if (StringUtils.isEmpty(emp.getName()))   return "员工姓名不能为空";
        if (!ID_CARD_PATTERN.matcher(emp.getIdCard().trim()).matches())
            return "身份证号格式错误：" + emp.getIdCard().trim();
        return null;
    }

    /**
     * 核心员工写入事务：幂等去重 → 生成编码 → 解析性别/出生日期 → 入库 → 写工资。
     * 通过 self 代理调用，每次调用是独立事务；供 addEmployee / addEmployees 共用。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> doAddEmployee(AccountSets accountSets, AddEmployeeReq req) {
        Employee employee = req.getEmployee();
        Map<String, Object> result = new HashMap<>();

        // 幂等：同账套下按身份证号去重
        Long existsId = bqf.selectFrom(qEmployee)
                .select(qEmployee.id)
                .where(qEmployee.accountSetsId.eq(accountSets.getId())
                        .and(qEmployee.idCard.eq(employee.getIdCard())))
                .fetchFirst();
        if (Objects.nonNull(existsId)) {
            result.put("success", true);
            result.put("skipped", true);
            result.put("employeeId", existsId);
            result.put("msg", "该身份证号员工已存在，已跳过");
            return result;
        }

        // 后端控制主键与账套关联
        employee.setId(null);
        employee.setAccountSetsId(accountSets.getId());

        // 编码：始终由后端生成（避免调用方传入造成并发冲突或编码不连续）
        employee.setCode(employeeService.getEmployeeCode(accountSets.getId()));

        // 性别：未传则从身份证号解析（18 位看第 17 位、15 位看第 15 位，奇男偶女）
        if (employee.getGender() == null) {
            Gender parsedGender = parseGenderFromIdCard(employee.getIdCard());
            if (parsedGender != null) {
                employee.setGender(parsedGender);
            }
        }

        // 顶层快捷字段回填（仅当 employee 内对应字段为空时）
        if (employee.getBirthDate() == null && req.getBirthDate() != null) {
            employee.setBirthDate(req.getBirthDate());
        }
        if (employee.getOnboardingDate() == null && req.getOnboardingDate() != null) {
            employee.setOnboardingDate(req.getOnboardingDate());
        }
        // 出生日期仍为空：从身份证号解析
        if (employee.getBirthDate() == null) {
            LocalDate parsed = parseBirthDateFromIdCard(employee.getIdCard());
            if (parsed != null) {
                employee.setBirthDate(parsed);
            }
        }
        // 入职日期仍为空：默认当年 1 月 1 日
        if (employee.getOnboardingDate() == null) {
            employee.setOnboardingDate(LocalDate.now().withDayOfYear(1));
        }

        employee = employeeRepository.saveAndFlush(employee);
        if (employee.getId() == null) {
            throw new IllegalStateException("员工保存后未生成主键，idCard=" + employee.getIdCard());
        }

        Map<String, Object> salaryResult = saveCurrentPeriodSalary(accountSets, employee, req.getPositionSalary());

        result.put("success", true);
        result.put("skipped", false);
        result.put("employeeId", employee.getId());
        result.put("accountSetsId", accountSets.getId());
        if (salaryResult != null) {
            result.put("salary", salaryResult);
        }
        log.info("[doAddEmployee] 完成: idCard={}, employeeId={}, salary={}",
                employee.getIdCard(), employee.getId(), salaryResult);
        return result;
    }

    private boolean isCodeConstraintViolation(org.springframework.dao.DataIntegrityViolationException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains("uc_code_accountSetsId");
    }

    /**
     * 从身份证号解析出生日期。
     * <ul>
     *   <li>18 位身份证：第 7-14 位为 yyyyMMdd</li>
     *   <li>15 位身份证：第 7-12 位为 yyMMdd（年份补 19xx 前缀）</li>
     * </ul>
     * 解析失败返回 null，调用方按"未提供"处理。
     */
    private LocalDate parseBirthDateFromIdCard(String idCard) {
        if (idCard == null) return null;
        String s = idCard.trim();
        try {
            String y, m, d;
            if (s.length() == 18) {
                y = s.substring(6, 10);
                m = s.substring(10, 12);
                d = s.substring(12, 14);
            } else if (s.length() == 15) {
                y = "19" + s.substring(6, 8);
                m = s.substring(8, 10);
                d = s.substring(10, 12);
            } else {
                return null;
            }
            return LocalDate.of(Integer.parseInt(y), Integer.parseInt(m), Integer.parseInt(d));
        } catch (Exception e) {
            log.warn("[addEmployee] 身份证号解析出生日期失败: idCard={}", idCard);
            return null;
        }
    }

    /**
     * 从身份证号解析性别：18 位看第 17 位、15 位看第 15 位，奇男偶女。解析失败返回 null。
     */
    private Gender parseGenderFromIdCard(String idCard) {
        if (idCard == null) return null;
        String s = idCard.trim();
        try {
            char c;
            if (s.length() == 18) {
                c = s.charAt(16);
            } else if (s.length() == 15) {
                c = s.charAt(14);
            } else {
                return null;
            }
            if (!Character.isDigit(c)) return null;
            return (c - '0') % 2 == 1 ? Gender.男 : Gender.女;
        } catch (Exception e) {
            log.warn("[addEmployee] 身份证号解析性别失败: idCard={}", idCard);
            return null;
        }
    }

    /**
     * 对外批量添加员工接口：提前查账套（N 条共用），账套不存在整批快速失败；
     * 每条独立事务，单条失败不影响其他条；编码冲突自动重试一次；
     * 返回结果按入参顺序列出每条的新增/跳过/失败状态。
     */
    public Map<String, Object> addEmployees(String creditCode, List<AddEmployeeReq> reqList) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (StringUtils.isEmpty(creditCode)) {
            result.put("success", false);
            result.put("msg", "税号不能为空");
            return result;
        }
        if (CollUtil.isEmpty(reqList)) {
            result.put("success", false);
            result.put("msg", "员工列表不能为空");
            return result;
        }

        // 提前查账套，N 条共用，账套不存在则整批快速失败
        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.creditCode.eq(creditCode))
                .fetchFirst();
        if (Objects.isNull(accountSets)) {
            result.put("success", false);
            result.put("msg", "未找到对应税号的账套：" + creditCode);
            return result;
        }

        int total = reqList.size();
        int created = 0, skipped = 0, failed = 0;
        List<Map<String, Object>> items = new ArrayList<>();

        for (int i = 0; i < total; i++) {
            AddEmployeeReq req = reqList.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i);
            String idCard = (req != null && req.getEmployee() != null) ? req.getEmployee().getIdCard() : null;
            item.put("idCard", idCard);

            // 单条入参校验
            String validErr = validateReq(req);
            if (validErr != null) {
                failed++;
                item.put("success", false);
                item.put("msg", validErr);
                items.add(item);
                continue;
            }
            req.getEmployee().setIdCard(req.getEmployee().getIdCard().trim());

            try {
                Map<String, Object> r = self.doAddEmployee(accountSets, req);
                item.putAll(r);
                if (Boolean.TRUE.equals(r.get("success"))) {
                    if (Boolean.TRUE.equals(r.get("skipped"))) skipped++;
                    else created++;
                } else {
                    failed++;
                }
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                if (isCodeConstraintViolation(e)) {
                    log.warn("[addEmployees] 第{}条编码冲突，重试: idCard={}", i, idCard);
                    try {
                        Map<String, Object> r = self.doAddEmployee(accountSets, req);
                        item.putAll(r);
                        if (Boolean.TRUE.equals(r.get("success"))) {
                            if (Boolean.TRUE.equals(r.get("skipped"))) skipped++;
                            else created++;
                        } else {
                            failed++;
                        }
                    } catch (Exception retryEx) {
                        failed++;
                        item.put("success", false);
                        item.put("msg", retryEx.getMessage());
                        log.error("[addEmployees] 第{}条重试仍失败: idCard={}", i, idCard, retryEx);
                    }
                } else {
                    failed++;
                    item.put("success", false);
                    item.put("msg", e.getMostSpecificCause().getMessage());
                    log.error("[addEmployees] 第{}条数据约束冲突: idCard={}", i, idCard, e);
                }
            } catch (Exception e) {
                failed++;
                item.put("success", false);
                item.put("msg", e.getMessage());
                log.error("[addEmployees] 第{}条失败: creditCode={}, idCard={}", i, creditCode, idCard, e);
            }
            items.add(item);
        }

        result.put("success", true);
        result.put("total", total);
        result.put("created", created);
        result.put("skipped", skipped);
        result.put("failed", failed);
        result.put("items", items);
        return result;
    }

    /** 工资字段名：岗位工资（CRM 对接固定字段） */
    private static final String SALARY_FIELD_POSITION = "岗位工资";

    /**
     * 把员工的当期"岗位工资"写入数据库（不进入复核流程，state 保持 false）。
     * <ul>
     *   <li>账期取自 AccountSets.currentAccountDate（无则取今天的当月 1 日）</li>
     *   <li>已存在 Salary 则复用，否则新建（state=false）</li>
     *   <li>仅写入 SalaryField「岗位工资」一项；该字段不存在时返回未配置提示并跳过</li>
     * </ul>
     *
     * @return 处理结果摘要；若 positionSalary 为 null 则返回 null
     */
    private Map<String, Object> saveCurrentPeriodSalary(AccountSets accountSets, Employee employee,
                                                        BigDecimal positionSalary) {
        if (positionSalary == null) {
            return null;
        }
        Long accountSetsId = accountSets.getId();
        Map<String, Object> ret = new HashMap<>();

        // 1. 账期：取账套当前账期的当月 1 日
        LocalDate base = accountSets.getCurrentAccountDate() != null
                ? accountSets.getCurrentAccountDate() : LocalDate.now();
        LocalDate period = base.withDayOfMonth(1);
        ret.put("period", period.toString());

        // 2. 反查"岗位工资"字段定义
        SalaryField positionField = bqf.selectFrom(qSalaryField)
                .where(qSalaryField.accountSetsId.eq(accountSetsId)
                        .and(qSalaryField.fieldName.eq(SALARY_FIELD_POSITION)))
                .fetchFirst();
        if (positionField == null) {
            ret.put("salarySkipped", true);
            ret.put("msg", "该账套未配置「" + SALARY_FIELD_POSITION + "」字段，工资未写入");
            return ret;
        }

        // 3. 复用或新建当期 Salary
        Salary salary = bqf.selectFrom(qSalary)
                .where(qSalary.accountSetsId.eq(accountSetsId)
                        .and(qSalary.period.eq(period)))
                .fetchFirst();
        if (salary == null) {
            List<SalaryField> fields = bqf.selectFrom(qSalaryField)
                    .where(qSalaryField.accountSetsId.eq(accountSetsId))
                    .orderBy(qSalaryField.weight.asc())
                    .fetch();
            salary = new Salary();
            salary.setAccountSetsId(accountSetsId);
            salary.setPeriod(period);
            salary.setYear(period.getYear());
            salary.setMonth(period.getMonthValue());
            salary.setState(false); // 不复核
            salary.setSalaryFields(fields);
            salary.setFieldValue(new FieldValue());
            salary = salaryRepository.saveAndFlush(salary); // 立即刷库，保证 id 已生成
        }
        if (salary.getId() == null) {
            throw new IllegalStateException("Salary 保存后未生成主键 id，accountSetsId=" + accountSetsId + ", period=" + period);
        }
        ret.put("salaryId", salary.getId());

        // 4. 同员工同账期已存在则跳过
        Long existSfvId = bqf.selectFrom(qSalaryFieldValue)
                .select(qSalaryFieldValue.id)
                .where(qSalaryFieldValue.accountSetsId.eq(accountSetsId)
                        .and(qSalaryFieldValue.salaryId.eq(salary.getId()))
                        .and(qSalaryFieldValue.empId.eq(employee.getId()))
                        .and(qSalaryFieldValue.period.eq(period)))
                .fetchFirst();
        if (Objects.nonNull(existSfvId)) {
            ret.put("salarySkipped", true);
            ret.put("salaryFieldValueId", existSfvId);
            ret.put("msg", "该员工本期工资记录已存在，已跳过");
            return ret;
        }

        // 5. 仅写入「岗位工资」一项
        FieldValue fv = new FieldValue();
        fv.put(positionField.getId().toString(),
                new cn.gson.financial.kernel.model.bean.Value(positionField.getFieldName(), positionSalary));

        SalaryFieldValue sfv = new SalaryFieldValue();
        sfv.setAccountSetsId(accountSetsId);
        sfv.setSalaryId(salary.getId());
        sfv.setEmpId(employee.getId());
        sfv.setPeriod(period);
        sfv.setFieldValue(fv);
        sfv = salaryFieldValueRepository.save(sfv); // id 由 CosIdGenerator 自动生成

        ret.put("salarySkipped", false);
        ret.put("salaryFieldValueId", sfv.getId());
        ret.put("positionSalary", positionSalary);
        return ret;
    }

    /**
     * 对外更新账套接口：根据税号定位账套，将请求体中非空字段覆盖到现有账套。
     * 字段集合与 createAccountSet 一致（即整个 AccountSets 实体）。
     * 主键、税号本身、创建时间、创建人不允许通过该接口修改。
     *
     * @param entity 入参 AccountSets，必须包含 creditCode 用于反查
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateAccountSet(AccountSets entity) {
        Map<String, Object> result = new HashMap<>();
        if (entity == null || StringUtils.isEmpty(entity.getCreditCode())) {
            result.put("success", false);
            result.put("msg", "税号不能为空");
            return result;
        }

        AccountSets exists = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.creditCode.eq(entity.getCreditCode()))
                .fetchFirst();
        if (Objects.isNull(exists)) {
            result.put("success", false);
            result.put("msg", "未找到对应税号的账套：" + entity.getCreditCode());
            return result;
        }

        // 仅复制 caller 显式传入的非空字段，未传字段保持原值；
        // 同时屏蔽不允许通过外部接口修改的列。
        CopyOptions opts = CopyOptions.create()
                .setIgnoreNullValue(true)
                .setIgnoreProperties("id", "creditCode", "createDate", "creatorId");
        BeanUtil.copyProperties(entity, exists, opts);

        // enableDate 变更时同步刷新 currentAccountDate（与 createAccountSet 行为对齐）
        if (entity.getEnableDate() != null) {
            exists.setCurrentAccountDate(entity.getEnableDate());
        }

        // 直接走 JPA 持久化，避免命中 accountSetsService.save 的"创建"专用唯一性校验
        accountSetsRepository.save(exists);

        result.put("success", true);
        result.put("accountSetsId", exists.getId());
        return result;
    }

    /**
     * 对外修改账套截止日期接口：根据税号定位账套，更新 effectiveDate（有效/截止日期）。
     *
     * @param creditCode    统一社会信用代码（税号）
     * @param effectiveDate 截止日期（有效日期）
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateAccountSetEffectiveDate(String creditCode, LocalDate effectiveDate) {
        Map<String, Object> result = new HashMap<>();
        if (StringUtils.isEmpty(creditCode)) {
            result.put("success", false);
            result.put("msg", "税号不能为空");
            return result;
        }
        if (effectiveDate == null) {
            result.put("success", false);
            result.put("msg", "截止日期不能为空");
            return result;
        }

        long affected = jqf.update(qAccountSets)
                .set(qAccountSets.effectiveDate, effectiveDate)
                .where(qAccountSets.creditCode.eq(creditCode))
                .execute();

        if (affected == 0) {
            result.put("success", false);
            result.put("msg", "未找到对应税号的账套：" + creditCode);
            return result;
        }

        result.put("success", true);
        result.put("affected", affected);
        result.put("effectiveDate", effectiveDate.toString());
        return result;
    }

    /**
     * 获取公共银行库
     * @param bankCode
     * @param bankName
     * @return
     */
    public List<BankLibDto> getBankLibList(String bankCode, String bankName) {

        List<BankLib> bankLibList = bqf.selectFrom(qBankLib)
                .where(qBankLib.isDelete.eq(0)
                        .and(qBankLib.enabled.eq(1))
                        .and(StringUtils.isEmpty(bankCode) ? null : qBankLib.bankCode.eq(bankCode))
                        .and(StringUtils.isEmpty(bankName) ? null : qBankLib.bankName.like("%" + bankName + "%"))
                )
                .orderBy(qBankLib.enabled.desc(), qBankLib.bankCode.asc(), qBankLib.id.desc())
                .fetch();
        if(ObjectUtils.isEmpty(bankLibList)){
            return Collections.emptyList();
        }
        return bankLibList.stream().map(item -> {
            BankLibDto dto = CopierCopyUtils.convertTo(item, BankLibDto.class);
            return dto;
        }).toList();
    }

    /**
     * 根据用户税号获取账套ID
     *
     * @param creditCode
     * @param creatorId
     * @return
     */
    @Transactional(rollbackFor = Exception.class)
    public AccountSets getAccountSetsId(String creditCode, Long creatorId) {
        AccountSets accountSets = null;
        if (StringUtils.isNotEmpty(creditCode)) {
            accountSets = bqf.selectFrom(qAccountSets)
                    .select(qAccountSets)
                    .where(qAccountSets.creditCode.eq(creditCode)).fetchFirst();
        }
        if (accountSets == null) {
            return null;
        }
        //判断一下这个账套是否是停用状态如果是停用状态则改为启用，然后再查询一下这个账套下是否有这个创建人的权限，如果没有则需要为这个创建人添加权限
        if (Boolean.TRUE.equals(accountSets.getDeactivated())) {
            jqf.update(qAccountSets)
                    .set(qAccountSets.deactivated, Boolean.FALSE)
                    .where(qAccountSets.id.eq(accountSets.getId()))
                    .execute();
            accountSets.setDeactivated(false);
        }
        //查询一下这个账套下是否有这个创建人的权限，如果没有则需要为这个用户也就是creatorId添加权限,但是不要修改当前账套的创建人
        if (creatorId != null) {
            boolean related = userAccountSetsRepository.selectCount(accountSets.getId(), creatorId) > 0;
            if (!related) {
                Role role = roleService.getAstAdmin(accountSets.getId());
                UserAccountSets userAccountSets = new UserAccountSets();
                userAccountSets.setAccountSetsId(accountSets.getId());
                userAccountSets.setUserId(creatorId);
                userAccountSets.setRoleId(role.getId());
                // 仅为 creatorId 补建权限，不改动当前账套的创建人归属
                userAccountSets.setCreator(false);
                userAccountSets.setCurrent(false);
                userAccountSetsRepository.save(userAccountSets);
            }
        }
        return accountSets;
    }

}
