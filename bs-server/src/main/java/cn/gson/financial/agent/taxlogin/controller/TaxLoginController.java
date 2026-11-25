package cn.gson.financial.agent.taxlogin.controller;

import cn.gson.financial.agent.taxlogin.service.TaxLoginService;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.entity.TaxLoginAccount;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 税务登录控制器
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tax/login")
public class TaxLoginController {
    
    private final TaxLoginService taxLoginService;
    
    /**
     * 获取办税员列表
     */
    @GetMapping("/agents/{accountSetsId}")
    public JsonResult getTaxAgents(@PathVariable Long accountSetsId) {
        try {
            List<TaxLoginAccount> agents = taxLoginService.getTaxAgents(accountSetsId);
            return JsonResult.successful(agents);
        } catch (Exception e) {
            log.error("获取办税员列表失败", e);
            return JsonResult.failure("获取办税员列表失败：" + e.getMessage());
        }
    }
    
    /**
     * 办税员密码登录
     */
    @PostMapping("/password")
    public JsonResult passwordLogin(@RequestBody Map<String, Object> request) {
        try {
            Long accountSetsId = Long.valueOf(request.get("accountSetsId").toString());
            Long agentId = Long.valueOf(request.get("agentId").toString());
            String password = request.get("password").toString();
            String loginType = request.get("loginType").toString();
            
            Map<String, Object> result = taxLoginService.passwordLogin(accountSetsId, agentId, password,loginType);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("办税员密码登录失败", e);
            return JsonResult.failure("登录失败：" + e.getMessage());
        }
    }





    /**
     * 获取短信验证码
     */
    @PostMapping("/sms/code")
    public JsonResult getSmsCode(@RequestBody Map<String, Object> request) {
        try {
            Long accountSetsId = Long.valueOf(request.get("accountSetsId").toString());
            String phone = request.get("phone").toString();
            
            Map<String, Object> result = taxLoginService.getSmsCode(accountSetsId, phone);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("获取短信验证码失败", e);
            return JsonResult.failure("获取验证码失败：" + e.getMessage());
        }
    }
    
    /**
     * 短信验证码登录
     */
    @PostMapping("/sms")
    public JsonResult smsLogin(@RequestBody Map<String, Object> request) {
        try {
            Long accountSetsId = Long.valueOf(request.get("accountSetsId").toString());
            String phone = request.get("phone").toString();
            String code = request.get("code").toString();
            
            Map<String, Object> result = taxLoginService.smsLogin(accountSetsId, phone, code);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("短信验证码登录失败", e);
            return JsonResult.failure("登录失败：" + e.getMessage());
        }
    }
    
    /**
     * 法人账号密码登录（验证码由前端录入后存入Redis，在RPA登录时按税号从Redis读取）
     */
    @PostMapping("/legal/password")
    public JsonResult legalPasswordLogin(@RequestBody Map<String, Object> request) {
        try {
            Long accountSetsId = Long.valueOf(request.get("accountSetsId").toString());
            String phone = request.get("phone").toString();
            String password = request.get("password").toString();
            
            Map<String, Object> result = taxLoginService.legalPasswordLogin(accountSetsId, phone, password);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("法人账号密码登录失败", e);
            return JsonResult.failure("登录失败：" + e.getMessage());
        }
    }

    /**
     * 保存法人短信验证码到Redis（key为公司税号，value为验证码）
     */
    @PostMapping("/legal/smsCode")
    public JsonResult saveLegalSmsCode(@RequestBody Map<String, Object> request) {
        try {
            Long accountSetsId = Long.valueOf(request.get("accountSetsId").toString());
            String code = request.get("code").toString();
            taxLoginService.saveLegalSmsCode(accountSetsId, code);
            return JsonResult.successful();
        } catch (Exception e) {
            log.error("保存法人短信验证码失败", e);
            return JsonResult.failure("保存验证码失败：" + e.getMessage());
        }
    }
    
    /**
     * 生成二维码
     */
    @PostMapping("/qrcode/generate")
    public JsonResult generateQrcode(@RequestBody Map<String, Object> request) {
        try {
            Long accountSetsId = Long.valueOf(request.get("accountSetsId").toString());
            
            Map<String, Object> result = taxLoginService.generateQrcode(accountSetsId);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("生成二维码失败", e);
            return JsonResult.failure("生成二维码失败：" + e.getMessage());
        }
    }
    
    /**
     * 查询二维码登录状态
     */
    @GetMapping("/qrcode/status/{qrcodeId}")
    public JsonResult checkQrcodeStatus(@PathVariable String qrcodeId) {
        try {
            Map<String, Object> result = taxLoginService.checkQrcodeStatus(qrcodeId);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("查询二维码状态失败", e);
            return JsonResult.failure("查询状态失败：" + e.getMessage());
        }
    }
    
    /**
     * 关闭扫码登录浏览器
     */
    @PostMapping("/qrcode/close")
    public JsonResult closeQrcodeBrowser(@RequestBody Map<String, Object> request) {
        try {
            Long accountSetsId = Long.valueOf(request.get("accountSetsId").toString());
            Map<String, Object> result = taxLoginService.closeQrcodeBrowser(accountSetsId);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("关闭扫码登录浏览器失败", e);
            return JsonResult.failure("关闭浏览器失败：" + e.getMessage());
        }
    }

    /**
     * 检查办税员/法人密码登录的浏览器登录状态
     */
    @PostMapping("/status")
    public JsonResult checkLoginStatus(@RequestBody Map<String, Object> request) {
        try {
            Long accountSetsId = Long.valueOf(request.get("accountSetsId").toString());
            String phone = request.get("phone").toString();
            String loginType = request.get("loginType") != null ? request.get("loginType").toString() : "";
            Map<String, Object> result = taxLoginService.checkLoginStatus(accountSetsId, phone, loginType);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("检查登录状态失败", e);
            return JsonResult.failure("检查登录状态失败：" + e.getMessage());
        }
    }

    /**
     * 关闭办税员/法人登录的浏览器
     */
    @PostMapping("/close")
    public JsonResult closeBrowser(@RequestBody Map<String, Object> request) {
        try {
            Long accountSetsId = Long.valueOf(request.get("accountSetsId").toString());
            String phone = request.get("phone").toString();
            String loginType = request.get("loginType") != null ? request.get("loginType").toString() : "";
            Map<String, Object> result = taxLoginService.closeBrowser(accountSetsId, phone, loginType);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("关闭浏览器失败", e);
            return JsonResult.failure("关闭浏览器失败：" + e.getMessage());
        }
    }
    
    /**
     * 统计今日登录次数
     */
    @GetMapping("/count/{accountSetsId}")
    public JsonResult countTodayLogin(@PathVariable Long accountSetsId,
                                      @RequestParam(required = false) String phoneNumber) {
        try {
            Long count = taxLoginService.countTodayLogin(accountSetsId, phoneNumber);
            return JsonResult.successful(count);
        } catch (Exception e) {
            log.error("统计登录次数失败", e);
            return JsonResult.failure("统计失败：" + e.getMessage());
        }
    }
    
    /**
     * 获取所有办税员（管理用，包括停用的）
     */
    @GetMapping("/agents/all/{accountSetsId}")
    public JsonResult getAllTaxAgents(@PathVariable Long accountSetsId) {
        try {
            List<TaxLoginAccount> agents = taxLoginService.getAllTaxAgents(accountSetsId);
            return JsonResult.successful(agents);
        } catch (Exception e) {
            log.error("获取办税员列表失败", e);
            return JsonResult.failure("获取办税员列表失败：" + e.getMessage());
        }
    }
    
    /**
     * 保存办税员（新增或更新）
     */
    @PostMapping("/agents/{accountSetsId}")
    public JsonResult saveTaxAgent(@PathVariable Long accountSetsId, @RequestBody TaxLoginAccount agent) {
        try {
            TaxLoginAccount saved = taxLoginService.saveTaxAgent(accountSetsId, agent);
            return JsonResult.successful(saved);
        } catch (Exception e) {
            log.error("保存办税员失败", e);
            return JsonResult.failure("保存办税员失败：" + e.getMessage());
        }
    }
    
    /**
     * 删除办税员
     */
    @DeleteMapping("/agents/{accountSetsId}/{agentId}")
    public JsonResult deleteTaxAgent(@PathVariable Long accountSetsId, @PathVariable Long agentId) {
        try {
            taxLoginService.deleteTaxAgent(accountSetsId, agentId);
            return JsonResult.successful();
        } catch (Exception e) {
            log.error("删除办税员失败", e);
            return JsonResult.failure("删除办税员失败：" + e.getMessage());
        }
    }
    
    /**
     * 更新办税员状态
     */
    @PutMapping("/agents/{accountSetsId}/{agentId}/status")
    public JsonResult updateTaxAgentStatus(@PathVariable Long accountSetsId, 
                                          @PathVariable Long agentId,
                                          @RequestParam String status) {
        try {
            TaxLoginAccount agent = taxLoginService.updateTaxAgentStatus(accountSetsId, agentId, status);
            return JsonResult.successful(agent);
        } catch (Exception e) {
            log.error("更新办税员状态失败", e);
            return JsonResult.failure("更新办税员状态失败：" + e.getMessage());
        }
    }
    
    /**
     * 保存代理通道短信验证码到Redis（key为PROXY_+用户名，value为验证码）
     */
    @PostMapping("/proxy/smsCode")
    public JsonResult saveProxySmsCode(@RequestBody Map<String, Object> request) {
        try {
            String proxyUsername = request.get("proxyUsername").toString();
            String code = request.get("code").toString();
            taxLoginService.saveProxySmsCode(proxyUsername, code);
            return JsonResult.successful();
        } catch (Exception e) {
            log.error("保存代理通道短信验证码失败", e);
            return JsonResult.failure("保存验证码失败：" + e.getMessage());
        }
    }

    /**
     * 代理通道登录（代理业务Tab）
     */
    @PostMapping("/proxy")
    public JsonResult proxyLogin(@RequestBody Map<String, Object> request) {
        try {
            Long accountSetsId = Long.valueOf(request.get("accountSetsId").toString());
            String proxyAgencyCode = request.get("proxyAgencyCode").toString();
            String proxyUsername = request.get("proxyUsername").toString();
            String proxyPassword = request.get("proxyPassword").toString();
            Map<String, Object> result = taxLoginService.proxyLogin(accountSetsId, proxyAgencyCode, proxyUsername, proxyPassword);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("代理通道登录失败", e);
            return JsonResult.failure("登录失败：" + e.getMessage());
        }
    }

    /**
     * 获取浏览器的 CDP WebSocket URL（用于前端远程连接和实时查看）
     */
    @PostMapping("/cdp/url")
    public JsonResult getCdpWebSocketUrl(@RequestBody Map<String, Object> request) {
        try {
            Long accountSetsId = Long.valueOf(request.get("accountSetsId").toString());
            String phone = request.get("phone") != null ? request.get("phone").toString() : null;
            String loginType = request.get("loginType") != null ? request.get("loginType").toString() : "AGENT";
            
            Map<String, Object> result = taxLoginService.getCdpWebSocketUrl(accountSetsId, phone, loginType);
            return JsonResult.successful(result);
        } catch (Exception e) {
            log.error("获取 CDP WebSocket URL 失败", e);
            return JsonResult.failure("获取 CDP WebSocket URL 失败：" + e.getMessage());
        }
    }
}
