package cn.gson.financial.agent.initialbalance.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.File;

/**
 * 调用 8081 期初填充接口：上传「账套期初模板」+「科目余额表」，返回脚本处理后的 JSON。
 */
@Slf4j
@Service
public class FillInitialHttpClient {

    private static final String FILL_INITIAL_PATH = "/fill-initial";
    private static final String FORM_TEMPLATE_FILE = "template_file";
    private static final String FORM_BALANCE_FILE = "balance_file";

    private final String baseUrl;
    private final RestTemplate restTemplate = new RestTemplate();

    public FillInitialHttpClient(@Value("${agent.workflow.import-url:http://182.40.193.87:8081}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /**
     * 上传模板文件和科目余额表，返回 8081 脚本处理后的 JSON 字符串。
     */
    public String fillInitial(File templateFile, File balanceFile) {
        String url = baseUrl + FILL_INITIAL_PATH;
        log.info("调用期初填充接口: {}，template={}, balance={}", url, templateFile.getName(), balanceFile.getName());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        Resource templateResource = new FileSystemResource(templateFile);
        Resource balanceResource = new FileSystemResource(balanceFile);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add(FORM_TEMPLATE_FILE, templateResource);
        body.add(FORM_BALANCE_FILE, balanceResource);

        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );
            String result = response.getBody();
            log.info("期初填充接口调用成功，响应长度: {}", result != null ? result.length() : 0);
            return result != null ? result : "{}";
        } catch (RestClientException e) {
            log.error("调用期初填充接口失败: {}", e.getMessage());
            throw new RuntimeException("调用期初填充接口失败: " + e.getMessage(), e);
        }
    }
}
