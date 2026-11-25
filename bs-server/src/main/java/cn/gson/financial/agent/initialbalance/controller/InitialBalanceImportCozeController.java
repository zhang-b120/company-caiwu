package cn.gson.financial.agent.initialbalance.controller;

import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.model.dto.BalanceInitialDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.BalanceCalcService;
import cn.gson.financial.kernel.service.BalanceInitialService;
import cn.gson.financial.kernel.service.CurrencyService;
import cn.gson.financial.kernel.service.SubjectService;
import cn.gson.financial.task.workbench.service.TaskWorkbenchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 期初数据导入 - 转发到工作流服务的控制器
 * <p>
 * 前端上传科目余额表 Excel 和会计类型，后端直接转发到工作流的 /run_with_file 接口。
 */
@RestController
@RequestMapping("/account")
@Slf4j
@RequiredArgsConstructor
public class InitialBalanceImportCozeController {

    private final SubjectService subjectService;
    private final BalanceInitialService balanceInitialService;
    private final BalanceCalcService balanceCalcService;
    private final CurrencyService currencyService;
    private final TaskWorkbenchService taskWorkbenchService;

    /**
     * 调用 Coze 工作流所需的 Bearer Token
     */
    private static final String COZE_API_TOKEN = "eyJhbGciOiJSUzI1NiIsImtpZCI6ImIxNTVmODNhLTZkYzAtNDA5Ni05MDRiLWU5MjM0ZTVlZmU2ZiJ9.eyJpc3MiOiJodHRwczovL2FwaS5jb3plLmNuIiwiYXVkIjpbIlR2ZGRlYjNyRnJLRzF1RkVSR3pzQzdoQU5meERTTlhHIl0sImV4cCI6ODIxMDI2Njg3Njc5OSwiaWF0IjoxNzcwODU5ODc2LCJzdWIiOiJzcGlmZmU6Ly9hcGkuY296ZS5jbi93b3JrbG9hZF9pZGVudGl0eS9pZDo3NTk5NTA1ODMxMzU2MjY4NTU5Iiwic3JjIjoiaW5ib3VuZF9hdXRoX2FjY2Vzc190b2tlbl9pZDo3NjA1Nzg1MjU1NTI1NDgyNTA1In0.iwU596Zadj6rFVU94BWf4bbUj-qtWpWw2wwwFsIhI-fgIkO0lHBUQ7CSPupPsppv7DSpU5kXCXtXlA-CY_l4tbRGCQvTr77a0HfCFmSSd2EaXRL-aeg09tDTmI6Is9m7OPB8YZy2AIs6abAwOnQpOZ1ZS3_VGVdUbJB3nAddpEntB6j5z5-bg4aynky1GqhMM83pPxdyJRiN61HFxjIwQ45Cs6cVZVbqQV6YrNKY4FNkThBD4HFsLAMsOZSk-mTi2zTAtuF8bhIAwBvRMmMgFGRc1UqinvshDB71_PZbBFSdXP28byhPJmPUychmmndSBxzjk8JytDCwE9NVO7cuOw";

    @PostMapping("/import")
    public ResponseEntity<?> importAccountData(
            @RequestParam("file") MultipartFile file,
            @RequestParam("accountType") String accountType,
            @SessionAccountSet AccountSets accountSets,
            @SessionUser UserVo userVo) {
        Long accountSetsId = accountSets.getId();
        try {
            // 验证文件
            if (file.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "文件不能为空"));
            }

            String filename = file.getOriginalFilename();
            if (filename == null || (!filename.endsWith(".xlsx") && !filename.endsWith(".xls"))) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "只支持Excel文件"));
            }

            // 验证会计类型
            if (!"small_scale".equals(accountType) && !"general_taxpayer".equals(accountType)) {
                return ResponseEntity.badRequest()
                        .body(Map.of("error", "会计类型只能为 small_scale 或 general_taxpayer"));
            }

            // 创建 RestTemplate 并设置超时时间
            // 设置超时配置：连接超时30秒，响应超时5分钟（300秒），因为Coze接口处理可能需要较长时间
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(30))
                    .setResponseTimeout(Timeout.ofSeconds(600))
                    .setConnectionRequestTimeout(Timeout.ofSeconds(30))
                    .build();

            CloseableHttpClient httpClient = HttpClients.custom()
                    .setDefaultRequestConfig(requestConfig)
                    .build();

            HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
            factory.setHttpClient(httpClient);
            RestTemplate restTemplate = new RestTemplate(factory);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            // 设置 Bearer Token，满足 Coze 的鉴权要求
            if (COZE_API_TOKEN != null && !COZE_API_TOKEN.isEmpty()) {
                headers.setBearerAuth(COZE_API_TOKEN);
            }

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            // 直接使用 MultipartFile 的 Resource 作为文件部分
            body.add("file", file.getResource());
            body.add("account_type", accountType);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            // 转发到工作流服务的 /run_with_file 接口
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    "https://6nbn833j84.coze.site/run_with_file",
                    requestEntity,
                    Map.class
            );

            Map<String, Object> responseBody = (Map<String, Object>) response.getBody().get("llm_result");
            if (responseBody == null) {
                return ResponseEntity.status(response.getStatusCode())
                        .body(Map.of("error", "Coze接口返回数据为空"));
            }

            // 处理返回的数据：转换并保存科目和期初余额
            processAndSaveData(responseBody, accountSetsId, accountSets, userVo);

            // 同步更新任务工作台期初步骤状态为已完成
            if (accountSets != null && accountSets.getEnableDate() != null) {
                try {
                    String enablePeriod = YearMonth.from(accountSets.getEnableDate()).toString();
                    taskWorkbenchService.completeInit(accountSetsId, enablePeriod);
                } catch (Exception ex) {
                    log.warn("同步任务工作台期初状态失败，不影响导入结果: {}", ex.getMessage());
                }
            }

            return ResponseEntity.status(response.getStatusCode())
                    .body(responseBody);

        } catch (Exception e) {
            log.error("导入失败", e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", "导入失败: " + e.getMessage()));
        }
    }

    /**
     * 处理并保存Coze返回的数据
     */
    private void processAndSaveData(Map<String, Object> responseBody, Long accountSetsId,
                                     AccountSets accountSets, UserVo userVo) {
        try {
            // 获取本位币ID
            Long localCurrencyId = userVo.getLocalCurrencyId();
            if (localCurrencyId == null) {
                localCurrencyId = currencyService.fetchLocal(accountSetsId).getId();
            }

            // 处理新增科目
            if (responseBody.containsKey("newSubjects")) {
                Object newSubjectsObj = responseBody.get("newSubjects");
                if (newSubjectsObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> newSubjectsList = (List<Map<String, Object>>) newSubjectsObj;
                    saveNewSubjects(newSubjectsList, accountSetsId, accountSets);
                }
            }

            // 处理期初余额
            if (responseBody.containsKey("initialBalances")) {
                Object initialBalancesObj = responseBody.get("initialBalances");
                if (initialBalancesObj instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> initialBalancesList = (List<Map<String, Object>>) initialBalancesObj;
                    saveInitialBalances(initialBalancesList, accountSetsId, accountSets, localCurrencyId);
                }
            }
        } catch (Exception e) {
            log.error("处理并保存数据失败", e);
            throw new RuntimeException("处理并保存数据失败: " + e.getMessage(), e);
        }
    }

    /**
     * 保存新增科目
     */
    private void saveNewSubjects(List<Map<String, Object>> newSubjectsList,
                                  Long accountSetsId, AccountSets accountSets) {
        Integer encLen = accountSets.getEncoding().stream().reduce(Integer::sum).orElse(0);

        for (Map<String, Object> subjectMap : newSubjectsList) {
            try {
                Subject subject = new Subject();
                subject.setAccountSetsId(accountSetsId);
                subject.setSystemDefault(false);
                subject.setCode(getStringValue(subjectMap, "code"));
                subject.setName(getStringValue(subjectMap, "name"));
                subject.setType(getStringValue(subjectMap, "type"));
                subject.setBalanceDirection(getStringValue(subjectMap, "balanceDirection"));

                // 处理父科目
                String parentCode = getStringValue(subjectMap, "parentCode");
                if (parentCode != null && !parentCode.isEmpty()) {
                    Subject parentSubject = subjectService.listByCode(accountSetsId, parentCode);
                    if (parentSubject != null) {
                        subject.setParentId(parentSubject.getId());
                        subject.setLevel(parentSubject.getLevel() + 1);
                    } else {
                        subject.setParentId(0L);
                        subject.setLevel(1);
                    }
                } else {
                    subject.setParentId(0L);
                    subject.setLevel(1);
                }

                // 默认设置为叶子节点
                subject.setLeaf(true);
                subject.setStatus(true);

                // 保存科目
                subjectService.save(subject, encLen);
                log.info("成功保存科目: code={}, name={}", subject.getCode(), subject.getName());
            } catch (Exception e) {
                log.error("保存科目失败: {}", subjectMap, e);
                // 继续处理下一个科目，不中断整个流程
            }
        }
    }

    /**
     * 保存期初余额
     */
    private void saveInitialBalances(List<Map<String, Object>> initialBalancesList,
                                     Long accountSetsId, AccountSets accountSets, Long localCurrencyId) {
        List<BalanceInitialDto> balanceList = new ArrayList<>();

        for (Map<String, Object> balanceMap : initialBalancesList) {
            try {
                String code = getStringValue(balanceMap, "code");
                if (code == null || code.isEmpty()) {
                    log.warn("期初余额数据缺少科目编码，跳过: {}", balanceMap);
                    continue;
                }

                // 根据编码查找科目
                Subject subject = subjectService.listByCode(accountSetsId, code);
                if (subject == null) {
                    log.warn("科目不存在，跳过期初余额: code={}", code);
                    continue;
                }

                BalanceInitialDto balance = new BalanceInitialDto();
                balance.setAccountSetsId(accountSetsId);
                balance.setSubjectId(subject.getId());
                balance.setSubjectCode(code);
                balance.setCurrencyId(localCurrencyId);
                balance.setAuxiliaryDetailId(0L); // 默认无辅助核算
                balance.setBalanceDirection(subject.getBalanceDirection());
                balance.setLeaf(subject.getLeaf());
                balance.setTreeId(subject.getId());
                balance.setParentTreeId(subject.getParentId());

                // 初始化所有非空字段为0，确保数据库插入不会失败
                BigDecimal zero = BigDecimal.ZERO;
                balance.setBeginBalance(zero);
                balance.setBeginBalanceFor(zero);
                balance.setBeginQuantity(zero);
                balance.setYearTotalDebit(zero);
                balance.setYearTotalDebitFor(zero);
                balance.setYearTotalDebitQuantity(zero);
                balance.setYearTotalCredit(zero);
                balance.setYearTotalCreditFor(zero);
                balance.setYearTotalCreditQuantity(zero);
                balance.setYearTotal(zero);
                balance.setYearTotalFor(zero);
                balance.setYearTotalQuantity(zero);
                balance.setCreditQuantity(zero);
                balance.setDebitQuantity(zero);

                // 设置期初余额数据（如果提供）
                Object beginBalanceObj = balanceMap.get("beginBalance");
                if (beginBalanceObj != null) {
                    BigDecimal beginBalance = convertToBigDecimal(beginBalanceObj);
                    balance.setBeginBalance(beginBalance);
                    balance.setBeginBalanceFor(beginBalance);
                }

                // 设置本年累计借方（如果提供）
                Object yearTotalDebitObj = balanceMap.get("yearTotalDebit");
                if (yearTotalDebitObj != null) {
                    BigDecimal yearTotalDebit = convertToBigDecimal(yearTotalDebitObj);
                    balance.setYearTotalDebit(yearTotalDebit);
                    balance.setYearTotalDebitFor(yearTotalDebit);
                }

                // 设置本年累计贷方（如果提供）
                Object yearTotalCreditObj = balanceMap.get("yearTotalCredit");
                if (yearTotalCreditObj != null) {
                    BigDecimal yearTotalCredit = convertToBigDecimal(yearTotalCreditObj);
                    balance.setYearTotalCredit(yearTotalCredit);
                    balance.setYearTotalCreditFor(yearTotalCredit);
                }

                // 计算年初余额（会自动计算yearTotal）
                balance.calculateYearTotal();
                // 同步设置原币年初余额
                if (balance.getYearTotal() != null) {
                    balance.setYearTotalFor(balance.getYearTotal());
                }

                balanceList.add(balance);
            } catch (Exception e) {
                log.error("处理期初余额失败: {}", balanceMap, e);
                // 继续处理下一个，不中断整个流程
            }
        }

        if (!balanceList.isEmpty()) {
            // 全量替换：先删旧数据再写入，避免第一次导错后第二次残留脏数据
            balanceInitialService.updateInitBalance(balanceList, accountSets);
            // 同步计算余额
            balanceCalcService.calcSynchronization(accountSetsId, YearMonth.from(accountSets.getEnableDate()));
            log.info("成功保存{}条期初余额数据", balanceList.size());
        }
    }

    /**
     * 从Map中获取字符串值
     */
    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * 转换为BigDecimal
     */
    private BigDecimal convertToBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (Exception e) {
            log.warn("无法转换为BigDecimal: {}", value);
            return BigDecimal.ZERO;
        }
    }
}
