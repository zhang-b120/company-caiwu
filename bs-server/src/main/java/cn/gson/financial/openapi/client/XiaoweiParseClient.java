package cn.gson.financial.openapi.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FN-06 — 触发 xiaowei 端的银行流水解析（异步 HTTP 调用）。
 * <p>
 * 解析服务由 xiaowei 团队的 parse_bank.py 提供，部署在 {@code agent.workflow.base-url}（默认 http://127.0.0.1:8000）下。
 * <p>
 * <b>TODO</b>：实际 URL 路径待 xiaowei 团队确认 — 当前默认调
 * <code>${agent.workflow.base-url}/parse_bank</code>。如果 xiaowei 用其它路径（例如
 * {@code /api/parse_bank} 或 {@code /v1/bank/parse}），请改下面的 {@link #PARSE_BANK_PATH}。
 * <p>
 * 调用契约（约定）：xiaowei 拿到 fileId 之后，自己回调
 * {@code POST /openapi/v1/files/{fileId}/mark-parsed} 标记已处理。本 client
 * 只负责"通知 + 失败不阻塞"，不等待解析结果。
 */
@Slf4j
@Component
public class XiaoweiParseClient {

    /** xiaowei 的银行流水解析端点路径 — 待 xiaowei 团队确认实际值 */
    static final String PARSE_BANK_PATH = "/parse_bank";

    private final String baseUrl;
    private final RestTemplate restTemplate;

    @Autowired
    public XiaoweiParseClient(@Value("${agent.workflow.base-url:http://127.0.0.1:8000}") String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.restTemplate = new RestTemplate();
    }

    /** 测试注入用 */
    XiaoweiParseClient(String baseUrl, RestTemplate restTemplate) {
        this.baseUrl = baseUrl == null ? "" : (baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        this.restTemplate = restTemplate;
    }

    /**
     * 异步触发银行流水解析。失败仅记录日志，不抛出。
     *
     * @param fileId         financial 端 fxy_financial_file_info 表的主键
     * @param accountSetsId  账套 ID（xiaowei 后续回调 mark-parsed 也需要）
     * @param customerId     辅助核算客户 ID（可空）
     * @param bankName       银行名（用于挑选解析模板）
     * @param periodYear     账期年
     * @param periodMonth    账期月
     */
    @Async
    public void triggerParseBankAsync(Long fileId, Long accountSetsId, Long customerId,
                                      String bankName, Integer periodYear, Integer periodMonth) {
        triggerParseBank(fileId, accountSetsId, customerId, bankName, periodYear, periodMonth);
    }

    /**
     * 同步触发版本（仅供测试 / 内部串行调用使用）。
     *
     * @return true 表示 HTTP 2xx，false 表示失败（已被吞掉）
     */
    public boolean triggerParseBank(Long fileId, Long accountSetsId, Long customerId,
                                     String bankName, Integer periodYear, Integer periodMonth) {
        String url = baseUrl + PARSE_BANK_PATH;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fileId", fileId);
        body.put("accountSetsId", accountSetsId);
        body.put("customerId", customerId);
        body.put("bankName", bankName);
        body.put("periodYear", periodYear);
        body.put("periodMonth", periodMonth);
        // 提供给 xiaowei 端回调用，省一次它再去查
        body.put("downloadUrl", "/openapi/v1/files/" + fileId + "/download?accountSetsId=" + accountSetsId);
        body.put("markParsedUrl", "/openapi/v1/files/" + fileId + "/mark-parsed?accountSetsId=" + accountSetsId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
            boolean ok = resp.getStatusCode().is2xxSuccessful();
            if (ok) {
                log.info("xiaowei 银行流水解析触发成功 fileId={} accountSetsId={} status={}",
                        fileId, accountSetsId, resp.getStatusCode());
            } else {
                log.warn("xiaowei 银行流水解析触发返回非 2xx fileId={} status={} body={}",
                        fileId, resp.getStatusCode(), resp.getBody());
            }
            return ok;
        } catch (RestClientException e) {
            // 异步触发，失败不阻塞主流程；warn 级别让运维能看到
            log.warn("xiaowei 银行流水解析触发失败 fileId={} url={} err={}", fileId, url, e.getMessage());
            return false;
        } catch (Exception e) {
            log.warn("xiaowei 银行流水解析触发异常 fileId={} url={}", fileId, url, e);
            return false;
        }
    }
}
