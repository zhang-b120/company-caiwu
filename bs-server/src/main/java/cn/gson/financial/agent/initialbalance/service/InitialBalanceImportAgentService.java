package cn.gson.financial.agent.initialbalance.service;

import cn.gson.financial.agent.initialbalance.dto.InitialBalanceImportResponse;
import cn.gson.financial.agent.initialbalance.util.BalanceExcelSplitUtil;
import cn.hutool.core.collection.CollUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.Base64;
import java.util.stream.Collectors;

/**
 * 期初数据导入 Agent 服务
 * <p>
 * 财务系统可直接使用工作流接口（如 http://localhost:8081/import），拿到科目余额表、系统科目 Excel 后
 * 直接以 multipart/form-data 上传即可，无需转换为流或 Base64。
 * <p>
 * 调用外部工作流接口 {importBaseUrl}/import，multipart/form-data 接收参数：
 * <ul>
 *   <li>balance_file          文件  是  科目余额表 Excel 文件</li>
 *   <li>default_accounts_file  文件  是  系统科目 Excel 文件</li>
 *   <li>period                 表单  否  会计期间，默认 "2025-01"</li>
 *   <li>code_rule              表单  否  编码规则，如 4-3-3-2 或 [4,3,3,2]</li>
 * </ul>
 */
@Slf4j
@Service
public class InitialBalanceImportAgentService {

    private final RestTemplate restTemplate;

    /**
     * 期初导入接口根 URL，默认 http://182.40.193.87:8081
     * 完整地址为：{importBaseUrl}/import
     */
    @Value("${agent.workflow.import-url:http://182.40.193.87:8081}")
    private String importBaseUrl;

    /** 期初数据导入接口路径（直接上传文件） */
    private static final String IMPORT_PATH = "/import";

    /** 表单字段：科目余额表 Excel（.xlsx/.xls） */
    private static final String FORM_BALANCE_FILE = "balance_file";
    /** 表单字段：系统科目 Excel */
    private static final String FORM_ACCOUNTS_FILE = "default_accounts_file";
    /** 表单字段：会计期间 period */
    private static final String FORM_PERIOD = "period";
    /** 表单字段：编码规则 code_rule，如 4-3-3-2 或 [4,3,3,2] */
    private static final String FORM_CODE_RULE = "code_rule";

    /** 分批导入时每份数据行数（不含表头），默认 50 */
    @Value("${agent.workflow.import-batch-size:50}")
    private int importBatchSize;

    public InitialBalanceImportAgentService() {
        this.restTemplate = new RestTemplate();
    }

    /**
     * 调用期初导入接口，直接上传文件（不转流、不 Base64）
     *
     * @param balanceFile  期初文件（科目余额表）
     * @param accountsFile 账套中的期初文件
     * @param period       会计期间（可选，默认 2025-01）
     * @param encodingRule 科目编码格式（可选），当前账套的 JSON 数组如 [4,3,3,2]，不传用 config 默认
     * @return 导入响应
     */
    public InitialBalanceImportResponse importInitialBalance(
            MultipartFile balanceFile,
            MultipartFile accountsFile,
            String period,
            String encodingRule) throws IOException {
        if (balanceFile == null || balanceFile.isEmpty()) {
            throw new IllegalArgumentException("期初文件（科目余额表）不能为空");
        }
        if (accountsFile == null || accountsFile.isEmpty()) {
            throw new IllegalArgumentException("账套期初文件不能为空");
        }
        Resource balanceResource = toNamedResource(balanceFile.getBytes(), balanceFile.getOriginalFilename());
        Resource accountsResource = toNamedResource(accountsFile.getBytes(), accountsFile.getOriginalFilename());
        return doImport(balanceResource, balanceFile.getOriginalFilename(),
                accountsResource, accountsFile.getOriginalFilename(),
                period != null ? period : "2025-01",
                encodingRule);
    }

    /**
     * 使用字节数组调用期初导入接口（直接上传，不转 Base64）
     *
     * @param encodingRule 科目编码格式（可选），JSON 数组如 [4,3,3,2]
     */
    public InitialBalanceImportResponse importInitialBalance(
            byte[] balanceFileBytes,
            String balanceFileName,
            byte[] accountsFileBytes,
            String accountsFileName,
            String period,
            String encodingRule) {
        if (balanceFileBytes == null || balanceFileBytes.length == 0) {
            throw new IllegalArgumentException("期初文件（科目余额表）不能为空");
        }
        if (accountsFileBytes == null || accountsFileBytes.length == 0) {
            throw new IllegalArgumentException("账套期初文件不能为空");
        }
        Resource balanceResource = toNamedResource(balanceFileBytes, balanceFileName);
        Resource accountsResource = toNamedResource(accountsFileBytes, accountsFileName);
        return doImport(balanceResource, balanceFileName, accountsResource, accountsFileName,
                period != null ? period : "2025-01",
                encodingRule);
    }

    /**
     * 将科目余额表按行拆成多份、分批调用工作流，多批返回合并为一份后再一块处理（减少单次返回长度）。
     * 调用方拿到的是合并后的单次响应，写入账套时只做一次 apply。
     *
     * @param balanceFileBytes  科目余额表 Excel 字节
     * @param balanceFileName   文件名
     * @param accountsFileBytes 系统科目 Excel 字节
     * @param accountsFileName  文件名
     * @param period            会计期间
     * @param encodingRule      编码规则
     * @return 合并后的导入响应
     */
    public InitialBalanceImportResponse importInitialBalanceInBatches(
            byte[] balanceFileBytes,
            String balanceFileName,
            byte[] accountsFileBytes,
            String accountsFileName,
            String period,
            String encodingRule) {
        if (balanceFileBytes == null || balanceFileBytes.length == 0) {
            throw new IllegalArgumentException("期初文件（科目余额表）不能为空");
        }
        if (accountsFileBytes == null || accountsFileBytes.length == 0) {
            throw new IllegalArgumentException("账套期初文件不能为空");
        }
        String periodVal = period != null ? period : "2025-01";
        List<byte[]> chunks;
        try {
            chunks = BalanceExcelSplitUtil.splitByRows(balanceFileBytes, importBatchSize);
        } catch (IOException e) {
            log.warn("拆分科目余额表失败，改为单次导入: {}", e.getMessage());
            return importInitialBalance(balanceFileBytes, balanceFileName, accountsFileBytes, accountsFileName, period, encodingRule);
        }
        if (chunks.size() <= 1) {
            return importInitialBalance(balanceFileBytes, balanceFileName, accountsFileBytes, accountsFileName, period, encodingRule);
        }
        log.info("科目余额表拆成 {} 批导入，每批最多 {} 行", chunks.size(), importBatchSize);
        List<InitialBalanceImportResponse> responses = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            byte[] chunk = chunks.get(i);
            String chunkName = balanceFileName != null && balanceFileName.contains(".")
                    ? balanceFileName.replaceFirst("(\\.xlsx?)$", "_part" + (i + 1) + "$1")
                    : "balance_part" + (i + 1) + ".xlsx";
            Resource balanceResource = toNamedResource(chunk, chunkName);
            Resource accountsResource = toNamedResource(accountsFileBytes, accountsFileName);
            InitialBalanceImportResponse r = doImport(balanceResource, chunkName, accountsResource, accountsFileName, periodVal, encodingRule);
            responses.add(r);
        }
        // 多批返回合并为一份，返回来一块处理（写入账套时只 apply 一次）
        return mergeImportResponses(responses);
    }

    /** 将多批工作流返回合并为一份，供调用方一块处理（单次写入账套）。 */
    private static InitialBalanceImportResponse mergeImportResponses(List<InitialBalanceImportResponse> responses) {
        if (responses == null || responses.isEmpty()) {
            InitialBalanceImportResponse empty = new InitialBalanceImportResponse();
            empty.setSuccess(true);
            empty.setData(new InitialBalanceImportResponse.ResponseData());
            empty.setMessage("无数据");
            return empty;
        }
        if (responses.size() == 1) {
            return responses.get(0);
        }
        InitialBalanceImportResponse merged = new InitialBalanceImportResponse();
        merged.setSuccess(responses.stream().allMatch(r -> Boolean.TRUE.equals(r.getSuccess())));
        merged.setMessage(responses.stream().map(InitialBalanceImportResponse::getMessage).filter(Objects::nonNull).collect(Collectors.joining("；")));
        InitialBalanceImportResponse.ResponseData data = new InitialBalanceImportResponse.ResponseData();
        Map<String, InitialBalanceImportResponse.NewSubject> newSubjectByCode = new LinkedHashMap<>();
        List<InitialBalanceImportResponse.InitialBalance> allBalances = new ArrayList<>();
        List<InitialBalanceImportResponse.AmbiguousMatch> allAmbiguous = new ArrayList<>();
        List<InitialBalanceImportResponse.Level1OnlyBalance> allLevel1 = new ArrayList<>();
        List<InitialBalanceImportResponse.AutoMapping> allAuto = new ArrayList<>();
        String period = null;
        for (InitialBalanceImportResponse r : responses) {
            if (r.getData() == null) continue;
            InitialBalanceImportResponse.ResponseData d = r.getData();
            if (CollUtil.isNotEmpty(d.getNewSubjects())) {
                for (InitialBalanceImportResponse.NewSubject s : d.getNewSubjects()) {
                    if (s != null && s.getCode() != null) {
                        newSubjectByCode.putIfAbsent(s.getCode(), s);
                    }
                }
            }
            if (CollUtil.isNotEmpty(d.getInitialBalances())) {
                allBalances.addAll(d.getInitialBalances());
            }
            if (CollUtil.isNotEmpty(d.getAmbiguousMatches())) {
                allAmbiguous.addAll(d.getAmbiguousMatches());
            }
            if (CollUtil.isNotEmpty(d.getLevel1OnlyBalances())) {
                allLevel1.addAll(d.getLevel1OnlyBalances());
            }
            if (CollUtil.isNotEmpty(d.getAutoMappings())) {
                allAuto.addAll(d.getAutoMappings());
            }
            if (period == null && d.getPeriod() != null) period = d.getPeriod();
        }
        data.setNewSubjects(new ArrayList<>(newSubjectByCode.values()));
        data.setInitialBalances(allBalances);
        data.setPeriod(period);
        data.setAmbiguousMatches(allAmbiguous.isEmpty() ? null : allAmbiguous);
        data.setLevel1OnlyBalances(allLevel1.isEmpty() ? null : allLevel1);
        data.setAutoMappings(allAuto.isEmpty() ? null : allAuto);
        merged.setData(data);
        return merged;
    }

    /**
     * 使用 File 路径调用期初导入接口（读取为字节后直接上传）
     */
    public InitialBalanceImportResponse importInitialBalance(
            java.io.File balanceFile,
            java.io.File accountsFile,
            String period,
            String encodingRule) throws IOException {
        byte[] balanceBytes = java.nio.file.Files.readAllBytes(balanceFile.toPath());
        byte[] accountsBytes = java.nio.file.Files.readAllBytes(accountsFile.toPath());
        return importInitialBalance(balanceBytes, balanceFile.getName(), accountsBytes, accountsFile.getName(), period, encodingRule);
    }

    /**
     * 发送 multipart/form-data 到 /import
     */
    private InitialBalanceImportResponse doImport(
            Resource balanceResource,
            String balanceFileName,
            Resource accountsResource,
            String accountsFileName,
            String period,
            String encodingRule) {
        String url = importBaseUrl + IMPORT_PATH;
        log.info("调用期初导入接口（直接上传文件）：{}", url);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add(FORM_BALANCE_FILE, balanceResource);
        body.add(FORM_ACCOUNTS_FILE, accountsResource);
        body.add(FORM_PERIOD, period);
        if (encodingRule != null && !encodingRule.isBlank()) {
            body.add(FORM_CODE_RULE, encodingRule.trim());
        }

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        try {
            log.info("上传期初文件：{}，账套期初文件：{}，期间：{}，编码规则：{}", balanceFileName, accountsFileName, period, encodingRule);
            ResponseEntity<InitialBalanceImportResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    InitialBalanceImportResponse.class
            );

            InitialBalanceImportResponse result = response.getBody();
            if (result != null && Boolean.TRUE.equals(result.getSuccess())) {
                log.info("期初导入接口调用成功");
            } else {
                log.warn("期初导入接口返回失败：{}", result != null ? result.getMessage() : "未知错误");
            }
            return result;
        } catch (RestClientException e) {
            log.error("调用期初导入接口失败", e);
            throw new RuntimeException("调用期初导入接口失败: " + e.getMessage(), e);
        }
    }

    private static Resource toNamedResource(byte[] bytes, String filename) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename != null ? filename : "file";
            }
        };
    }

    /**
     * 将 Base64 字符串转为字节数组（兼容旧用法）
     */
    public byte[] base64ToBytes(String base64) {
        if (base64 == null) return new byte[0];
        if (base64.contains(",")) {
            base64 = base64.substring(base64.indexOf(",") + 1);
        }
        return Base64.getDecoder().decode(base64);
    }
}
