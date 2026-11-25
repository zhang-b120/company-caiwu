package cn.gson.financial.agent.taxlogin.service;

import com.microsoft.playwright.BrowserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 税务浏览器管理服务（单例模式）
 * 使用"省份+手机号"作为key来管理浏览器对象
 * 支持Spring依赖注入和传统单例模式两种方式
 */
@Slf4j
@Service
public class TaxBrowserManagerService {
    
    // 单例实例（用于非Spring环境）
    private static volatile TaxBrowserManagerService instance;
    
    // 浏览器对象Map：key = "省份+手机号"，value = BrowserContext
    private final Map<String, BrowserContext> browserMap = new ConcurrentHashMap<>();
    
    // 浏览器元数据Map：key = "省份+手机号"，value = 浏览器元数据（包含税号等信息）
    private final Map<String, BrowserMetadata> metadataMap = new ConcurrentHashMap<>();
    
    /**
     * 构造方法（Spring会自动注入，同时支持传统单例模式）
     */
    public TaxBrowserManagerService() {
        // 如果是第一个实例，设置为单例实例
        if (instance == null) {
            synchronized (TaxBrowserManagerService.class) {
                if (instance == null) {
                    instance = this;
                }
            }
        }
    }
    
    /**
     * 获取单例实例（双重检查锁定，用于非Spring环境）
     */
    public static TaxBrowserManagerService getInstance() {
        if (instance == null) {
            synchronized (TaxBrowserManagerService.class) {
                if (instance == null) {
                    instance = new TaxBrowserManagerService();
                }
            }
        }
        return instance;
    }
    
    /**
     * 生成浏览器key：省份+手机号
     */
    public String generateBrowserKey(String province, String phoneNumber) {
        return province + "*" + phoneNumber;
    }
    
    /**
     * 获取浏览器对象
     */
    public BrowserContext getBrowser(String key) {
        BrowserContext context = browserMap.get(key);
        if (context != null && !context.browser().isConnected()) {
            // 浏览器已断开，移除
            removeBrowser(key);
            return null;
        }
        return context;
    }
    
    /**
     * 添加浏览器对象
     */
    public void addBrowser(String key, BrowserContext browser, BrowserMetadata metadata) {
        // 如果已存在，先关闭旧的浏览器
        BrowserContext existing = browserMap.get(key);
        if (existing != null) {
            try {
                existing.close();
            } catch (Exception e) {
                log.warn("关闭旧浏览器失败: {}", key, e);
            }
        }
        browserMap.put(key, browser);
        if (metadata != null) {
            metadataMap.put(key, metadata);
        }
        log.info("添加浏览器: key={}, 税号={}", key, metadata != null ? metadata.getTaxNumber() : "未知");
    }
    
    /**
     * 移除浏览器对象
     */
    public void removeBrowser(String key) {
        BrowserContext context = browserMap.remove(key);
        metadataMap.remove(key);
        if (context != null) {
            try {
                context.close();
                log.info("移除并关闭浏览器: key={}", key);
            } catch (Exception e) {
                log.warn("关闭浏览器失败: key={}", key, e);
            }
        }
    }
    
    /**
     * 检查浏览器是否存在且有效
     */
    public boolean hasBrowser(String key) {
        BrowserContext context = browserMap.get(key);
        if (context == null) {
            return false;
        }
        // 检查浏览器是否仍然连接
        try {
            return context.browser().isConnected();
        } catch (Exception e) {
            // 浏览器已断开，移除
            removeBrowser(key);
            return false;
        }
    }
    
    /**
     * 获取浏览器元数据
     */
    public BrowserMetadata getMetadata(String key) {
        return metadataMap.get(key);
    }
    
    /**
     * 获取所有浏览器key
     */
    public java.util.Set<String> getAllBrowserKeys() {
        return browserMap.keySet();
    }
    
    /**
     * 清理所有浏览器
     */
    public void clearAll() {
        for (String key : browserMap.keySet()) {
            removeBrowser(key);
        }
        log.info("清理所有浏览器");
    }
    
    /**
     * 浏览器元数据
     */
    public static class BrowserMetadata {
        private Long accountSetsId;
        private String taxNumber; // 税号
        private String province; // 省份
        private String phoneNumber; // 手机号
        private Long agentId; // 办税员ID
        private Integer debugPort; // CDP 远程调试端口
        
        public BrowserMetadata() {
        }
        
        public BrowserMetadata(Long accountSetsId, String taxNumber, String province, String phoneNumber, Long agentId) {
            this.accountSetsId = accountSetsId;
            this.taxNumber = taxNumber;
            this.province = province;
            this.phoneNumber = phoneNumber;
            this.agentId = agentId;
        }
        
        // Getters and Setters
        public Long getAccountSetsId() {
            return accountSetsId;
        }
        
        public void setAccountSetsId(Long accountSetsId) {
            this.accountSetsId = accountSetsId;
        }
        
        public String getTaxNumber() {
            return taxNumber;
        }
        
        public void setTaxNumber(String taxNumber) {
            this.taxNumber = taxNumber;
        }
        
        public String getProvince() {
            return province;
        }
        
        public void setProvince(String province) {
            this.province = province;
        }
        
        public String getPhoneNumber() {
            return phoneNumber;
        }
        
        public void setPhoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
        }
        
        public Long getAgentId() {
            return agentId;
        }
        
        public void setAgentId(Long agentId) {
            this.agentId = agentId;
        }
        
        public Integer getDebugPort() {
            return debugPort;
        }
        
        public void setDebugPort(Integer debugPort) {
            this.debugPort = debugPort;
        }
    }
    
    /**
     * 获取浏览器的 CDP WebSocket URL（用于前端远程连接）
     * @param key 浏览器 key
     * @return CDP WebSocket URL，如果浏览器不存在或未开启调试则返回 null
     */
    public String getCdpWebSocketUrl(String key) {
        BrowserMetadata metadata = metadataMap.get(key);
        if (metadata == null || metadata.getDebugPort() == null) {
            return null;
        }
        
        try {
            // 访问 CDP 端点获取 WebSocket URL
            java.net.URL url = new java.net.URL("http://localhost:" + metadata.getDebugPort() + "/json");
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            
            if (conn.getResponseCode() == 200) {
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                
                // 解析 JSON 响应，获取第一个页面的 WebSocket URL
                com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                com.fasterxml.jackson.databind.JsonNode jsonArray = mapper.readTree(response.toString());
                if (jsonArray.isArray() && jsonArray.size() > 0) {
                    String wsUrl = jsonArray.get(0).get("webSocketDebuggerUrl").asText();
                    return wsUrl;
                }
            }
        } catch (Exception e) {
            log.warn("获取 CDP WebSocket URL 失败: key={}, port={}", key, metadata.getDebugPort(), e);
        }
        
        return null;
    }
}
