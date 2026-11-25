package cn.gson.financial.kernel.service;

import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.FinancialProcessDTO;
import cn.gson.financial.kernel.model.entity.*;
import cn.hutool.core.bean.BeanUtil;
import com.querydsl.core.BooleanBuilder;
import jakarta.annotation.Resource;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FinancialProcessService extends AbsService {

    private final QFinancialProcess qFinancialProcess = QFinancialProcess.financialProcess;
    private final QTimedTaskInvoice qTimedTaskInvoice = QTimedTaskInvoice.timedTaskInvoice;
    private final QAccountSets qAccountSets = QAccountSets.accountSets;
    private final QUserAccountSets qUserAccountSets = QUserAccountSets.userAccountSets;

    @Resource
    @Lazy
    private AccountSetsService accountSetsService;

    @Resource
    @Lazy
    private RoleService roleService;
    public Map<String, Object> getFinancialProcess(List<AccountSets> accountSetsList, FinancialProcessDTO financialProcessConditional) {
        if (accountSetsList == null || accountSetsList.isEmpty()) {
            return null;
        }
        //遍历用户的所有账套的id
        List<Long> ids = accountSetsList.stream()
                .map(AccountSets::getId)
                .collect(Collectors.toList());
        //根据查询用户所选账期的已有记载的任务流程状态
        List<FinancialProcess> financialProcessList = bqf
                .selectFrom(qFinancialProcess)
                .where(qFinancialProcess.accountSetsId.in(ids).and(qFinancialProcess.accountingPeriod.startsWith(financialProcessConditional.getAccountingPeriod())))
                .fetch();
        //将任务将查到的任务变成map存放起来
        Map<Long, List<FinancialProcess>> financialProcessMap = financialProcessList.stream()
                .collect(Collectors.groupingBy(FinancialProcess::getAccountSetsId));
        //查询该账套下的有失败的任务
        List<TimedTaskInvoice> TimedTaskInvoiceList = new ArrayList<>();
        if (financialProcessConditional.getAccountingPeriod() != null && !financialProcessConditional.getAccountingPeriod().trim().isEmpty()) {
            // 解析会计期间，支持 "2025-09" 或 "2025-09-01" 格式
            String period = financialProcessConditional.getAccountingPeriod();
            if (period.length() >= 7) {
                String yearMonth = period.substring(0, 7); // 提取 "2025-09" 部分
                String[] parts = yearMonth.split("-");
                if (parts.length == 2) {
                    try {
                        int year = Integer.parseInt(parts[0]);
                        int month = Integer.parseInt(parts[1]);

                        TimedTaskInvoiceList = jqf.selectFrom(qTimedTaskInvoice)
                                .where(qTimedTaskInvoice.result.eq(1)
                                        .and(qTimedTaskInvoice.checkDate.year().eq(year))
                                        .and(qTimedTaskInvoice.checkDate.month().eq(month)))
                                .fetch();
                    } catch (NumberFormatException e) {
                        // 如果解析失败，返回空列表
                        TimedTaskInvoiceList = new ArrayList<>();
                    }
                }
            }
        }
        //查询失败的的原因，分布在该账套下
        Map<Long,List<TimedTaskInvoice>> timedTaskInvoiceMap = TimedTaskInvoiceList.stream()
                .collect(Collectors.groupingBy(TimedTaskInvoice::getAccountSetsId));

        //遍历用户的账套，然后根据map中的accountSetsId来匹配任务状态
        List<FinancialProcessDTO> financialProcessDTOList  = accountSetsList.stream()
                .filter(accountSets -> accountSets.getDeactivated() == null || !accountSets.getDeactivated())
                .map(accountSets -> {
                    FinancialProcessDTO financialProcessDTO = new FinancialProcessDTO();
                    BeanUtils.copyProperties(accountSets, financialProcessDTO);
                    //如果能查询到相应的任务状态
                    if (financialProcessMap.containsKey(accountSets.getId())) {
                        FinancialProcess financialProcess = financialProcessMap.get(accountSets.getId()).get(0);
                        //判断每个字段是否为空，为空则设置默认值
                        if (financialProcess.getIsTicketObtained() == null) {
                            financialProcess.setIsTicketObtained("否");
                        }
                        if (financialProcess.getIsTaxItemCompleted() == null) {
                            financialProcess.setIsTaxItemCompleted("否");
                        }
                        if (financialProcess.getIsAccountingDone() == null) {
                            financialProcess.setIsAccountingDone("否");
                        }
                        if (financialProcess.getIsTaxFilingCompleted() == null) {
                            financialProcess.setIsTaxFilingCompleted("否");
                        }
                        if (financialProcess.getIsPayrollProcessed() == null) {
                            financialProcess.setIsPayrollProcessed("否");
                        }
                        if (financialProcess.getIsPersonnelInfoSynced() == null) {
                            financialProcess.setIsPersonnelInfoSynced("否");
                        }
                        if (financialProcess.getIsIndividualTaxCompleted() == null) {
                            financialProcess.setIsIndividualTaxCompleted("否");
                        }
                        if (financialProcess.getAccountingPeriod() == null) {
                            financialProcess.setAccountingPeriod(financialProcessConditional.getAccountingPeriod());
                        }else{
                            financialProcess.setAccountingPeriod(financialProcessConditional.getAccountingPeriod().substring(0, 7));
                        }
                        if (financialProcess.getAccountSetsId() == null) {
                            financialProcess.setAccountSetsId(accountSets.getId());
                        }
                        BeanUtils.copyProperties(financialProcess, financialProcessDTO);
                    } else {
                        FinancialProcess financialProcess = new FinancialProcess();
                        //为所有字段设置默认值
                        financialProcess.setIsTicketObtained("否");
                        financialProcess.setIsTaxItemCompleted("否");
                        financialProcess.setIsAccountingDone("否");
                        financialProcess.setIsTaxFilingCompleted("否");
                        financialProcess.setIsPayrollProcessed("否");
                        financialProcess.setIsPersonnelInfoSynced("否");
                        financialProcess.setIsIndividualTaxCompleted("否");
                        financialProcess.setAccountSetsId(accountSets.getId());
                        financialProcess.setAccountingPeriod(financialProcessConditional.getAccountingPeriod());
                        financialProcessList.add(financialProcess);
                        BeanUtils.copyProperties(financialProcess, financialProcessDTO);
                    }
                    // 设置失败原因，安全处理空值情况
                    if (timedTaskInvoiceMap.containsKey(accountSets.getId()) &&
                        !timedTaskInvoiceMap.get(accountSets.getId()).isEmpty()) {
                        String failureReason = timedTaskInvoiceMap.get(accountSets.getId()).get(0).getFailureReason();
                        financialProcessDTO.setFailureReason( failureReason);
                    }
                    financialProcessDTO.setVatType(String.valueOf(accountSets.getVatType()));
                    return financialProcessDTO;
                })
                .toList();
        //根据前端传来的筛选条件来筛选数据
        List<FinancialProcessDTO> filteredList = financialProcessDTOList.stream()
                .filter(financialProcessDTO -> {
                    // 检查公司名称筛选条件
                    if (financialProcessConditional.getCompanyName() != null &&
                        !financialProcessConditional.getCompanyName().trim().isEmpty()) {
                        if (financialProcessDTO.getCompanyName() == null ||
                            !financialProcessDTO.getCompanyName().contains(financialProcessConditional.getCompanyName())) {
                            return false;
                        }
                    }

                    // 检查是否取得发票筛选条件
                    if (financialProcessConditional.getIsTicketObtained() != null &&
                        !financialProcessConditional.getIsTicketObtained().trim().isEmpty()) {
                        if (financialProcessDTO.getIsTicketObtained() == null ||
                            !financialProcessDTO.getIsTicketObtained().equals(financialProcessConditional.getIsTicketObtained())) {
                            return false;
                        }
                    }

                    // 检查税务项目完成筛选条件
                    if (financialProcessConditional.getIsTaxItemCompleted() != null &&
                        !financialProcessConditional.getIsTaxItemCompleted().trim().isEmpty()) {
                        if (financialProcessDTO.getIsTaxItemCompleted() == null ||
                            !financialProcessDTO.getIsTaxItemCompleted().equals(financialProcessConditional.getIsTaxItemCompleted())) {
                            return false;
                        }
                    }

                    // 检查记账完成筛选条件
                    if (financialProcessConditional.getIsAccountingDone() != null &&
                        !financialProcessConditional.getIsAccountingDone().trim().isEmpty()) {
                        if (financialProcessDTO.getIsAccountingDone() == null ||
                            !financialProcessDTO.getIsAccountingDone().equals(financialProcessConditional.getIsAccountingDone())) {
                            return false;
                        }
                    }

                    // 检查税务申报完成筛选条件
                    if (financialProcessConditional.getIsTaxFilingCompleted() != null &&
                        !financialProcessConditional.getIsTaxFilingCompleted().trim().isEmpty()) {
                        if (financialProcessDTO.getIsTaxFilingCompleted() == null ||
                            !financialProcessDTO.getIsTaxFilingCompleted().equals(financialProcessConditional.getIsTaxFilingCompleted())) {
                            return false;
                        }
                    }

                    // 检查工资处理完成筛选条件
                    if (financialProcessConditional.getIsPayrollProcessed() != null &&
                        !financialProcessConditional.getIsPayrollProcessed().trim().isEmpty()) {
                        if (financialProcessDTO.getIsPayrollProcessed() == null ||
                            !financialProcessDTO.getIsPayrollProcessed().equals(financialProcessConditional.getIsPayrollProcessed())) {
                            return false;
                        }
                    }

                    // 检查人员信息同步筛选条件
                    if (financialProcessConditional.getIsPersonnelInfoSynced() != null &&
                        !financialProcessConditional.getIsPersonnelInfoSynced().trim().isEmpty()) {
                        if (financialProcessDTO.getIsPersonnelInfoSynced() == null ||
                            !financialProcessDTO.getIsPersonnelInfoSynced().equals(financialProcessConditional.getIsPersonnelInfoSynced())) {
                            return false;
                        }
                    }

                    // 检查个税完成筛选条件
                    if (financialProcessConditional.getIsIndividualTaxCompleted() != null &&
                        !financialProcessConditional.getIsIndividualTaxCompleted().trim().isEmpty()) {
                        if (financialProcessDTO.getIsIndividualTaxCompleted() == null ||
                            !financialProcessDTO.getIsIndividualTaxCompleted().equals(financialProcessConditional.getIsIndividualTaxCompleted())) {
                            return false;
                        }
                    }
                    // 检查增值税类型筛选条件
                    if (financialProcessConditional.getVatType() != null &&
                            !financialProcessConditional.getVatType().trim().isEmpty()) {
                        if (financialProcessDTO.getVatType() == null ||
                                !financialProcessDTO.getVatType().equals(financialProcessConditional.getVatType())) {
                            return false;
                        }
                    }

                    return true;
                })
                .toList();

        // 处理分页
        int page = 1;
        int pageSize = 10;

        // 解析分页参数
        if (financialProcessConditional.getPage() != null && !financialProcessConditional.getPage().trim().isEmpty()) {
            try {
                page = Integer.parseInt(financialProcessConditional.getPage());
                if (page < 1) page = 1; // 确保页码至少为1
            } catch (NumberFormatException e) {
                page = 1; // 解析失败时使用默认值
            }
        }

        if (financialProcessConditional.getPageSize() != null && !financialProcessConditional.getPageSize().trim().isEmpty()) {
            try {
                pageSize = Integer.parseInt(financialProcessConditional.getPageSize());
                if (pageSize < 1) pageSize = 10; // 确保每页大小至少为1
            } catch (NumberFormatException e) {
                pageSize = 10; // 解析失败时使用默认值
            }
        }

        // 计算分页起始位置
        int startIndex = (page - 1) * pageSize;
        int endIndex = Math.min(startIndex + pageSize, filteredList.size());

        // 如果起始位置超出数据范围，返回空列表
        if (startIndex >= filteredList.size()) {
            Map<String, Object> result = new HashMap<>();
            result.put("data", new ArrayList<>());
            result.put("total", 0);
            return result;
        }

        // 返回分页后的数据
        List<FinancialProcessDTO> pageData = filteredList.subList(startIndex, endIndex);

        // 构建返回结果
        Map<String, Object> result = new HashMap<>();
        result.put("data", pageData);
        result.put("total", filteredList.size());

        return result;
    }
    @Transactional(rollbackFor = Exception.class)
    public void updateFinancialProcess(FinancialProcessDTO financialProcessDTO) {
        //先判断该会计期间是否存在该账套的财务流程记
        long count = jqf.selectFrom(qFinancialProcess)
                .where(
                        qFinancialProcess.accountSetsId.eq(financialProcessDTO.getAccountSetsId())
                                .and(
                                        qFinancialProcess.accountingPeriod.startsWith(
                                                financialProcessDTO.getAccountingPeriod().substring(0, 7)
                                        )
                                )
                )
                .stream()
                .count();
        String periodYearMonth =  financialProcessDTO.getAccountingPeriod().substring(0,7);
        String periodYearMonthDay = periodYearMonth + "-01";
        financialProcessDTO.setAccountingPeriod(periodYearMonthDay);
        Map<String, Object> params = BeanUtil.beanToMap(financialProcessDTO, false, false);
        if (count == 0) {
            lazyDao.executeSql("insertFinancialProcess", params);
        } else {
            lazyDao.executeSql("updateFinancialProcess", params);
        }
    }

    /**
     * 分页查询所有账套，并标记当前用户是否已分配
     *
     * @param userId 当前用户ID
     * @param req    查询条件: companyName / ownership(ALL|MINE|OTHERS) / assigneeUserId / page / pageSize
     */
    public Map<String, Object> listAllAccountSets(Long userId, Map<String, Object> req) {
        if (req == null) {
            req = new HashMap<>();
        }
        String companyName = req.get("companyName") == null ? null : String.valueOf(req.get("companyName")).trim();
        String ownership = req.get("ownership") == null ? "ALL" : String.valueOf(req.get("ownership"));
        String province = req.get("province") == null ? null : String.valueOf(req.get("province")).trim();
        Long assigneeUserId = parseLongSafe(req.get("assigneeUserId"));
        int page = parseIntSafe(req.get("page"), 1);
        int pageSize = parseIntSafe(req.get("pageSize"), 500);
        if (page < 1) page = 1;
        if (pageSize < 1) pageSize = 500;

        Set<Long> myAstIds = bqf.selectFrom(qUserAccountSets)
                .select(qUserAccountSets.accountSetsId)
                .where(qUserAccountSets.userId.eq(userId))
                .fetch()
                .stream()
                .collect(Collectors.toSet());

        BooleanBuilder where = new BooleanBuilder();
        where.and(qAccountSets.deactivated.isNull().or(qAccountSets.deactivated.isFalse()));
        if (companyName != null && !companyName.isEmpty()) {
            where.and(qAccountSets.companyName.contains(companyName));
        }
        if (province != null && !province.isEmpty() && !"ALL".equalsIgnoreCase(province)) {
            if ("__EMPTY__".equalsIgnoreCase(province)) {
                where.and(qAccountSets.province.isNull().or(qAccountSets.province.eq("")));
            } else {
                where.and(qAccountSets.province.eq(province));
            }
        }
        if ("MINE".equalsIgnoreCase(ownership)) {
            if (myAstIds.isEmpty()) {
                Map<String, Object> empty = new HashMap<>();
                empty.put("data", new ArrayList<>());
                empty.put("total", 0L);
                return empty;
            }
            where.and(qAccountSets.id.in(myAstIds));
        } else if ("OTHERS".equalsIgnoreCase(ownership)) {
            if (!myAstIds.isEmpty()) {
                where.and(qAccountSets.id.notIn(myAstIds));
            }
        }
        if (assigneeUserId != null) {
            Set<Long> assigneeAstIds = bqf.selectFrom(qUserAccountSets)
                    .select(qUserAccountSets.accountSetsId)
                    .where(qUserAccountSets.userId.eq(assigneeUserId))
                    .fetch()
                    .stream()
                    .collect(Collectors.toSet());
            if (assigneeAstIds.isEmpty()) {
                Map<String, Object> empty = new HashMap<>();
                empty.put("data", new ArrayList<>());
                empty.put("total", 0L);
                return empty;
            }
            where.and(qAccountSets.id.in(assigneeAstIds));
        }

        long total = bqf.selectFrom(qAccountSets).where(where).fetchCount();
        List<AccountSets> rows = bqf.selectFrom(qAccountSets)
                .where(where)
                .orderBy(qAccountSets.id.desc())
                .offset((long) (page - 1) * pageSize)
                .limit(pageSize)
                .fetch();

        List<Map<String, Object>> data = rows.stream().map(as -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", as.getId());
            row.put("companyName", as.getCompanyName());
            row.put("creditCode", as.getCreditCode());
            row.put("vatType", as.getVatType() == null ? null : as.getVatType().name());
            row.put("province", as.getProvince());
            row.put("enableDate", as.getEnableDate());
            row.put("isMine", myAstIds.contains(as.getId()));
            row.put("isCreator", as.getCreatorId() != null && as.getCreatorId().equals(userId));
            return row;
        }).collect(Collectors.toList());

        Map<String, Object> result = new HashMap<>();
        result.put("data", data);
        result.put("total", total);
        return result;
    }

    /**
     * 将当前用户分配为该账套的管理员
     */
    @Transactional(rollbackFor = Exception.class)
    public void assignSelf(String mobile, Long accountSetsId) {
        Role role = roleService.getAstAdmin(accountSetsId);
        accountSetsService.addUser(accountSetsId, mobile, role.getId());
    }

    /**
     * 批量分配账套给当前用户。每条独立事务（accountSetsService.addUser 自带 @Transactional），
     * 已分配/超额等单条失败不影响其它继续执行。
     */
    public List<Map<String, Object>> batchAssignSelf(String mobile, Collection<Long> accountSetsIds) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (accountSetsIds == null || accountSetsIds.isEmpty()) {
            return results;
        }
        Set<Long> distinctIds = accountSetsIds.stream().filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (Long id : distinctIds) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("accountSetsId", id);
            try {
                Role role = roleService.getAstAdmin(id);
                accountSetsService.addUser(id, mobile, role.getId());
                result.put("success", true);
                result.put("message", "分配成功");
            } catch (ServiceException se) {
                result.put("success", false);
                result.put("code", se.getCode());
                result.put("message", se.getMessage());
            } catch (Exception e) {
                result.put("success", false);
                result.put("message", e.getMessage() == null ? "分配失败" : e.getMessage());
            }
            results.add(result);
        }
        return results;
    }

    /**
     * 将当前用户从该账套移除。账套创建人禁止自移除。
     */
    @Transactional(rollbackFor = Exception.class)
    public void unassignSelf(Long userId, Long accountSetsId) {
        AccountSets accountSets = bqf.selectFrom(qAccountSets)
                .where(qAccountSets.id.eq(accountSetsId))
                .fetchFirst();
        if (accountSets == null) {
            throw new ServiceException("账套不存在", 404);
        }
        if (accountSets.getCreatorId() != null && accountSets.getCreatorId().equals(userId)) {
            throw new ServiceException("您是该账套的创建人，无法将自己移除", 403);
        }
        accountSetsService.removeUser(accountSetsId, userId);
    }

    private int parseIntSafe(Object value, int defaultVal) {
        if (value == null) {
            return defaultVal;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return defaultVal;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private Long parseLongSafe(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
