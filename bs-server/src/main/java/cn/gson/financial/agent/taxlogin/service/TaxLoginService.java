package cn.gson.financial.agent.taxlogin.service;

import cn.gson.financial.agent.taxlogin.service.TaxBrowserManagerService.BrowserMetadata;
import cn.gson.financial.crm.util.RpaUtil;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.TaxLoginAccount;
import cn.gson.financial.kernel.model.entity.TaxLoginLog;
import cn.gson.financial.kernel.model.repo.AccountSetsRepository;
import cn.gson.financial.kernel.model.repo.TaxLoginAccountRepository;
import cn.gson.financial.kernel.model.repo.TaxLoginLogRepository;
import cn.gson.financial.kernel.service.AbsService;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.tool.RedisUtil;
import cn.gson.financial.rpa.Provice2URL;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 税务登录服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TaxLoginService extends AbsService {
    
    private final TaxLoginAccountRepository loginAccountRepository;
    private final TaxLoginLogRepository loginLogRepository;
    private final AccountSetsRepository accountSetsRepository;
    private final RpaUtil rpaUtil;
    private final TaxBrowserManagerService browserManagerService;
    @Autowired
    private RedisUtil redisUtil;
    private final QAccountSets qAccountSets = QAccountSets.accountSets;

    /**
     * 获取办税员列表（只返回激活的）
     */
    public List<TaxLoginAccount> getTaxAgents(Long accountSetsId) {
        // 办税员账号与账套无关，返回所有激活状态的办税员
        List<TaxLoginAccount> all = loginAccountRepository.findAll();
        List<TaxLoginAccount> actives = new ArrayList<>();
        for (TaxLoginAccount agent : all) {
            if ("ACTIVE".equals(agent.getStatus())) {
                actives.add(agent);
            }
        }
        return actives;
    }
    
    /**
     * 办税员密码登录（默认办税员登录）
     * 使用【账套税号 + 省份 + 办税员手机号 + 密码】通过“企业业务”方式登录税务局，
     * 以"省份+手机号"作为 key 交给浏览器管理服务进行管理。
     */
    public Map<String, Object> passwordLogin(Long accountSetsId, Long agentId, String password, String loginType) {
        Map<String, Object> result = new HashMap<>();
        // 如果前端或上层未传，默认视为办税员登录
        if (StrUtil.isBlank(loginType)) {
            loginType = "AGENT";
        }
        
        // 获取账套信息
        AccountSets accountSets = accountSetsRepository.findById(accountSetsId)
                .orElseThrow(() -> new RuntimeException("账套不存在"));
        
        // 获取办税员账号（与账套无关，全局共享）
        TaxLoginAccount agent = loginAccountRepository.findById(agentId)
                .orElseThrow(() -> new RuntimeException("办税员账号不存在"));
        
        // 获取税号（统一社会信用代码）
        String taxNumber = accountSets.getCreditCode();
        if (StrUtil.isBlank(taxNumber)) {
            throw new RuntimeException("账套税号不能为空");
        }
        
        // 获取省份和手机号
        String province = accountSets.getProvince();
        String phone = agent.getPhoneNumber();
        
        if (StrUtil.isBlank(province)) {
            throw new RuntimeException("账套省份不能为空");
        }
        if (StrUtil.isBlank(phone)) {
            throw new RuntimeException("办税员手机号不能为空");
        }
        
        // 生成浏览器key：省份+手机号
        String browserKey = browserManagerService.generateBrowserKey(province, phone);
        
        // 检查是否已有浏览器且有效
        BrowserContext browserContext = browserManagerService.getBrowser(browserKey);
        boolean needLogin = (browserContext == null);
        
        try {
            if (needLogin) {
                // 创建新的浏览器上下文（开启 CDP 远程调试）
                BrowserContextWithDebug browserWithDebug = createBrowserContextWithDebug(null);
                browserContext = browserWithDebug.getContext();
                Integer debugPort = browserWithDebug.getDebugPort();
                
                // 执行登录流程（企业业务：税号 + 省份 + 手机号 + 密码）
                // 办税员登录优先使用前端传入的密码（已在管理办税员中展示），为空时退回到数据库中保存的密码
                Page page = browserContext.newPage();
                String agentPassword = StrUtil.isNotBlank(password) ? password : agent.getPassword();
                boolean loginSuccess = doLogin(page, province, taxNumber, phone, agentPassword, loginType);
                
                if (!loginSuccess) {
                    // 登录失败，关闭浏览器
                    try {
                        browserContext.close();
                    } catch (Exception e) {
                        log.warn("关闭浏览器失败", e);
                    }
                    
                    // 记录登录日志
                    recordLoginLog(accountSetsId, phone, loginType, "FAILED");
                    
                    result.put("success", false);
                    result.put("message", "登录失败，请检查账号密码");
                    return result;
                }
                
                // 登录成功，保存浏览器到管理服务（包含调试端口）
                BrowserMetadata metadata = new BrowserMetadata(
                    accountSetsId, 
                    taxNumber, 
                    province, 
                    phone, 
                    agentId
                );
                metadata.setDebugPort(debugPort);
                browserManagerService.addBrowser(browserKey, browserContext, metadata);
                
                // 等待页面加载
                page.waitForTimeout(3000);
                
                // 检查是否需要短信验证
                if (page.innerText("body").contains("短信验证")) {
                    page.getByText("短信验证", new Page.GetByTextOptions().setExact(true)).click();
                    page.getByText("获取验证码").click();
                    
                    // 记录登录日志（需要验证码）
                    recordLoginLog(accountSetsId, phone, loginType, "PENDING_SMS");
                    
                    result.put("success", true);
                    result.put("message", "登录成功，需要短信验证码");
                    result.put("needSmsCode", true);
                    return result;
                }
            } else {
                // 浏览器已存在，获取页面
                List<Page> pages = browserContext.pages();
                if (pages.isEmpty()) {
                    browserContext.newPage();
                }
            }
            
            // 办税员密码登录：登录即进入目标企业，不再做身份切换
            // 记录登录日志
            recordLoginLog(accountSetsId, phone, loginType, "SUCCESS");
            
            result.put("success", true);
            result.put("message", "登录成功");
            result.put("needSmsCode", false);
            
        } catch (Exception e) {
            log.error("登录过程中发生错误", e);
            
            // 如果登录过程中出错，清理浏览器
            if (needLogin && browserContext != null) {
                try {
                    browserContext.close();
                } catch (Exception ex) {
                    log.warn("关闭浏览器失败", ex);
                }
                browserManagerService.removeBrowser(browserKey);
            }
            
            // 记录登录日志
            recordLoginLog(accountSetsId, phone, loginType, "FAILED");
            
            result.put("success", false);
            result.put("message", "登录失败：" + e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 执行登录操作（企业业务登录）
     * 参数：省份 + 税号 + 手机号 + 密码 + 登录类型
     */
    private boolean doLogin(Page page, String province, String taxNumber, String phone, String password, String loginType) {
        String webUrl = ProvinceURLUtil.getUrl(province);
        page.navigate(webUrl);
        page.waitForSelector(".loginBtn");
        SelectiveDomFilter.keepSingleClassWithScripts(page, "loginBtn");
        page.waitForTimeout(2000);
        if (page.innerText("body").contains("我知道了")) {
            page.getByText("我知道了").click();
        }
        page.querySelector(".loginBtn").click();
     



        page.getByText("企业业务").click();
        page.getByPlaceholder("统一社会信用代码/纳税人识别号").fill(taxNumber);
        page.getByPlaceholder("居民身份证号码/手机号码/用户名").fill(phone);
        page.getByPlaceholder("个人用户密码").fill(password);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登录")).click();

        // 等待验证码区域加载
        page.waitForTimeout(500);

        // 获取提示图片（tpass-captcha-tip-img，PNG base64）
        Locator tipImg = page.locator("#tpass-captcha-tip-img");
        tipImg.waitFor();
        String tipSrc = tipImg.getAttribute("src");
        if (tipSrc == null) {
            throw new RuntimeException("未找到提示图片的 src 属性");
        }
        int tipCommaIndex = tipSrc.indexOf(",");
        String tipImgBase64;
        if (tipCommaIndex >= 0) {
            tipImgBase64 = tipSrc.substring(tipCommaIndex + 1); // 去掉 data:image/png;base64, 前缀
        } else {
            tipImgBase64 = tipSrc;
        }

        // 获取选择图片（tpass-captcha-slider-bg-img，JPEG base64）
        Locator sliderBgImg = page.locator("#tpass-captcha-slider-bg-img");
        sliderBgImg.waitFor();
        String sliderSrc = sliderBgImg.getAttribute("src");
        if (sliderSrc == null) {
            throw new RuntimeException("未找到选择图片的 src 属性");
        }
        int sliderCommaIndex = sliderSrc.indexOf(",");
        String sliderImgBase64;
        if (sliderCommaIndex >= 0) {
            sliderImgBase64 = sliderSrc.substring(sliderCommaIndex + 1); // 去掉 data:image/jpeg;base64, 前缀
        } else {
            sliderImgBase64 = sliderSrc;
        }

        // 调用封装方法，识别验证码并获取按X轴排序的坐标
        List<int[]> points = ChaoJiYing.solveClickCaptchaWithTwoImages(tipImgBase64, sliderImgBase64);

        // 等待图片完全加载并获取选择图片的位置
        page.waitForTimeout(300);  // 确保图片完全渲染
        BoundingBox box = sliderBgImg.boundingBox();
        if (box == null) {
            throw new RuntimeException("无法获取选择图片的 BoundingBox");
        }

        // 超级鹰识别时使用的图片尺寸（固定值）
        final int CAPTCHA_IMAGE_WIDTH = 550;
        final int CAPTCHA_IMAGE_HEIGHT = 330;

        // 获取网页上图片的实际显示尺寸
        double displayWidth = box.width;
        double displayHeight = box.height;

        // 计算缩放比例
        double scaleX = displayWidth / CAPTCHA_IMAGE_WIDTH;
        double scaleY = displayHeight / CAPTCHA_IMAGE_HEIGHT;

        System.out.println("验证码识别图片尺寸: " + CAPTCHA_IMAGE_WIDTH + "x" + CAPTCHA_IMAGE_HEIGHT);
        System.out.println("网页图片显示尺寸: " + displayWidth + "x" + displayHeight);
        System.out.println("缩放比例 - scaleX: " + scaleX + ", scaleY: " + scaleY);
        System.out.println("图片位置 - x: " + box.x + ", y: " + box.y);

        // 按照X轴排序后的坐标依次点击
        // 坐标是相对于图片左上角的，需要按比例缩放后加上图片在页面上的位置
        for (int i = 0; i < points.size(); i++) {
            int[] point = points.get(i);
            // 将超级鹰返回的坐标按比例缩放到网页图片的实际尺寸
            double scaledX = point[0] * scaleX;
            double scaledY = point[1] * scaleY;

            // 计算绝对坐标：图片左上角坐标 + 缩放后的相对坐标
            int absoluteX = (int) Math.round(box.x + scaledX);
            int absoluteY = (int) Math.round(box.y + scaledY);

            System.out.println("点击第 " + (i + 1) + " 个坐标 - 原始坐标: (" + point[0] + ", " + point[1] + "), 缩放后: (" + scaledX + ", " + scaledY + "), 绝对坐标: (" + absoluteX + ", " + absoluteY + ")");

            // 先移动到目标位置
            page.mouse().move(absoluteX, absoluteY);
            page.waitForTimeout(100);  // 模拟人类操作，稍微延迟

            // 点击（使用精确的坐标）
            page.mouse().click(absoluteX, absoluteY);
            page.waitForTimeout(200);  // 每次点击后延迟
        }
        page.getByText("确定").click();
        // 等待验证码验证完成
        page.waitForTimeout(1000);
        if(page.innerText("body").contains("短信验证")){
            page.getByText("短信验证", new Page.GetByTextOptions().setExact(true)).click();
        }

        page.waitForSelector(".el-row.el-row--flex:has-text('获取验证码')");
        page.getByText("获取验证码").click();
        page.waitForTimeout(1000);

        String code;
        if ("LEGAL".equalsIgnoreCase(loginType)) {
            // 法人登录：从Redis按公司税号读取验证码，最多等待5分钟
        String redisKey = taxNumber;
            long deadline = System.currentTimeMillis() + 5 * 60 * 1000L;
            code = null;
            while (System.currentTimeMillis() < deadline) {
                Object val = redisUtil.get(redisKey);
                if (val != null && StrUtil.isNotBlank(val.toString())) {
                    code = val.toString().trim();
                    break;
                }
            // 每10秒检查一次
            page.waitForTimeout(10000);
            }
            if (StrUtil.isBlank(code)) {
                throw new RuntimeException("在5分钟内未收到短信验证码，请重新登录后重试");
            }
        } else {
            // 办税员登录：保持原有从Redis中按省份+标识获取验证码的逻辑
            code = getCode(province,"ka2");
            int i = 0;
            while (code == null || code.trim().isEmpty()) {
                try {
                    i++;
                    if (i > 10) {
                        break;
                    }
                    // 等待10秒（10000毫秒）
                    page.waitForTimeout(10000);
                    // 在这里发送请求``
                    code = getCode(province,"ka2");

                } catch (Exception e) {
                    i++;
                    if (i > 10) {
                        break ;
                    }
                }
            }
            int j = 0;
            while (code == null || code.trim().isEmpty()) {
                //如果还是为空则进行第二次重新发送
                page.waitForTimeout(85000);
                if(page.isVisible(".el-button.codeDivCls.btnDisables.el-button--primary('重新发送')")){
                    page.getByText("重新发送").click();
                }
                if(page.isVisible(".el-row.el-row--flex:has-text('获取验证码')")){
                    page.getByText("获取验证码").click();
                }
                try {
                    j++;
                    if (j > 10) {
                        break;
                    }
                    // 等待10秒（10000毫秒）
                    page.waitForTimeout(10000);

                    // 在这里发送请求``
                    code = getCode(province,"CheckCode");
                } catch (Exception e) {
                    j++;
                    if (j > 10) {
                        break ;
                    }
                }
            }
            if (code == null || code.trim().isEmpty()) {
                throw new RuntimeException("获取验证码失败");
            }
        }

        page.getByPlaceholder("请输入短信验证码").fill(code);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登录")).click();
        page.evaluate("() => {"+
                "       const mask = document.querySelector('.mask.show');"+
                "if (mask) {"+
                "mask.remove();"+
                "       }"+
                "return mask !== null;"+
                "}");
        if (page.isVisible(".tit:has-text('温馨提示')", new Page.IsVisibleOptions())) {
            // page.querySelector(".t-button__text:has-text('同意签订')").click();
            page.reload();
        }
        // 能执行到这里，说明登录流程完整成功
        return true;
    }
    
    /**
     * 根据税号切换身份
     */
    private boolean changeIdentity(Page page, String taxNumber) {
        try {
            page.waitForTimeout(2000);
            
            // 检查当前页面是否已包含该税号
            if (page.innerText("body").contains(taxNumber)) {
                return true;
            }
            
            // 执行身份切换
            page.hover(".t-menu__operations");
            page.waitForTimeout(500);
            page.getByText("身份切换").click();
            page.waitForTimeout(2000);
            
            // 填写统一社会信用代码
            page.getByPlaceholder("请输入统一社会信用代码").fill(taxNumber);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("查询")).click();
            page.waitForTimeout(3000);
            
            // 检查是否找到企业
            if (page.innerText("body").contains("共 1 条")) {
                // 点击切换或进入按钮
                if (page.innerText("body").contains("进入")) {
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("进入")).click();
                } else {
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("切换")).first().click();
                }
                page.waitForTimeout(2000);
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("确定")).click();
                page.waitForTimeout(2000);
                return true;
            } else {
                // 未找到企业，点击返回
                try {
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("返回")).click();
                } catch (Exception e) {
                    log.warn("点击返回按钮失败", e);
                }
                return false;
            }
        } catch (Exception e) {
            log.error("切换身份失败: 税号={}", taxNumber, e);
            return false;
        }
    }
    
    /**
     * 创建浏览器上下文（支持 CDP 远程调试）
     * @param debugPort CDP 远程调试端口，如果为 null 则不开启调试
     * @return BrowserContext 和 debugPort 的包装对象
     */
    private BrowserContextWithDebug createBrowserContextWithDebug(Integer debugPort) {
        Playwright playwright = Playwright.create();
        
        // 动态分配调试端口（如果未指定）
        if (debugPort == null) {
            debugPort = findAvailablePort(9222, 9300);
        }
        
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                .setHeadless(false)
                .setSlowMo(200)
                .setArgs(java.util.Arrays.asList(
                    "--disable-blink-features=AutomationControlled",
                    "--remote-debugging-port=" + debugPort
                ));
        
        Browser browser = playwright.chromium().launch(launchOptions);
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true)
                .setJavaScriptEnabled(true));
        
        return new BrowserContextWithDebug(context, debugPort);
    }
    
    /**
     * 创建浏览器上下文（兼容旧接口）
     */
    private BrowserContext createBrowserContext() {
        return createBrowserContextWithDebug(null).getContext();
    }
    
    /**
     * 查找可用端口
     */
    private Integer findAvailablePort(int startPort, int endPort) {
        for (int port = startPort; port <= endPort; port++) {
            try {
                java.net.ServerSocket socket = new java.net.ServerSocket(port);
                socket.close();
                return port;
            } catch (java.io.IOException e) {
                // 端口被占用，继续查找
            }
        }
        // 如果所有端口都被占用，返回默认端口（可能会失败，但至少尝试）
        return 9222;
    }
    
    /**
     * 浏览器上下文和调试端口的包装类
     */
    private static class BrowserContextWithDebug {
        private final BrowserContext context;
        private final Integer debugPort;
        
        public BrowserContextWithDebug(BrowserContext context, Integer debugPort) {
            this.context = context;
            this.debugPort = debugPort;
        }
        
        public BrowserContext getContext() {
            return context;
        }
        
        public Integer getDebugPort() {
            return debugPort;
        }
    }
    
    /**
     * 记录登录日志
     */
    private void recordLoginLog(Long accountSetsId, String phone, String loginType, String status) {
        try {
            TaxLoginLog log = new TaxLoginLog();
            log.setAccountSetsId(accountSetsId);
            // 使用传入的登录方式标记：AGENT / LEGAL / QRCODE
            log.setLoginType(loginType);
            log.setPhoneNumber(phone);
            log.setLoginTime(LocalDateTime.now());
            log.setLoginStatus(status);
            log.setCreateTime(LocalDateTime.now());
            loginLogRepository.save(log);
        } catch (Exception e) {
            log.warn("记录登录日志失败", e);
        }
    }
    
    /**
     * 获取短信验证码（模拟接口）
     */
    public Map<String, Object> getSmsCode(Long accountSetsId, String phone) {
        Map<String, Object> result = new HashMap<>();
        
        // 验证手机号格式
        if (StrUtil.isBlank(phone) || !phone.matches("^1[3-9]\\d{9}$")) {
            throw new RuntimeException("手机号格式不正确");
        }
        
        // TODO: 实际应该调用短信服务发送验证码
        // 这里返回模拟的验证码（实际应该通过短信发送）
        String code = "123456"; // 模拟验证码
        
        result.put("success", true);
        result.put("message", "验证码已发送");
        result.put("code", code); // 仅用于测试，生产环境不应返回
        
        return result;
    }
    
    /**
     * 短信验证码登录（法人短信登录）
     */
    public Map<String, Object> smsLogin(Long accountSetsId, String phone, String code) {
        Map<String, Object> result = new HashMap<>();
        
        // 获取账套信息
        AccountSets accountSets = accountSetsRepository.findById(accountSetsId)
                .orElseThrow(() -> new RuntimeException("账套不存在"));
        
        String province = accountSets.getProvince();
        String taxSystemKey = province + "*" + phone;
        
        // 验证验证码（这里简化处理，实际应该验证短信验证码）
        // TODO: 实际应该验证短信验证码是否有效
        
        // 调用RPA验证码登录
        boolean loginSuccess = rpaUtil.checkTaxSystemCode(code, taxSystemKey);
        
        // 记录登录日志（法人登录）
        TaxLoginLog log = new TaxLoginLog();
        log.setAccountSetsId(accountSetsId);
        log.setLoginType("LEGAL");
        log.setPhoneNumber(phone);
        log.setLoginTime(LocalDateTime.now());
        log.setLoginStatus(loginSuccess ? "SUCCESS" : "FAILED");
        log.setCreateTime(LocalDateTime.now());
        loginLogRepository.save(log);
        
        result.put("success", loginSuccess);
        result.put("message", loginSuccess ? "登录成功" : "验证码错误或已过期");
        
        return result;
    }

    /**
     * 保存法人短信验证码到Redis（key为公司税号，value为验证码，有效期5分钟）
     */
    public void saveLegalSmsCode(Long accountSetsId, String code) {
        if (StrUtil.isBlank(code)) {
            throw new RuntimeException("验证码不能为空");
        }
        AccountSets accountSets = accountSetsRepository.findById(accountSetsId)
                .orElseThrow(() -> new RuntimeException("账套不存在"));
        String taxNumber = accountSets.getCreditCode();
        if (StrUtil.isBlank(taxNumber)) {
            throw new RuntimeException("账套税号不能为空");
        }
        // key为公司税号，value为验证码，有效期5分钟
        redisUtil.set(taxNumber, code, 300);
    }
    
    /**
     * 保存代理通道短信验证码到Redis（key为PROXY_+用户名，有效期5分钟）
     */
    public void saveProxySmsCode(String proxyUsername, String code) {
        if (StrUtil.isBlank(code)) {
            throw new RuntimeException("验证码不能为空");
        }
        if (StrUtil.isBlank(proxyUsername)) {
            throw new RuntimeException("用户名不能为空");
        }
        redisUtil.set("PROXY_" + proxyUsername, code, 300);
    }

    /**
     * 法人账号密码登录（与办税员密码登录同流程，但使用输入的手机号+密码）
     */
    public Map<String, Object> legalPasswordLogin(Long accountSetsId, String phone, String password) {
        Map<String, Object> result = new HashMap<>();
        
        // 获取账套信息
        AccountSets accountSets = accountSetsRepository.findById(accountSetsId)
                .orElseThrow(() -> new RuntimeException("账套不存在"));
        
        // 获取税号（统一社会信用代码）
        String taxNumber = accountSets.getCreditCode();
        if (StrUtil.isBlank(taxNumber)) {
            throw new RuntimeException("账套税号不能为空");
        }
        
        // 获取省份和手机号
        String province = accountSets.getProvince();
        
        if (StrUtil.isBlank(province)) {
            throw new RuntimeException("账套省份不能为空");
        }
        if (StrUtil.isBlank(phone)) {
            throw new RuntimeException("手机号不能为空");
        }
        
        String loginType = "LEGAL";
        
        // 生成浏览器key：省份+手机号
        String browserKey = browserManagerService.generateBrowserKey(province, phone);
        
        // 检查是否已有浏览器且有效
        BrowserContext browserContext = browserManagerService.getBrowser(browserKey);
        boolean needLogin = (browserContext == null);
        
        try {
            if (needLogin) {
                // 创建新的浏览器上下文（开启 CDP 远程调试）
                BrowserContextWithDebug browserWithDebug = createBrowserContextWithDebug(null);
                browserContext = browserWithDebug.getContext();
                Integer debugPort = browserWithDebug.getDebugPort();
                
                // 执行登录流程（企业业务：税号 + 省份 + 手机号 + 密码）
                Page page = browserContext.newPage();
                boolean loginSuccess = doLogin(page, province, taxNumber, phone, password, loginType);
                
                if (!loginSuccess) {
                    // 登录失败，关闭浏览器
                    try {
                        browserContext.close();
                    } catch (Exception e) {
                        log.warn("关闭浏览器失败", e);
                    }
                    
                    // 记录登录日志
                    recordLoginLog(accountSetsId, phone, loginType, "FAILED");
                    
                    result.put("success", false);
                    result.put("message", "登录失败，请检查账号密码");
                    return result;
                }
                
                // 登录成功，保存浏览器到管理服务（包含调试端口）
                TaxBrowserManagerService.BrowserMetadata metadata = new TaxBrowserManagerService.BrowserMetadata(
                    accountSetsId, 
                    taxNumber, 
                    province, 
                    phone, 
                    null // 法人登录没有办税员ID
                );
                metadata.setDebugPort(debugPort);
                browserManagerService.addBrowser(browserKey, browserContext, metadata);
                
                // 等待页面加载
                page.waitForTimeout(3000);
            } else {
                // 浏览器已存在，获取页面
                List<Page> pages = browserContext.pages();
                if (pages.isEmpty()) {
                    browserContext.newPage();
                }
            }
            
            // 法人账号密码登录（验证码登录）：登录即进入目标企业，不再做身份切换
            // 记录登录日志
            recordLoginLog(accountSetsId, phone, loginType, "SUCCESS");
            
            result.put("success", true);
            result.put("message", "登录成功");
            result.put("needSmsCode", false);
            
        } catch (Exception e) {
            log.error("法人账号密码登录过程中发生错误", e);
            
            // 如果登录过程中出错，清理浏览器
            if (needLogin && browserContext != null) {
                try {
                    browserContext.close();
                } catch (Exception ex) {
                    log.warn("关闭浏览器失败", ex);
                }
                browserManagerService.removeBrowser(browserKey);
            }
            
            // 记录登录日志
            recordLoginLog(accountSetsId, phone, loginType, "FAILED");
            
            result.put("success", false);
            result.put("message", "登录失败：" + e.getMessage());
        }
        
        return result;
    }

    /**
     * 代理通道登录（代理业务Tab）
     * 使用【代理机构统一社会信用代码 + 居民身份证号码/手机号码/用户名 + 密码】通过"代理业务"方式登录税务局
     */
    public Map<String, Object> proxyLogin(Long accountSetsId, String proxyAgencyCode, String proxyUsername, String proxyPassword) {
        Map<String, Object> result = new HashMap<>();

        // 获取账套信息（用于获取省份）
        AccountSets accountSets = accountSetsRepository.findById(accountSetsId)
                .orElseThrow(() -> new RuntimeException("账套不存在"));

        String province = accountSets.getProvince();
        if (StrUtil.isBlank(province)) {
            throw new RuntimeException("账套省份不能为空");
        }
        if (StrUtil.isBlank(proxyAgencyCode)) {
            throw new RuntimeException("代理机构统一社会信用代码不能为空");
        }
        if (StrUtil.isBlank(proxyUsername)) {
            throw new RuntimeException("用户名不能为空");
        }

        String loginType = "PROXY";

        // 生成浏览器key：省份 + PROXY_ + 用户名
        String browserKey = browserManagerService.generateBrowserKey(province, "PROXY_" + proxyUsername);

        // 检查是否已有浏览器且有效
        BrowserContext browserContext = browserManagerService.getBrowser(browserKey);
        boolean needLogin = (browserContext == null);

        try {
            if (needLogin) {
                // 创建新的浏览器上下文（开启 CDP 远程调试）
                BrowserContextWithDebug browserWithDebug = createBrowserContextWithDebug(null);
                browserContext = browserWithDebug.getContext();
                Integer debugPort = browserWithDebug.getDebugPort();

                // 执行代理业务登录流程
                Page page = browserContext.newPage();
                boolean loginSuccess = doProxyLogin(page, province, proxyAgencyCode, proxyUsername, proxyPassword);

                if (!loginSuccess) {
                    try {
                        browserContext.close();
                    } catch (Exception e) {
                        log.warn("关闭浏览器失败", e);
                    }
                    recordLoginLog(accountSetsId, proxyUsername, loginType, "FAILED");
                    result.put("success", false);
                    result.put("message", "登录失败，请检查账号密码");
                    return result;
                }

                // 登录成功，保存浏览器到管理服务
                TaxBrowserManagerService.BrowserMetadata metadata = new TaxBrowserManagerService.BrowserMetadata(
                    accountSetsId,
                    proxyAgencyCode,
                    province,
                    proxyUsername,
                    null
                );
                metadata.setDebugPort(debugPort);
                browserManagerService.addBrowser(browserKey, browserContext, metadata);

                page.waitForTimeout(3000);
            } else {
                List<Page> pages = browserContext.pages();
                if (pages.isEmpty()) {
                    browserContext.newPage();
                }
            }

            recordLoginLog(accountSetsId, proxyUsername, loginType, "SUCCESS");
            result.put("success", true);
            result.put("message", "代理通道登录成功");
            result.put("needSmsCode", false);

        } catch (Exception e) {
            log.error("代理通道登录过程中发生错误", e);
            if (needLogin && browserContext != null) {
                try {
                    browserContext.close();
                } catch (Exception ex) {
                    log.warn("关闭浏览器失败", ex);
                }
                browserManagerService.removeBrowser(browserKey);
            }
            recordLoginLog(accountSetsId, proxyUsername, loginType, "FAILED");
            result.put("success", false);
            result.put("message", "登录失败：" + e.getMessage());
        }

        return result;
    }

    /**
     * 代理业务Tab登录的自动化流程
     * 与 doLogin 的区别：点击"代理业务"Tab，第一个字段填写代理机构统一社会信用代码
     */
    private boolean doProxyLogin(Page page, String province, String proxyAgencyCode, String proxyUsername, String proxyPassword) {
        String webUrl = ProvinceURLUtil.getUrl(province);
        page.navigate(webUrl);
        page.waitForSelector(".loginBtn");
        SelectiveDomFilter.keepSingleClassWithScripts(page, "loginBtn");
        page.waitForTimeout(2000);
        if (page.innerText("body").contains("我知道了")) {
            page.getByText("我知道了").click();
        }
        page.querySelector(".loginBtn").click();

        // 点击"代理业务"Tab（区别于企业业务）
        page.getByText("代理业务").click();
        page.getByPlaceholder("代理机构统一社会信用代码/纳税人识别号").fill(proxyAgencyCode);
        page.getByPlaceholder("居民身份证号码/手机号码/用户名").fill(proxyUsername);
        page.getByPlaceholder("个人用户密码").fill(proxyPassword);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登录")).click();

        // 等待验证码区域加载
        page.waitForTimeout(500);

        // 获取提示图片（tpass-captcha-tip-img，PNG base64）
        Locator tipImg = page.locator("#tpass-captcha-tip-img");
        tipImg.waitFor();
        String tipSrc = tipImg.getAttribute("src");
        if (tipSrc == null) {
            throw new RuntimeException("未找到提示图片的 src 属性");
        }
        int tipCommaIndex = tipSrc.indexOf(",");
        String tipImgBase64 = tipCommaIndex >= 0 ? tipSrc.substring(tipCommaIndex + 1) : tipSrc;

        // 获取选择图片（tpass-captcha-slider-bg-img，JPEG base64）
        Locator sliderBgImg = page.locator("#tpass-captcha-slider-bg-img");
        sliderBgImg.waitFor();
        String sliderSrc = sliderBgImg.getAttribute("src");
        if (sliderSrc == null) {
            throw new RuntimeException("未找到选择图片的 src 属性");
        }
        int sliderCommaIndex = sliderSrc.indexOf(",");
        String sliderImgBase64 = sliderCommaIndex >= 0 ? sliderSrc.substring(sliderCommaIndex + 1) : sliderSrc;

        // 调用超级鹰识别验证码坐标
        List<int[]> points = ChaoJiYing.solveClickCaptchaWithTwoImages(tipImgBase64, sliderImgBase64);

        page.waitForTimeout(300);
        BoundingBox box = sliderBgImg.boundingBox();
        if (box == null) {
            throw new RuntimeException("无法获取选择图片的 BoundingBox");
        }

        final int CAPTCHA_IMAGE_WIDTH = 550;
        final int CAPTCHA_IMAGE_HEIGHT = 330;
        double scaleX = box.width / CAPTCHA_IMAGE_WIDTH;
        double scaleY = box.height / CAPTCHA_IMAGE_HEIGHT;

        for (int[] point : points) {
            int absoluteX = (int) Math.round(box.x + point[0] * scaleX);
            int absoluteY = (int) Math.round(box.y + point[1] * scaleY);
            page.mouse().move(absoluteX, absoluteY);
            page.waitForTimeout(100);
            page.mouse().click(absoluteX, absoluteY);
            page.waitForTimeout(200);
        }
        page.getByText("确定").click();
        page.waitForTimeout(1000);

        if (page.innerText("body").contains("短信验证")) {
            page.getByText("短信验证", new Page.GetByTextOptions().setExact(true)).click();
        }

        page.waitForSelector(".el-row.el-row--flex:has-text('获取验证码')");
        page.getByText("获取验证码").click();
        page.waitForTimeout(1000);

        // 代理业务登录：从Redis按代理用户名读取验证码，最多等待5分钟
        String redisKey = "PROXY_" + proxyUsername;
        long deadline = System.currentTimeMillis() + 5 * 60 * 1000L;
        String code = null;
        while (System.currentTimeMillis() < deadline) {
            Object val = redisUtil.get(redisKey);
            if (val != null && StrUtil.isNotBlank(val.toString())) {
                code = val.toString().trim();
                break;
            }
            page.waitForTimeout(10000);
        }
        if (StrUtil.isBlank(code)) {
            throw new RuntimeException("在5分钟内未收到短信验证码，请重新登录后重试");
        }

        page.getByPlaceholder("请输入短信验证码").fill(code);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登录")).click();
        page.waitForTimeout(3000);

        return !page.url().contains("login");
    }

    /**
     * 根据账套和手机号检查当前浏览器登录状态（办税员/法人密码登录）
     */
    public Map<String, Object> checkLoginStatus(Long accountSetsId, String phone, String loginType) {
        Map<String, Object> result = new HashMap<>();

        AccountSets accountSets = accountSetsRepository.findById(accountSetsId)
                .orElse(null);
        if (accountSets == null) {
            result.put("status", "EXPIRED");
            result.put("message", "账套不存在，登录会话已失效");
            return result;
        }
        String province = accountSets.getProvince();
        if (StrUtil.isBlank(province)) {
            result.put("status", "EXPIRED");
            result.put("message", "账套省份缺失，登录会话已失效");
            return result;
        }
        if (StrUtil.isBlank(phone)) {
            result.put("status", "EXPIRED");
            result.put("message", "手机号为空，无法匹配登录会话");
            return result;
        }

        String browserKey = browserManagerService.generateBrowserKey(province, phone);
        BrowserContext context = browserManagerService.getBrowser(browserKey);
        if (context == null) {
            result.put("status", "EXPIRED");
            result.put("message", "浏览器已关闭或会话失效，请重新登录");
            return result;
        }

        try {
            Page page;
            if (context.pages().isEmpty()) {
                page = context.newPage();
            } else {
                page = context.pages().get(0);
            }

            String url = page.url();
            if (url != null && url.toLowerCase().contains("login")) {
                result.put("status", "PENDING");
                result.put("message", "尚未完成登录，请稍后再试");
            } else {
                result.put("status", "SUCCESS");
                result.put("message", "登录已完成");
            }
        } catch (Exception e) {
            log.error("检查登录状态失败", e);
            result.put("status", "PENDING");
            result.put("message", "检查登录状态失败，请稍后重试");
        }

        return result;
    }

    /**
     * 根据账套和手机号关闭浏览器（办税员/法人密码登录）
     */
    public Map<String, Object> closeBrowser(Long accountSetsId, String phone, String loginType) {
        Map<String, Object> result = new HashMap<>();

        AccountSets accountSets = accountSetsRepository.findById(accountSetsId)
                .orElse(null);
        if (accountSets == null) {
            result.put("success", false);
            result.put("message", "账套不存在");
            return result;
        }
        String province = accountSets.getProvince();
        if (StrUtil.isBlank(province)) {
            result.put("success", false);
            result.put("message", "账套省份缺失，无法关闭浏览器");
            return result;
        }
        if (StrUtil.isBlank(phone)) {
            result.put("success", false);
            result.put("message", "手机号为空，无法匹配浏览器");
            return result;
        }

        String browserKey = browserManagerService.generateBrowserKey(province, phone);
        if (!browserManagerService.hasBrowser(browserKey)) {
            result.put("success", true);
            result.put("message", "浏览器已关闭");
            return result;
        }

        browserManagerService.removeBrowser(browserKey);
        result.put("success", true);
        result.put("message", "浏览器已关闭");
        return result;
    }
    
    /**
     * 生成扫码登录二维码：
     * 根据账套省份打开对应税务局网站，进入登录页并获取二维码图片。
     * 浏览器会通过 TaxBrowserManagerService 保持打开状态，等待用户扫码。
     */
    public Map<String, Object> generateQrcode(Long accountSetsId) {
        Map<String, Object> result = new HashMap<>();

        // 查账套，获取省份和税号
        AccountSets accountSets = accountSetsRepository.findById(accountSetsId)
                .orElseThrow(() -> new RuntimeException("账套不存在"));
        String province = accountSets.getProvince();
        if (StrUtil.isBlank(province)) {
            throw new RuntimeException("账套省份不能为空");
        }
        String taxNumber = accountSets.getCreditCode();

        // 使用「省份 + 特殊标识」作为扫码登录浏览器 key
        String browserKey = browserManagerService.generateBrowserKey(province, "QRCODE_" + accountSetsId);

        try {
            // 复用或创建浏览器上下文（开启 CDP 远程调试）
            BrowserContext context = browserManagerService.getBrowser(browserKey);
            if (context == null) {
                BrowserContextWithDebug browserWithDebug = createBrowserContextWithDebug(null);
                context = browserWithDebug.getContext();
                Integer debugPort = browserWithDebug.getDebugPort();
                TaxBrowserManagerService.BrowserMetadata metadata =
                        new TaxBrowserManagerService.BrowserMetadata(accountSetsId, taxNumber, province, null, null);
                metadata.setDebugPort(debugPort);
                browserManagerService.addBrowser(browserKey, context, metadata);
            }

            Page page;
            if (context.pages().isEmpty()) {
                page = context.newPage();
            } else {
                page = context.pages().get(0);
            }

            String webUrl = Provice2URL.getUrl(province);
            if (StrUtil.isBlank(webUrl)) {
                throw new RuntimeException("未找到省份对应的税局URL");
            }

            // 打开省级电子税务局首页
            page.navigate(webUrl);
            page.waitForSelector(".loginBtn");
            page.waitForTimeout(2000);

            // 关闭“我知道了”等弹窗
            if (page.innerText("body").contains("我知道了")) {
                page.getByText("我知道了").click();
            }

            // 进入登录页
            page.querySelector(".loginBtn").click();
            page.waitForTimeout(1000);

            // 只获取二维码图片：优先从 #qrcodeDiv 下的 <img> 读取 data URL
            Locator qrImg = page.locator("#qrcodeDiv img");
            qrImg.waitFor();

            String src = qrImg.getAttribute("src");
            if (StrUtil.isBlank(src)) {
                throw new RuntimeException("未找到二维码图片的 src 属性");
            }

            String base64;
            // 如果是 data:image/png;base64,XXX 这种内嵌格式，直接截取逗号后的部分
            int commaIndex = src.indexOf(",");
            if (commaIndex >= 0) {
                base64 = src.substring(commaIndex + 1);
            } else {
                // 否则退化为对二维码元素本身截图，避免截整页
                byte[] bytes = qrImg.screenshot();
                base64 = java.util.Base64.getEncoder().encodeToString(bytes);
            }

            String dataUrl = "data:image/png;base64," + base64;

            result.put("success", true);
            result.put("qrcodeImage", dataUrl);
            // 使用账套ID作为 qrcodeId，后续验证时根据账套ID检查登录状态
            result.put("qrcodeId", String.valueOf(accountSetsId));
            result.put("message", "二维码已生成，请使用手机扫码登录");
            return result;
        } catch (Exception e) {
            log.error("生成扫码登录二维码失败", e);
            result.put("success", false);
            result.put("message", "生成二维码失败：" + e.getMessage());
            return result;
        }
    }
    
    /**
     * 查询二维码登录状态
     * 现在使用 qrcodeId = 账套ID，通过 TaxBrowserManagerService 找到对应浏览器，
     * 根据当前页面 URL 判断是否已经完成登录。
     */
    public Map<String, Object> checkQrcodeStatus(String qrcodeId) {
        Map<String, Object> result = new HashMap<>();

        Long accountSetsId;
        try {
            accountSetsId = Long.valueOf(qrcodeId);
        } catch (Exception e) {
            result.put("status", "EXPIRED");
            result.put("message", "二维码无效，请重新获取");
            return result;
        }

        AccountSets accountSets = accountSetsRepository.findById(accountSetsId)
                .orElse(null);
        if (accountSets == null) {
            result.put("status", "EXPIRED");
            result.put("message", "账套不存在，二维码已失效");
            return result;
        }
        String province = accountSets.getProvince();
        if (StrUtil.isBlank(province)) {
            result.put("status", "EXPIRED");
            result.put("message", "账套省份缺失，二维码已失效");
            return result;
        }

        String browserKey = browserManagerService.generateBrowserKey(province, "QRCODE_" + accountSetsId);
        BrowserContext context = browserManagerService.getBrowser(browserKey);
        if (context == null) {
            result.put("status", "EXPIRED");
            result.put("message", "浏览器已关闭或会话失效，请重新打开税务局获取二维码");
            return result;
        }

        try {
            Page page;
            if (context.pages().isEmpty()) {
                page = context.newPage();
            } else {
                page = context.pages().get(0);
            }

            String url = page.url();
            // 如果仍然在登录页面，认为尚未完成登录
            if (url != null && url.toLowerCase().contains("login")) {
                result.put("status", "PENDING");
                result.put("message", "尚未完成扫码登录，请稍后再试");
            } else {
                // 已经通过扫码登录成功，接下来根据当前账套税号切换到对应企业
                String taxNumber = accountSets.getCreditCode();         
                if (StrUtil.isBlank(taxNumber)) {
                    result.put("status", "SUCCESS");
                    result.put("message", "扫码登录已完成，但账套税号为空，无法自动切换企业");
                } else {
                    boolean switchSuccess = changeIdentity(page, taxNumber);
                    if (switchSuccess) {
                        result.put("status", "SUCCESS");
                        result.put("message", "扫码登录已完成并已切换到当前账套对应企业");
                    } else {
                        // 切换失败时，不直接报错，提示前端稍后重试或手工检查
                        result.put("status", "PENDING");
                        result.put("message", "扫码登录已完成，但暂未能切换到指定企业，请稍后重试或手动检查");
                    }
                }
            }
        } catch (Exception e) {
            log.error("检查二维码登录状态失败", e);
            result.put("status", "PENDING");
            result.put("message", "检查登录状态失败，请稍后重试");
        }

        return result;
    }

    /**
     * 关闭当前账套对应的扫码登录浏览器
     */
    public Map<String, Object> closeQrcodeBrowser(Long accountSetsId) {
        Map<String, Object> result = new HashMap<>();

        AccountSets accountSets = accountSetsRepository.findById(accountSetsId)
                .orElse(null);
        if (accountSets == null) {
            result.put("success", false);
            result.put("message", "账套不存在");
            return result;
        }
        String province = accountSets.getProvince();
        if (StrUtil.isBlank(province)) {
            result.put("success", false);
            result.put("message", "账套省份缺失，无法关闭浏览器");
            return result;
        }

        String browserKey = browserManagerService.generateBrowserKey(province, "QRCODE_" + accountSetsId);
        if (!browserManagerService.hasBrowser(browserKey)) {
            result.put("success", true);
            result.put("message", "浏览器已关闭");
            return result;
        }

        browserManagerService.removeBrowser(browserKey);
        result.put("success", true);
        result.put("message", "浏览器已关闭");
        return result;
    }
    
    /**
     * 获取浏览器的 CDP WebSocket URL（用于前端远程连接和实时查看）
     * @param accountSetsId 账套ID
     * @param phone 手机号（办税员登录）或法人手机号（法人登录），扫码登录传 null
     * @param loginType 登录类型：AGENT / LEGAL / QRCODE
     * @return CDP WebSocket URL，如果浏览器不存在或未开启调试则返回 null
     */
    public Map<String, Object> getCdpWebSocketUrl(Long accountSetsId, String phone, String loginType) {
        Map<String, Object> result = new HashMap<>();
        
        AccountSets accountSets = accountSetsRepository.findById(accountSetsId).orElse(null);
        if (accountSets == null) {
            result.put("success", false);
            result.put("message", "账套不存在");
            return result;
        }
        
        String province = accountSets.getProvince();
        if (StrUtil.isBlank(province)) {
            result.put("success", false);
            result.put("message", "账套省份缺失");
            return result;
        }
        
        String browserKey;
        if ("QRCODE".equalsIgnoreCase(loginType)) {
            browserKey = browserManagerService.generateBrowserKey(province, "QRCODE_" + accountSetsId);
        } else {
            if (StrUtil.isBlank(phone)) {
                result.put("success", false);
                result.put("message", "手机号不能为空");
                return result;
            }
            browserKey = browserManagerService.generateBrowserKey(province, phone);
        }
        
        String wsUrl = browserManagerService.getCdpWebSocketUrl(browserKey);
        if (StrUtil.isBlank(wsUrl)) {
            result.put("success", false);
            result.put("message", "浏览器不存在或未开启远程调试");
            return result;
        }
        
        result.put("success", true);
        result.put("cdpWebSocketUrl", wsUrl);
        result.put("message", "获取 CDP WebSocket URL 成功");
        return result;
    }
    
    /**
     * 统计今日登录次数
     */
    public Long countTodayLogin(Long accountSetsId, String phoneNumber) {
        if (StrUtil.isNotBlank(phoneNumber)) {
            return loginLogRepository.countTodayLoginByAccountSetsIdAndPhoneNumber(accountSetsId, phoneNumber);
        } else {
            return loginLogRepository.countTodayLoginByAccountSetsId(accountSetsId);
        }
    }
    
    /**
     * 获取所有办税员（包括停用的）
     */
    public List<TaxLoginAccount> getAllTaxAgents(Long accountSetsId) {
        // 办税员账号与账套无关，返回全部
        return loginAccountRepository.findAll();
    }
    
    /**
     * 保存办税员（新增或更新）
     */
    public TaxLoginAccount saveTaxAgent(Long accountSetsId, TaxLoginAccount agent) {
        // 账套仅作为管理入口上下文，办税员账号全局共用；
        // 但兼容数据库非空约束，仍然为 accountSetsId 字段写入一个有效值
        agent.setLoginType("PASSWORD");
        
        // 验证手机号不能为空
        if (StrUtil.isBlank(agent.getPhoneNumber())) {
            throw new ServiceException("手机号不能为空");
        }
        
        // 验证手机号格式
        if (!agent.getPhoneNumber().matches("^1[3-9]\\d{9}$")) {
            throw new ServiceException("手机号格式不正确");
        }
        
        // 如果是更新，需要先获取原有数据
        TaxLoginAccount existingAgent = null;
        if (agent.getId() != null) {
            existingAgent = loginAccountRepository.findById(agent.getId())
                    .orElseThrow(() -> new ServiceException("办税员不存在"));
            // 保留创建时间
            agent.setCreateTime(existingAgent.getCreateTime());
            // 如果密码为空，保留原密码
            if (StrUtil.isBlank(agent.getPassword())) {
                agent.setPassword(existingAgent.getPassword());
            }
        } else {
            // 新增时设置创建时间
            agent.setCreateTime(LocalDateTime.now());
            // 新增时密码不能为空
            if (StrUtil.isBlank(agent.getPassword())) {
                throw new ServiceException("密码不能为空");
            }
        }
        // 兼容数据库约束：确保 accountSetsId 字段始终有值
        if (existingAgent != null && existingAgent.getAccountSetsId() != null) {
            // 更新时沿用原来的 accountSetsId（不再作为权限绑定，只为满足 NOT NULL）
            agent.setAccountSetsId(existingAgent.getAccountSetsId());
        } else {
            // 新增时写入当前管理入口所在的账套ID，避免为 null
            agent.setAccountSetsId(accountSetsId);
        }
        
        // 直接保存明文密码，便于 RPA 自动登录使用（注意：仅限内网/受控环境）
        
        // 检查手机号是否重复（全局唯一，排除当前记录）
        List<TaxLoginAccount> existing = loginAccountRepository.findAll();
        for (TaxLoginAccount existingItem : existing) {
            if (existingItem.getPhoneNumber().equals(agent.getPhoneNumber()) 
                    && (agent.getId() == null || !existingItem.getId().equals(agent.getId()))) {
                throw new ServiceException("该手机号已被使用");
            }
        }
        
        agent.setUpdateTime(LocalDateTime.now());
        if (agent.getStatus() == null) {
            agent.setStatus("ACTIVE");
        }
        
        return loginAccountRepository.save(agent);
    }
    
    /**
     * 删除办税员
     */
    public void deleteTaxAgent(Long accountSetsId, Long agentId) {
        TaxLoginAccount agent = loginAccountRepository.findById(agentId)
                .orElseThrow(() -> new ServiceException("办税员不存在"));
        // 办税员账号与账套无关，直接删除
        loginAccountRepository.delete(agent);
    }
    
    /**
     * 更新办税员状态
     */
    public TaxLoginAccount updateTaxAgentStatus(Long accountSetsId, Long agentId, String status) {
        TaxLoginAccount agent = loginAccountRepository.findById(agentId)
                .orElseThrow(() -> new ServiceException("办税员不存在"));
        // 办税员账号与账套无关，仅更新状态
        agent.setStatus(status);
        agent.setUpdateTime(LocalDateTime.now());
        return loginAccountRepository.save(agent);
    }
    public String getCode(String province,String num) {
        if (StringUtils.isBlank(province)) {
            return null;
        }

        Set<String> keys = redisUtil.keys(province + num);
        if (CollectionUtils.isEmpty(keys)) {
            return null;
        }

        String foundCode = null;
        String foundKey = null;

        for (String key : keys) {
            if (StringUtils.isBlank(key)) {
                continue;
            }

            try {
                String value = redisUtil.get(key);
                if (StringUtils.isBlank(value)) {
                    continue;
                }

                Map<String, String> checkCodeInfo = JSONUtil.toBean(value, Map.class);
                if (checkCodeInfo != null) {
                    String code = checkCodeInfo.get("code");
                    if (StringUtils.isNotBlank(code)) {
                        foundCode = code;
                        foundKey = key;
                        break; // 找到第一个有效的就停止
                    }
                }
            } catch (Exception e) {
                //继续进行下个遍历
                continue;
            }
        }

        // 找到验证码后再删除
        if (foundKey != null) {
            redisUtil.delByKey(foundKey);
        }

        return foundCode;
    }
}
