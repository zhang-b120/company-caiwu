package cn.gson.financial.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.annotation.SessionAccountSetId;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.crm.dto.TaxTableDTO;
import cn.gson.financial.kernel.model.crm.req.AddTaskListReq;
import cn.gson.financial.kernel.model.crm.req.OneKeyTaxCompanyReq;
import cn.gson.financial.kernel.model.crm.req.OneKeyTaxItemReq;
import cn.gson.financial.kernel.model.dto.TaxDto;
import cn.gson.financial.kernel.model.entity.*;
import cn.gson.financial.kernel.model.hibernate.BlazeJPAQueryFactory;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.repo.TimedTaskPaymentRepository;
import cn.gson.financial.kernel.model.repo.TimedTaskTaxItemRepository;
import cn.gson.financial.kernel.model.repo.TimedTaskTaxRepository;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.TaxService;
import cn.gson.financial.rpa.TaxBrowserManager;
import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.*;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static cn.gson.financial.kernel.common.Constants.S_USER_KEY;

import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/tax")
public class TaxController {
    @Resource
    private TaxService taxService;
    private QTaskExecuteList qTaskExecuteList = QTaskExecuteList.taskExecuteList;
    protected BlazeJPAQueryFactory jqf;
    @Resource
    @Lazy
    protected BlazeJPAQueryFactory bqf;
    private final QTaxTableSort qTaxTableSort = QTaxTableSort.taxTableSort;
    private final TaxBrowserManager taxBrowserManager;
    private final TimedTaskTaxItemRepository timedTaskTaxItemRepository;
    private final TimedTaskTaxRepository timedTaskTaxRepository;
    private final TimedTaskPaymentRepository timedTaskPaymentRepository;
    /**
     * 一键报税
     * 获取需要报税的条目
     *
     * @param map
     * @return
     */
    @PostMapping("/getTaxItem")
    public JsonResult getTaxItem(@RequestBody Map<String, String> map) {
        UserVo userVo = (UserVo) StpUtil.getSession().get(S_USER_KEY);
        String sponsor = userVo.getMobile();

        String list = map.get("list");
        String accountPeriod = map.get("accountPeriod"); // 获取账期参数，格式：YYYY-MM

        // 解析为实体类列表
        List<OneKeyTaxCompanyReq> companies = JSON.parseArray(list, OneKeyTaxCompanyReq.class);

        // 将所有公司转换为TimedTaskTaxItem实体
        List<TimedTaskTaxItem> timedTaskTaxItemList = companies.stream()
                .map(company -> {
                    TimedTaskTaxItem taxItem = new TimedTaskTaxItem();
                    taxItem.setAccountSetsId(company.getAccountSetsId());
                    taxItem.setState(0); // 默认未执行
                    taxItem.setResult(0); // 初始状态为未执行
                    // 设置账期
                    if (accountPeriod != null && !accountPeriod.isEmpty()) {
                        try {
                            // 将 YYYY-MM 格式转换为 LocalDate（使用该月的第一天）
                            String[] parts = accountPeriod.split("-");
                            if (parts.length == 2) {
                                int year = Integer.parseInt(parts[0]);
                                int month = Integer.parseInt(parts[1]);
                                taxItem.setCheckDate(LocalDate.of(year, month, 1));
                            }
                        } catch (Exception e) {
                            log.warn("账期格式解析失败: {}", accountPeriod, e);
                        }
                    }
                    return taxItem;
                })
                .toList();
        if (CollUtil.isNotEmpty(timedTaskTaxItemList)) {
            timedTaskTaxItemRepository.saveAll(timedTaskTaxItemList);
        }
        return JsonResult.successful();
    }

    @PostMapping("/oneKeyTax")
    public JsonResult OneKeyTax(@RequestBody Map<String, String> map) {
        UserVo userVo = (UserVo) StpUtil.getSession().get(S_USER_KEY);
        String sponsor = userVo.getMobile();

        String list = map.get("list");
        String accountPeriod = map.get("accountPeriod"); // 获取账期参数，格式：YYYY-MM

        // 解析为实体类列表
        List<OneKeyTaxCompanyReq> companies = JSON.parseArray(list, OneKeyTaxCompanyReq.class);

        // 过滤掉children为空数组的公司
        List<OneKeyTaxCompanyReq> validCompanies = companies.stream()
                .filter(company -> CollUtil.isNotEmpty(company.getChildren()))
                .toList();

        // 提取所有children并转换为TimedTaskTax实体
        List<TimedTaskTax> timedTaskTaxList = validCompanies.stream()
                .flatMap(company -> {
                    Long accountSetsId = company.getAccountSetsId();
                    return company.getChildren().stream()
                            .map(item -> {
                                TimedTaskTax tax = new TimedTaskTax();
                                tax.setAccountSetsId(accountSetsId);
                                tax.setTaxType(item.getTaxType());
                                tax.setState(0); // 默认未执行
                                tax.setResult(0); // 初始状态为null
                                // 设置账期
                                if (accountPeriod != null && !accountPeriod.isEmpty()) {
                                    try {
                                        // 将 YYYY-MM 格式转换为 LocalDate（使用该月的第一天）
                                        String[] parts = accountPeriod.split("-");
                                        if (parts.length == 2) {
                                            int year = Integer.parseInt(parts[0]);
                                            int month = Integer.parseInt(parts[1]);
                                            tax.setCheckDate(LocalDate.of(year, month, 1));
                                        }
                                    } catch (Exception e) {
                                        log.warn("账期格式解析失败: {}", accountPeriod, e);
                                    }
                                }
                                return tax;
                            });
                })
                .toList();

        // 存储到数据库
        if (CollUtil.isNotEmpty(timedTaskTaxList)) {
            timedTaskTaxRepository.saveAll(timedTaskTaxList);
        }
        return JsonResult.successful();
    }

    @PostMapping("/executeTaxBegin")
    public JsonResult executeTaxBegin(@RequestBody Map<String, String> map) {
        UserVo userVo = (UserVo) StpUtil.getSession().get(S_USER_KEY);

        map.put("sponsor", userVo.getMobile());//账套id
        while (true) {
            TaskExecuteList taskList = taxService.selectTaskExecuteList(map);
            if (taskList == null) {
                break;
            }

            String taskData = taskList.getTaskData();
            Map<String, String> taskDataMap = JSON.parseObject(taskData, Map.class);
            if (map.get("sponsor").equals(map.get("proxyLoginPhone"))) {
                taskDataMap.put("phoneNumber", userVo.getMobile());
                taskDataMap.put("taxSystemKey", userVo.getMobile());
            } else {
                taskDataMap.put("phoneNumber", map.get("proxyLoginPhone"));
                taskDataMap.put("taxSystemKey",map.get("proxyLoginPhone"));
            }
            try {
                Map<String, String> result = taxBrowserManager.oneKeyTaxButton(taskDataMap, taskList.getTaskExecutionTime().toLocalDate());
                result.put("taskListId", String.valueOf(taskList.getId()));
                result.put("taxId", taskDataMap.get("taxId"));
                int i = taxService.updateTaskExecuteList(result);
            } catch (Exception e) {
               return JsonResult.failure();
            }
        }
        return JsonResult.successful();
    }

    /**
     * 添加下载税费缴纳情况任务
     *
     * @param map
     * @return
     */
    @PostMapping("/addTaxPaymentTask")
    public JsonResult addTaxPaymentTask(@RequestBody Map<String, String> map, @SessionUser UserVo userVo) {
        String list = map.get("list");
        String accountPeriod = map.get("accountPeriod"); // 获取账期参数，格式：YYYY-MM
        
        // 解析为实体类列表
        List<OneKeyTaxCompanyReq> companies = JSON.parseArray(list, OneKeyTaxCompanyReq.class);

        // 将所有公司转换为TimedTaskPayment实体
        List<TimedTaskPayment> timedTaskPaymentList = companies.stream()
                .map(company -> {
                    TimedTaskPayment payment = new TimedTaskPayment();
                    payment.setAccountSetsId(company.getAccountSetsId());
                    payment.setState(0); // 默认未执行
                    payment.setResult(0); // 初始状态为未执行
                    // 设置账期
                    if (accountPeriod != null && !accountPeriod.isEmpty()) {
                        try {
                            // 将 YYYY-MM 格式转换为 LocalDate（使用该月的第一天）
                            String[] parts = accountPeriod.split("-");
                            if (parts.length == 2) {
                                int year = Integer.parseInt(parts[0]);
                                int month = Integer.parseInt(parts[1]);
                                payment.setCheckDate(LocalDate.of(year, month, 1));
                            }
                        } catch (Exception e) {
                            log.warn("账期格式解析失败: {}", accountPeriod, e);
                        }
                    }
                    return payment;
                })
                .toList();
        
        // 存储到数据库
        if (CollUtil.isNotEmpty(timedTaskPaymentList)) {
            timedTaskPaymentRepository.saveAll(timedTaskPaymentList);
        }
        
        return JsonResult.successful("添加下载税费缴纳情况任务成功");
    }
}
