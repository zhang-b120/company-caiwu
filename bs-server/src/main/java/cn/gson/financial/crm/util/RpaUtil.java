package cn.gson.financial.crm.util;



import cn.gson.financial.kernel.model.crm.crmEnum.CodeEnum;
import cn.gson.financial.kernel.model.crm.dto.TaxDeclareItemsInfoDTO;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.QAccountSets;
import cn.gson.financial.kernel.model.entity.TaskExecuteList;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.service.*;
import cn.gson.financial.rpa.Provice2URL;
import cn.gson.financial.rpa.TaskUtil;
import cn.gson.financial.rpa.TaxBrowserManager;
import cn.hutool.core.collection.CollUtil;
import com.alibaba.fastjson.JSONObject;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.BoundingBox;
import com.microsoft.playwright.options.WaitUntilState;
import com.querydsl.jpa.impl.JPAQueryFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static cn.gson.financial.kernel.model.crm.crmEnum.CodeEnum.*;

@Service
@RequiredArgsConstructor
public class RpaUtil {
    private final TaxBrowserManager taxBrowserManager;


    private JPAQueryFactory jqf;

    //    public QAccountSets qTaskExecuteList = QAccountSets.taskExecuteList;
    private final TaxService taxService;
    //全局静态对象，存放所有的浏览器对象
    // 单例模式获取任务管理工具实例，用于管理浏览器任务
    TaskUtil taskUtil = TaskUtil.getInstance();
    //  添加定时器对象
    private final Map<String, Timer> timerMap = new HashMap<>();

    // 定时刷新调度器，用于保持登录状态 - key: contextId, value: scheduler
    private final Map<String, ScheduledExecutorService> refreshSchedulers = new HashMap<>();

    /**
     * 检查各省份税局登录状态
     *
     * @param taxSystemKey 浏览器对象唯一标记 省份+手机号
     * @return
     */
    public boolean checkTaxSystemStatus(String taxSystemKey) {
        List<Map<String, Object>> list = taskUtil.getAllTasks();
        // 判断list是否为null，如果不为null，进行下一步验证，否则返回默认值false
        if (list != null) {
            Iterator<Map<String, Object>> iterator = list.iterator();
            while (iterator.hasNext()) {
                Map<String, Object> map = iterator.next();
                // 检查当前map中是否包含指定的taxSystemKey,并且唯一标记相等，进行下一步判断
                if (map.containsKey("taxSystemKey") && taxSystemKey.equals(map.get("taxSystemKey"))) {
                    try {
                        // 如果浏览器对象存在，那么验证该账号的登录状态是否有效
                        BrowserContext context = (BrowserContext) map.get("browser");
                        if (context == null) {
                            iterator.remove(); // 浏览器上下文为空，移除无效任务
                            continue;
                        }

                        List<Page> pageList = context.pages();
                        if (pageList.isEmpty()) {
                            iterator.remove(); // 没有页面，移除无效任务
                            continue;
                        }

                        Page page = pageList.get(0);

                        // 关闭多余的页面
                        for (int i = 2; i < pageList.size(); i++) {
                            try {
                                pageList.get(i).close();
                            } catch (Exception e) {
                            }
                        }

                        // 刷新一下页面
                        page.reload();
                        // 等待页面加载完成
                        page.waitForLoadState();
                        // 等待页面加载完成
                        page.waitForTimeout(1000);

                        // 判断页面是否出现"请按住滑块，拖动到最右边"
                        if (!page.isVisible("text=请按住滑块，拖动到最右边")) {
                            return true; // 登录状态有效
                        } else {
                            // 登录状态无效，关闭浏览器上下文并移除任务
                            cleanupRefreshTasksForContext(taxSystemKey);
                            context.close();
                            taskUtil.removeTask(map); // 使用迭代器安全删除
                            return false; // 找到了目标但登录已失效
                        }
                    } catch (Exception e) {
                        return false;
                    }
                }
            }
        }
        return false; // 未找到匹配的taxSystemKey
    }

    public boolean loginTaxSystem(String taxSystemKey, String province, String mobile, String password) {
            BrowserContext newContext = createBrowserContext();
            Page page = newContext.newPage();
            HashMap<String, Object> newMap = new HashMap<>();
            try {
                String webUrl = Provice2URL.getUrl(province);
                page.navigate(webUrl);

                page.waitForSelector(".loginBtn");
                page.waitForTimeout(2000);
                if (page.innerText("body").contains("我知道了")) {
                    page.getByText("我知道了").click();
                }
                page.querySelector(".loginBtn").click();
                page.waitForTimeout(200);
                page.getByText("自然人业务").click();
                page.getByPlaceholder("居民身份证号码/手机号码/用户名").fill(mobile);
                page.getByPlaceholder("个人用户密码").fill(password);
                if(!"guangdong".equals(province)){
                    Locator locator = page.locator(".handler");
                    page.waitForTimeout(200);
                    BoundingBox boundingBox = locator.boundingBox();
                    page.waitForTimeout(200);
                    page.mouse().move(boundingBox.x, boundingBox.y);
                    page.waitForTimeout(20);
                    page.mouse().down();
                    Locator dragText = page.locator(".drag_text");
                    BoundingBox dragTextBoundingBox = dragText.boundingBox();
                    page.mouse().move(boundingBox.x + dragTextBoundingBox.width, boundingBox.y);
                    page.waitForTimeout(20);
                    page.mouse().up();
                }
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登录")).click();
                newMap.put("taxSystemKey", taxSystemKey);
                newMap.put("browser", newContext);
                taskUtil.addTask(newMap);
                page.waitForTimeout(3000);
                if (page.innerText("body").contains("短信验证")) {
                    page.getByText("短信验证", new Page.GetByTextOptions().setExact(true)).click();
                    page.getByText("获取验证码").click();
                    return true;
                } else {
                    createScheduler(taxSystemKey, newContext);
                    return false;
                }
            } catch (Exception e) {
                e.printStackTrace();
                taskUtil.removeTask(newMap);
                return false;
            }

    }

    public Boolean checkTaxSystemCode(String code, String taxSystemKey) {

        // 需要根据企业进行验证码的个性化配置

        List<Map<String, Object>> list = taskUtil.getAllTasks();
        if (list != null) {
            for (Map<String, Object> map : list) {
                //检查当前map中是否包含指定的taxSystemKey,并且唯一标记相等，进行下一步判断，否则将会返回默认值false
                if (map.containsKey("taxSystemKey") && taxSystemKey.equals(map.get("taxSystemKey"))) {
                    //如果浏览器对象存在，那么验证该账号的登录状态是否有效
                    BrowserContext context = (BrowserContext) map.get("browser");
                    Page page = context.pages().get(0);
                    page.getByPlaceholder("请输入短信验证码").fill(code);
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登录")).click();
                    page.waitForTimeout(3000);
                    //判断页面中是否存在placeholder="请输入短信验证码"的元素
                    if (page.innerText("body").contains("请输入短信验证码")) {
                        System.out.println("验证码失败");
                        return false;
                    } else {
                        System.out.println("验证码正确，登录成功");
                        // 验证码成功后，创建新的page，并且换身份1
                        createScheduler(taxSystemKey, context);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public Boolean logoutTaxSystem(String taxSystemKey) {
        List<Map<String, Object>> list = taskUtil.getAllTasks();
        if (list != null) {
            Iterator<Map<String, Object>> iterator = list.iterator();
            while (iterator.hasNext()) {
                Map<String, Object> map = iterator.next();
                // 检查当前map中是否包含指定的taxSystemKey,并且唯一标记相等，进行下一步判断
                if (map.containsKey("taxSystemKey") && taxSystemKey.equals(map.get("taxSystemKey"))) {
                    // 如果浏览器对象存在，那么验证该账号的登录状态是否有效
                    BrowserContext context = (BrowserContext) map.get("browser");
                    if (context != null) {
                        // 关闭浏览器上下文前，清理所有相关的定时刷新任务
                        cleanupRefreshTasksForContext(taxSystemKey);
                        context.close();
                    }
                    taskUtil.removeTask(map); // 使用迭代器安全删除
                    return true; // 找到并删除后返回true
                }
            }
        }
        return false;
    }

    /**
     * 清理指定浏览器上下文的所有定时刷新任务
     *
     * @param taxSystemKey 浏览器唯一标记
     */
    private void cleanupRefreshTasksForContext(String taxSystemKey) {
        try {

            // 查找并停止该上下文的刷新任务
            Iterator<Map.Entry<String, ScheduledExecutorService>> iterator = refreshSchedulers.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, ScheduledExecutorService> entry = iterator.next();
                String key = entry.getKey();

                // 检查是否是该上下文的任务（通过上下文hashCode匹配）
                if (key.contains(taxSystemKey)) {
                    ScheduledExecutorService scheduler = entry.getValue();
                    if (!scheduler.isShutdown()) {
                        scheduler.shutdown();
                    }
                    iterator.remove();
                    System.out.println("已清理浏览器上下文的定时刷新任务: " + key);
                }
            }

            System.out.println("已清理浏览器上下文的所有定时刷新任务");

        } catch (Exception e) {
            System.err.println("清理定时刷新任务失败: " + e.getMessage());
        }
    }

    /**
     * 测试页面刷新对其他页面的影响
     *
     * @param taxSystemKey 税务系统标识
     */
    public void testPageRefreshImpact(String taxSystemKey) {
        List<Map<String, Object>> list = taskUtil.getAllTasks();
        if (list != null) {
            for (Map<String, Object> map : list) {
                if (map.containsKey("taxSystemKey") && taxSystemKey.equals(map.get("taxSystemKey"))) {
                    BrowserContext context = (BrowserContext) map.get("browser");
                    if (context != null) {
                        List<Page> pages = context.pages();
                        System.out.println("测试前页面数: " + pages.size());

                        // 打印所有页面的URL和状态
                        for (int i = 0; i < pages.size(); i++) {
                            Page p = pages.get(i);
                            System.out.println("页面" + i + ": " + p.url() + " (状态: " + (!p.isClosed() ? "存在" : "已关闭") + ")");
                        }

                        // 手动刷新第一个页面
                        if (!pages.isEmpty() && !pages.get(0).isClosed()) {
                            System.out.println("手动刷新第一个页面...");
                            pages.get(0).reload();
                            pages.get(0).waitForTimeout(1000);
                            System.out.println("手动刷新完成");

                            // 检查刷新后的状态
                            List<Page> pagesAfter = context.pages();
                            System.out.println("测试后页面数: " + pagesAfter.size());

                            for (int i = 0; i < pagesAfter.size(); i++) {
                                Page p = pagesAfter.get(i);
                                System.out.println("页面" + i + ": " + p.url() + " (状态: " + (!p.isClosed() ? "存在" : "已关闭") + ")");
                            }
                        }
                        break;
                    }
                }
            }
        }
    }

    /**
     * 创建浏览器上下文
     */
    private BrowserContext createBrowserContext() {
        //创建浏览器上下文
        Playwright playwright = Playwright.create();
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions().setHeadless(false).setSlowMo(200)
                //跳过检查，防止被识别是自动化脚本
                .setArgs(Collections.singletonList("--disable-blink-features=AutomationControlled"));
        //设置有头浏览器方式启动
        Browser browser = playwright.chromium().launch(launchOptions);
        BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                .setIgnoreHTTPSErrors(true).setJavaScriptEnabled(true));
        return context;
    }

    /**
     * 根据税号切换身份
     *
     * @param page
     * @param shuihao
     * @return
     */
    private boolean changeIdentity(Page page, String shuihao) {
        page.waitForTimeout(2000);
        if (page.innerText("body").contains(shuihao)) {
            return true;
        } else {
            page.hover(".t-menu__operations");
            page.waitForTimeout(500);
            page.getByText("身份切换").click();
            page.waitForTimeout(2000);
            page.getByPlaceholder("请输入统一社会信用代码").fill(shuihao);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("查询")).click();
            page.waitForTimeout(3000);
            if (page.innerText("body").contains("共 1 条")) {
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("切换")).first().click();
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("确定")).click();
                return true;
            } else {
                //点击返回按钮回到起始页
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("返回")).click();
                return false;
            }
        }
    }

    /**
     * 根据税号切换身份
     *
     * @param page
     * @return
     */
    private void changeIdentityDefault(Page page) {
        try {
            page.waitForTimeout(2000);
            page.hover(".t-menu__operations");
            page.waitForTimeout(1000);
            page.getByText("账户中心").click();
            page.waitForTimeout(2000);
            page.getByText("身份切换").click();
            page.waitForTimeout(3000);
            // 当有多条数据时，默认选择第一条数据的切换按钮
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("切换")).first().click();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("确定")).click();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 创建定时任务。浏览器保活
     *
     * @param taxSystemKey
     * @param context
     */
    public void createScheduler(String taxSystemKey, BrowserContext context) {
        Page page = context.pages().get(0);
        String currentUrl = page.url();
        Page newPage = context.newPage();
        newPage.navigate(currentUrl);
        newPage.waitForLoadState();
        changeIdentityDefault(newPage);
        newPage.waitForTimeout(2000);
        // 创建保活任务 - 使用模拟用户活动的方式
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            System.out.println("=== 开始保活操作 ===");
            page.getByText("首页").click();
        }, 300, 300, TimeUnit.SECONDS); // 30秒执行一次，更温和
        refreshSchedulers.put(taxSystemKey, scheduler);
    }


    public Map<String, String> downInvoice(TaskExecuteList taskExecuteList) {
        // 解析taskData
        String taskData = taskExecuteList.getTaskData();
        JSONObject taskDataJson = JSONObject.parseObject(taskData);

        // 获取账套信息
        AccountSets accountSets = new AccountSets();
        accountSets.setId(Long.parseLong(taskDataJson.getString("id")));
        accountSets.setCompanyName(taskDataJson.getString("companyName"));
        accountSets.setCreditCode(taskDataJson.getString("creditCode"));
        accountSets.setElectricityTaxCode(taskDataJson.getString("electricityTaxCode"));
        accountSets.setProvince(taskDataJson.getString("province"));
        accountSets.setVatType(VatType.valueOf(taskDataJson.getString("vatTypes")));

        // 获取发票类型列表
        List<String> invoceTypeList = taskDataJson.getJSONArray("invoiceType").toJavaList(String.class);

        // 创建参数Map
        Map<String, Object> map = new HashMap<>();
        map.put("startDate", taskDataJson.getString("startDate"));
        map.put("endDate", taskDataJson.getString("endDate"));
        map.put("invoiceSaveType", taskDataJson.getInteger("invoiceSaveType"));
        Map<String, String> result = new HashMap<>();
        result.put("yigouxuan", "暂无任务");
        result.put("weigouxuan", "暂无任务");
        result.put("kaijufapiao", "暂无任务");
        result.put("qudefapiao", "暂无任务");
        for (String invoiceType : invoceTypeList) {
            Map<String, String> params = new HashMap<>();
            params.put("taxSystemKey", taskDataJson.getString("province") + "*" + taskDataJson.getString("electricityTaxPhone"));//登录手机号
            params.put("creditCode", accountSets.getCreditCode());//税号
            params.put("password", accountSets.getElectricityTaxCode());//点税密码
            params.put("province", accountSets.getProvince());//省份
            params.put("accountSetsId", accountSets.getId().toString());//账套id
            params.put("startDate", map.get("startDate").toString());
            params.put("endDate", map.get("endDate").toString());
            params.put("invoiceType", invoiceType);
            params.put("invoiceSaveType", map.get("invoiceSaveType").toString());
            if (invoiceType.equals("已勾选") && accountSets.getVatType().name().equals("COMMONLY")) {// invoiceType ["已勾选", "未勾选", "开具发票","取得发票"]
                Map<String, String> gouxuanMap = downgouxuanInvoice(params, accountSets);
                if (gouxuanMap.containsKey("status") && "success".equals(gouxuanMap.get("status"))) {
                    result.put("yigouxuan", "已完成");
                } else {
                    result.put("yigouxuan", "未完成");
                    result.put("yigouxuanMsg", gouxuanMap.get("msg"));
                }
            } else if (invoiceType.equals("开具发票")) {
                Map<String, String> kaijuMap = downInvoice(params, accountSets);
                if (kaijuMap.containsKey("status") && "success".equals(kaijuMap.get("status"))) {
                    result.put("kaijufapiao", "已完成");
                } else {
                    result.put("kaijufapiao", "未完成");
                    result.put("kaijufapiaoMsg", kaijuMap.get("msg"));
                }
            } else if (invoiceType.equals("取得发票")) {
                Map<String, String> qudefapiao = downInvoice(params, accountSets);
                if (qudefapiao.containsKey("status") && "success".equals(qudefapiao.get("status"))) {
                    result.put("qudefapiao", "已完成");
                } else {
                    result.put("qudefapiao", "未完成");
                    result.put("qudefapiaoMsg", qudefapiao.get("msg"));
                }
            } else if (invoiceType.equals("未勾选") && accountSets.getVatType().name().equals("COMMONLY")) {
                Map<String, String> weigouxuanMap = downweigouxuanInvoice(params, accountSets);
                if (weigouxuanMap.containsKey("status") && "success".equals(weigouxuanMap.get("status"))) {
                    result.put("weigouxuan", "已完成");
                } else {
                    result.put("weigouxuan", "未完成");
                    result.put("weigouxuanMsg", weigouxuanMap.get("msg"));
                }
            }
        }
        return result;
    }

    public Map<String, String> downInvoice(Map<String, String> map, AccountSets accountSets) {
        HashMap<String, String> result = new HashMap<>();
        Map<String, Object> taskMap = checkUserExist(map.get("taxSystemKey"));
        BrowserContext context = (BrowserContext) taskMap.get("browser");
        Page page = context.pages().get(1);
        List<Page> pageList = context.pages();
        for (int i = 2; i < pageList.size(); i++) {
            pageList.get(i).close();
        }
        if (changeIdentity(page, map.get("creditCode"))) {
            //调用下载发票方法
            return taxBrowserManager.dwonInvoice(context, map, accountSets);
        } else {
            //切换公司失败，报错，告知用户
            result.put("status", "error");
            result.put("code", "202");
            result.put("msg", map.get("invoiceType") + "当前账号的办税员身份与公司不匹配，切换登录账号或者联系管理员进行维护");
            return result;
        }
    }
    /**
     * 下载发票pdf版 未开始
     */

    /**
     * 下载勾选的发票
     *
     * @param map
     * @return
     */
    public Map<String, String> downgouxuanInvoice(Map<String, String> map, AccountSets accountSets) {
        HashMap<String, String> result = new HashMap<>();
        Map<String, Object> taskMap = checkUserExist(map.get("taxSystemKey"));//shandong*13969134863
        BrowserContext context = (BrowserContext) taskMap.get("browser");
        Page page = context.pages().get(1);
        List<Page> pageList = context.pages();
        for (int i = 2; i < pageList.size(); i++) {
            pageList.get(i).close();
        }
        if (changeIdentity(page, map.get("creditCode"))) {
            //调用下载发票方法
            return taxBrowserManager.downgouxuan(context, map, page, accountSets);
        } else {
            //切换公司失败，报错，告知用户
            result.put("status", "error");
            result.put("code", "202");
            result.put("msg", map.get("invoiceType") + "当前账号的办税员身份与公司不匹配，切换登录账号或者联系管理员进行维护");
            return result;
        }

    }

    /**
     * 下载未勾选的发票
     */
    public Map<String, String> downweigouxuanInvoice(Map<String, String> map, AccountSets accountSets) {
        HashMap<String, String> result = new HashMap<>();
        Map<String, Object> taskMap = checkUserExist(map.get("taxSystemKey"));
        BrowserContext context = (BrowserContext) taskMap.get("browser");
        Page page = context.pages().get(1);
        List<Page> pageList = context.pages();
        for (int i = 2; i < pageList.size(); i++) {
            pageList.get(i).close();
        }
        if (changeIdentity(page, map.get("creditCode"))) {
            //调用下载发票方法
            return taxBrowserManager.downweigouxuan(context, map, page, accountSets);
        } else {
            //切换公司失败，报错，告知用户
            result.put("status", "error");
            result.put("code", "202");
            result.put("msg", map.get("invoiceType") + "当前账号的办税员身份与公司不匹配，切换登录账号或者联系管理员进行维护");
            return result;
        }

    }

    /**
     * 一、检查登录状态
     * 1、通过手机号判断taskUtil中是存在对应的浏览器对象
     */
    public Map<String, Object> checkUserExist(String taxSystemKey) {
        List<Map<String, Object>> list = taskUtil.getAllTasks();
        if (list == null) {
            return null;
        } else {
            for (Map<String, Object> map : list) {
                // 检查当前Map是否包含指定的phoneNumber键，并且值相等
                if (map.containsKey("taxSystemKey") && map.get("taxSystemKey").equals(taxSystemKey)) {
                    return map; // 找到匹配的电话号码
                }
            }
            return null;
        }
    }


    /**
     * 获取账套需要报税的项目
     *
     * @param creditCode
     * @param taxSystemKey
     * @return
     */
    public TaxDeclareItemsInfoDTO getTaxDeclareItems(String creditCode, String taxSystemKey,Long accountSetId) {
        TaxDeclareItemsInfoDTO infoDTO = new TaxDeclareItemsInfoDTO();
        Map<String, Object> taskMap = checkUserExist(taxSystemKey);
        BrowserContext context = (BrowserContext) taskMap.get("browser");
        Page page = context.pages().get(1);
        List<Page> pageList = context.pages();
        for (int i = 2; i < pageList.size(); i++) {
            pageList.get(i).close();
        }
        //保证页面返回后，页面加载完成
        if (changeIdentity(page, creditCode)) {
            page.waitForSelector(".rightMain");
            long startTime = System.currentTimeMillis();
            long timeout = 10000; // 10秒超时
            while (true) {
                if (!page.innerText(".rightMain").contains("暂无数据")) {
                    break; //跳出循环
                }
                // 检查是否超时
                if (System.currentTimeMillis() - startTime > timeout) {
                    infoDTO.setCode(CODE_202.getCode());
                    infoDTO.setMsg(CODE_202.getMsg());
                    return infoDTO;
                }
                page.waitForTimeout(1000); // 每秒检查一次
            }
            //调用下载报税项目方法
            List<Map<String, String>> taxDeclareItemsMap = downTaxDeclareItems(page);
            if (CollUtil.isNotEmpty(taxDeclareItemsMap)){
                infoDTO.setData(taxDeclareItemsMap);
            }else{
                infoDTO.setCode(CODE_203.getCode());
                infoDTO.setMsg(CODE_203.getMsg());
            }
            page.waitForTimeout(3000);
            String url = taxBrowserManager.screenView(page,creditCode);
            infoDTO.setImgUrl(url);
            taxBrowserManager.getTaxAmount(page,creditCode,accountSetId,context);
            return infoDTO;
        } else {
            //切换公司失败，报错，告知用户
            infoDTO.setCode(CodeEnum.CODE_201.getCode());
            infoDTO.setMsg(CODE_201.getMsg());
            return infoDTO;
        }
    }

    /**
     * 获取需要报税的界面表格
     *
     * @param page
     * @return
     */
    public List<Map<String, String>> downTaxDeclareItems(Page page) {
        //等页面加载完在执行下面的代码
        List<Map<String, String>> tableDataList = new ArrayList<>();
        page.waitForSelector(".rightMain");
        // 定位到class为rightMain的div
        ElementHandle rightMainDiv = page.querySelector(".rightMain");
        //等待rightMainDiv加载完成
        if (rightMainDiv != null) {
            // 定位到其子代class为t-table__content的div中的第一个div
            page.waitForSelector(".t-table__content:first-child");
            ElementHandle targetDiv = rightMainDiv.querySelector(".t-table__content:first-child");
            if (targetDiv != null) {
                //等待targetDiv加载完成
                // 获取表格的行
                List<ElementHandle> bodyRows = targetDiv.querySelectorAll("tbody tr");
                List<ElementHandle> headerRows = targetDiv.querySelectorAll("thead tr");
                if (!headerRows.isEmpty()) {
                    // 获取表头
                    List<String> headers = new ArrayList<>();
                    List<ElementHandle> headerCells = headerRows.get(0).querySelectorAll("th");
                    for (ElementHandle headerCell : headerCells) {
                        headers.add(headerCell.innerText());
                    }
                    // 获取表体数据
                    for (int i = 0; i < bodyRows.size(); i++) {
                        ElementHandle row = bodyRows.get(i);
                        List<ElementHandle> cells = row.querySelectorAll("td");
                        Map<String, String> rowMap = new HashMap<>();
                        for (int j = 0; j < cells.size(); j++) {
                            ElementHandle cell = cells.get(j);
                            if (j < headers.size()) {
                                rowMap.put(headers.get(j), cell.innerText());
                            }
                        }
                        tableDataList.add(rowMap);
                    }
                }
            }
        }
        return tableDataList;
    }

    // 以下为报税流程start
    public Map<String, String> oneKeyTaxButton(Map<String, String> taskDataMap, LocalDate taskDate) {
        Boolean flag = taxService.getTaskStatus("财务报表报送",taskDataMap.get("companyName"),taskDate);
        Map<String, String> result = new HashMap<>();

        if(!"财务报表报送（季报）".equals(taskDataMap.get("taxType")) && !"财务报表报送（年报）".equals(taskDataMap.get("taxType")) && !flag){
            // 如果报税条目不是财务报表报送，并且财务报表报送还没有完成。直接退出方法
            result.put("status","error");
            result.put("msg","财务报表推送未完成，"+taskDataMap.get("companyName")+taskDataMap.get("taxType")+"无法申报");
            return result;
        }
        Map<String, Object> taskMap = checkUserExist(taskDataMap.get("taxSystemKey"));
        BrowserContext context = (BrowserContext) taskMap.get("browser");
        Page page = context.pages().get(0);
        List<Page> pageList = context.pages();
        for (int i = 2; i < pageList.size(); i++) {
            pageList.get(i).close();
        }
        //保证页面返回后，页面加载完成
        page.waitForTimeout(1500);
        if (changeIdentity(page, taskDataMap.get("creditCode"))) {
            page.waitForTimeout(2000);
            //调用填写报税表的方法
            Page page1 = null;
            if ("财务报表报送（季报）".equals(taskDataMap.get("taxType")) || "财务报表报送（年报）".equals(taskDataMap.get("taxType"))) {
                page.waitForTimeout(100);
                page.getByPlaceholder("请输入关键词").fill("财务报表报送及更正");
                page.waitForTimeout(2000);
                page1 = context.waitForPage(() -> page.querySelector(".popMainScreen").click());
                result =  taxBrowserManager.caishuibaobiao(page1, taskDataMap);
            }else if ("增值税及附加税费申报（一般纳税人适用）".equals(taskDataMap.get("taxType")) ){
                page.getByPlaceholder("请输入关键词").fill("增值税及附加税费申报（一般纳税人适用）");
                page.waitForTimeout(2000);
                page1 = context.waitForPage(() -> page.querySelector(".popMainScreen").click());
                result =  taxBrowserManager.zengzhishuibaobiao(page1, taskDataMap);

            }else if( "增值税及附加税费申报（小规模纳税人）".equals(taskDataMap.get("taxType"))){
                page.getByPlaceholder("请输入关键词").fill("增值税及附加税费申报（小规模纳税人）");
                page.waitForTimeout(2000);
                page1 = context.waitForPage(() -> page.querySelector(".popMainScreen").click());
                result = taxBrowserManager.zengzhishuibaobiao(page1, taskDataMap);
            } else if ("居民企业（查账征收）企业所得税月（季）度申报".equals(taskDataMap.get("taxType"))) {
                page.waitForTimeout(100);
                page.getByPlaceholder("请输入关键词").fill("居民企业（查账征收）企业所得税月（季）度申报");
                page.waitForTimeout(2000);
                page1 = context.waitForPage(() -> page.querySelector(".popMainScreen").click());
                result = taxBrowserManager.juminqiye(page1, taskDataMap);
            } else if ("财产和行为税税源采集及合并申报".equals(taskDataMap.get("taxType"))) {
                page.waitForTimeout(100);
                page.getByPlaceholder("请输入关键词").fill("财产和行为税税源采集及合并申报");
                page.waitForTimeout(2000);
                page1 = context.waitForPage(() -> page.querySelector(".popMainScreen").click());
                result =  taxBrowserManager.caichanhexingwei(page1, taskDataMap);
            }else{
                result.put("status", "error");
                result.put("code", "202");
                result.put("msg", "待开发");
            }
        }else{
            result.put("status", "error");
            result.put("code", "202");
            result.put("msg", "无办税员身份");
        }
        return result;

    }

}
