package cn.gson.financial.rpa;


import cn.gson.financial.crm.service.CrmEmployeeService;
import cn.gson.financial.crm.service.OutSystem2CRMService;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.AllTaskDto;
import cn.gson.financial.kernel.model.dto.ReportItemsDto;
import cn.gson.financial.kernel.model.dto.TaxDto;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.entity.AppConfig;
import cn.gson.financial.kernel.model.entity.Invoice;
import cn.gson.financial.kernel.model.entity.QImagePayment;
import cn.gson.financial.kernel.model.enums.AccountStandard;
import cn.gson.financial.kernel.model.enums.InvoiceType;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.*;
import cn.hutool.json.JSONUtil;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.*;
import com.querydsl.jpa.impl.JPAQueryFactory;
import io.micrometer.common.util.StringUtils;
import jakarta.annotation.Resource;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.util.StringUtil;
import org.hibernate.validator.internal.util.stereotypes.Lazy;
import org.sagacity.sqltoy.dao.SqlToyLazyDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import cn.gson.financial.kernel.service.ReportService;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class TaxBrowserManager {
    private final TaxService taxService;
    @Value("${customize-config.screen-img-path}")
    private String screenImgPath;
    //全局静态对象，存放所有的浏览器对象
// 单例模式获取任务管理工具实例，用于管理浏览器任务
    TaskUtil taskUtil = TaskUtil.getInstance();

    // 发票导入服务,用于处理发票导入相关业务
    private final InvoiceImportService invoiceImportService;

    // 应用配置服务,用于获取系统配置信息
    private final AppConfigService appConfigService;

    // 账套服务,用于处理账套相关业务
    private final AccountSetsService accountSetsService;

    // 发票校验服务,用于处理发票校验相关业务
    private final CheckInvoiceService checkInvoiceService;
    private final InvoiceService invoiceService;
    private final CrmEmployeeService crmEmployeeService;
    private final QImagePayment qImagePayment = QImagePayment.imagePayment;

    @Resource
    protected JPAQueryFactory jqf;

    @Resource
    protected SqlToyLazyDao lazyDao;
    /*
     * 执行发票下载任务。
     *
     *  1、判断账号是否存在
     *   1.1 不存在，调用登录放放
     *   1.2 存在，判断账号是否失效。
     *      1.2.1 失效，调用登录方法
     *       1.2.2 未失效，验证公司是否匹配
     *       1.2.2.1 公司匹配，调用下载发票方法
     *       1.2.2.2 公司不匹配，调用切换公司方法
     *       1.2.2.2.1 切换公司成功，调用下载发票方法
     *       1.2.2.2.2 切换公司失败，报错，告知用户
     *
     * */

    /**
     *  1、验证用户登录状态，需要重新进行登录，请提供登录验证码
     *  2、进行下载发票操作
     *
     */

    /**
     * 验证用户登录状态
     *
     * @param map
     * @return
     */
    public Map<String, String> checkIsLoginStatus(Map<String, String> map) {
        HashMap<String, String> result = new HashMap<>();
        // 1、通过手机号判断taskUtil中是存在对应的浏览器对象
        Map<String, Object> taskMap = checkUserExist(map.get("phoneNumber"));

        if (taskMap != null) { // 浏览器对象存在，进行验证
            String code = String.valueOf(taskMap.get("code"));
            String phoneNumber = taskMap.get("phoneNumber").toString();
            BrowserContext context = (BrowserContext) taskMap.get("browser");
            Page page = context.pages().get(0);
            List<Page> pageList = context.pages();
            for (int i = 1; i < pageList.size(); i++) {
                pageList.get(i).close();
            }
            //刷新一下页面
            page.reload();
            // 等待页面加载完成
            page.waitForLoadState(LoadState.NETWORKIDLE);

            try {
                // 使用更可靠的选择器和等待机制
                if (page.isVisible("text='我知道了'", new Page.IsVisibleOptions().setTimeout(3000))) {
                    page.getByText("我知道了").click();
                    // 等待登录按钮出现并点击
                    page.waitForSelector(".loginBtn");
                    page.querySelector(".loginBtn").click();
                }
            } catch (TimeoutError e) {
                // 如果超时，说明没有"我知道了"按钮，继续后续流程
                log.debug("No '我知道了' button found, continuing...");
            }

            //判断页面是否出现"请按住滑块，拖动到最右边"
            if (page.isVisible("text=请按住滑块，拖动到最右边")) {
                HashMap<String, Object> newMap = new HashMap<>();
                if (doLogin(context, page, map)) {
                    // 账号密码登录完成，需要进行验证码确认
                    result.put("status", "success");
                    result.put("code", "201");
                    result.put("msg", "提供验证码");
                    newMap.put("code", "201");//201表示需要验证码
                    return result;
                } else {
                    result.put("status", "error");
                    result.put("code", "203");
                    result.put("msg", "登录失败，请核对账号密码，联系管理员进行维护");
                    page.close();
                    return result;
                }
            }
            if ("200".equals(taskMap.get("code"))) {
                //该用户已登录过，需要验证登录状态
                if (checkUserStatus(context, map.get("province"))) {
                    //用户验证通过，告知浏览器
                    result.put("status", "success");
                    result.put("code", "200");
                    result.put("msg", "用户登录状态正常");
                    return result;
                } else {
                    if (doLogin(context, page, map)) {
                        // 账号密码登录完成，需要进行验证码确认
                        result.put("status", "success");
                        result.put("code", "201");
                        result.put("msg", "提供验证码");
                        taskMap.put("code", "201");//201表示需要验证码
                        taskMap.put("phoneNumber", phoneNumber);
                        taskMap.put("browser", context);
                        return result;
                    } else {
                        result.put("status", "error");
                        result.put("code", "203");
                        result.put("msg", "登录失败，请核对账号密码，联系管理员进行维护");
                        context.close();
                        taskMap = null;
                        return result;
                    }
                }
            } else {//用户已经登录，但是没有确认验证码
                taskMap.put("code", "201");
                result.put("status", "success");
                result.put("code", "201");
                result.put("msg", "提供验证码");
                return result;
            }
        } else {//浏览器对象不存在，新建，并登录
            BrowserContext newContext = createBrowserContext();
            Page page = newContext.newPage();
            HashMap<String, Object> newMap = new HashMap<>();
            if (doLogin(newContext, page, map)) {
                // 账号密码登录完成，需要进行验证码确认
                result.put("status", "success");
                result.put("code", "201");
                result.put("msg", "提供验证码");
                newMap.put("code", "201");//201表示需要验证码
                newMap.put("phoneNumber", map.get("phoneNumber"));
                newMap.put("electricityTaxPhoneAndPrivance", map.get("electricityTaxPhoneAndPrivance") + map.get("province"));
                newMap.put("browser", newContext);
                taskUtil.addTask(newMap);
                return result;
            } else {
                result.put("status", "error");
                result.put("code", "203");
                result.put("msg", "登录失败，请核对账号密码，联系管理员进行维护");
                newContext.close();
                return result;
            }
        }
    }

    /**
     * 验证代理用户登录状态
     *
     * @param map
     * @return
     */
    public Map<String, String> newCheckIsLoginStatus(Map<String, String> map) {
        HashMap<String, String> result = new HashMap<>();
        //判断是用代理通道还是企业业务登录
        if (map.get("proxyLoginPhone").equals(map.get("phoneNumber"))) {


            // 1、通过手机号判断taskUtil中是存在对应的浏览器对象

            Map<String, Object> taskMap = checkUserExist(map.get("phoneNumber"));

            if (taskMap != null) { // 浏览器对象存在，进行验证
                String code = taskMap.get("code").toString();
                String phoneNumber = taskMap.get("phoneNumber").toString();
                BrowserContext context = (BrowserContext) taskMap.get("browser");
                Page page = context.pages().get(0);
                List<Page> pageList = context.pages();
                for (int i = 1; i < pageList.size(); i++) {
                    pageList.get(i).close();
                }
                //刷新一下页面
                page.reload();
                // 等待页面加载完成
                page.waitForLoadState(LoadState.NETWORKIDLE);
                try {
                    // 使用更可靠的选择器和等待机制
                    if (page.isVisible("text='我知道了'", new Page.IsVisibleOptions().setTimeout(3000))) {
                        page.getByText("我知道了").click();
                        // 等待登录按钮出现并点击
                        page.waitForSelector(".loginBtn");
                        page.querySelector(".loginBtn").click();
                    }
                } catch (TimeoutError e) {
                    // 如果超时，说明没有"我知道了"按钮，继续后续流程
                    log.debug("No '我知道了' button found, continuing...");
                }

                //判断页面是否出现"请按住滑块，拖动到最右边"
                if (page.isVisible("text=请按住滑块，拖动到最右边")) {
                    HashMap<String, Object> newMap = new HashMap<>();
                    if (newDoLogin(context, page, map)) {
                        // 账号密码登录完成，需要进行验证码确认
                        result.put("status", "success");
                        result.put("code", "201");
                        result.put("msg", "提供验证码");
                        newMap.put("code", "201");//201表示需要验证码
                        return result;
                    } else {
                        result.put("status", "error");
                        result.put("code", "203");
                        result.put("msg", "登录失败，请核对账号密码，联系管理员进行维护");
                        page.close();
                        return result;
                    }
                }
                if ("200".equals(taskMap.get("code"))) {
                    //该用户已登录过，需要验证登录状态
                    if (checkUserStatus(context, map.get("province"))) {
                        //用户验证通过，告知浏览器
                        result.put("status", "success");
                        result.put("code", "200");
                        result.put("msg", "用户登录状态正常");
                        return result;
                    } else {
                        if (newDoLogin(context, page, map)) {
                            // 账号密码登录完成，需要进行验证码确认
                            result.put("status", "success");
                            result.put("code", "201");
                            result.put("msg", "提供验证码");
                            taskMap.put("code", "201");//201表示需要验证码
                            taskMap.put("phoneNumber", phoneNumber);
                            taskMap.put("browser", context);
                            return result;
                        } else {
                            result.put("status", "error");
                            result.put("code", "203");
                            result.put("msg", "登录失败，请核对账号密码，联系管理员进行维护");
                            context.close();
                            taskMap = null;
                            return result;
                        }
                    }
                } else {//用户已经登录，但是没有确认验证码
                    taskMap.put("code", "201");
                    result.put("status", "success");
                    result.put("code", "201");
                    result.put("msg", "提供验证码");
                    return result;
                }
            } else {//浏览器对象不存在，新建，并登录
                BrowserContext newContext = createBrowserContext();
                Page page = newContext.newPage();
                HashMap<String, Object> newMap = new HashMap<>();
                if (newDoLogin(newContext, page, map)) {
                    // 账号密码登录完成，需要进行验证码确认
                    result.put("status", "success");
                    result.put("code", "201");
                    result.put("msg", "提供验证码");
                    newMap.put("code", "201");//201表示需要验证码
                    newMap.put("phoneNumber", map.get("phoneNumber"));
                    newMap.put("electricityTaxPhoneAndPrivance", map.get("electricityTaxPhoneAndPrivance") + map.get("province"));
                    newMap.put("browser", newContext);
                    taskUtil.addTask(newMap);
                    return result;
                } else {
                    result.put("status", "error");
                    result.put("code", "203");
                    result.put("msg", "登录失败，请核对账号密码，联系管理员进行维护");
                    newContext.close();
                    return result;
                }
            }
        } else {
            // 1、通过手机号判断taskUtil中是存在对应的浏览器对象

            Map<String, Object> taskMap = checkUserExist(map.get("proxyLoginPhone"));

            if (taskMap != null) { // 浏览器对象存在，进行验证
                String code = taskMap.get("code").toString();
                String phoneNumber = String.valueOf(taskMap.get("proxyLoginPhone"));
                BrowserContext context = (BrowserContext) taskMap.get("browser");
                Page page = context.pages().get(0);
                List<Page> pageList = context.pages();
                for (int i = 1; i < pageList.size(); i++) {
                    pageList.get(i).close();
                }
                //刷新一下页面
                page.reload();
                // 等待页面加载完成
                page.waitForLoadState(LoadState.NETWORKIDLE);
                try {
                    // 使用更可靠的选择器和等待机制
                    if (page.isVisible("text='我知道了'", new Page.IsVisibleOptions().setTimeout(3000))) {
                        page.getByText("我知道了").click();
                        // 等待登录按钮出现并点击
                        page.waitForSelector(".loginBtn");
                        page.querySelector(".loginBtn").click();
                    }
                } catch (TimeoutError e) {
                    // 如果超时，说明没有"我知道了"按钮，继续后续流程
                    log.debug("No '我知道了' button found, continuing...");
                }

                //判断页面是否出现"请按住滑块，拖动到最右边"
                if (page.isVisible("text=请按住滑块，拖动到最右边")) {
                    HashMap<String, Object> newMap = new HashMap<>();
                    if (newDoLogin(context, page, map)) {
                        // 账号密码登录完成，需要进行验证码确认
                        result.put("status", "success");
                        result.put("code", "201");
                        result.put("msg", "提供验证码");
                        newMap.put("code", "201");//201表示需要验证码
                        return result;
                    } else {
                        result.put("status", "error");
                        result.put("code", "203");
                        result.put("msg", "登录失败，请核对账号密码，联系管理员进行维护");
                        page.close();
                        return result;
                    }
                }
                if ("200".equals(taskMap.get("code"))) {
                    //该用户已登录过，需要验证登录状态
                    if (checkUserStatus(context, map.get("province"))) {
                        //用户验证通过，告知浏览器
                        result.put("status", "success");
                        result.put("code", "200");
                        result.put("msg", "用户登录状态正常");
                        return result;
                    } else {
                        if (newDoLogin(context, page, map)) {
                            // 账号密码登录完成，需要进行验证码确认
                            result.put("status", "success");
                            result.put("code", "201");
                            result.put("msg", "提供验证码");
                            taskMap.put("code", "201");//201表示需要验证码
                            taskMap.put("phoneNumber", phoneNumber);
                            taskMap.put("browser", context);
                            return result;
                        } else {
                            result.put("status", "error");
                            result.put("code", "203");
                            result.put("msg", "登录失败，请核对账号密码，联系管理员进行维护");
                            context.close();
                            taskMap = null;
                            return result;
                        }
                    }
                } else {//用户已经登录，但是没有确认验证码
                    taskMap.put("code", "201");
                    result.put("status", "success");
                    result.put("code", "201");
                    result.put("msg", "提供验证码");
                    return result;
                }
            } else {//浏览器对象不存在，新建，并登录
                BrowserContext newContext = createBrowserContext();
                Page page = newContext.newPage();
                HashMap<String, Object> newMap = new HashMap<>();
                if (newDoLogin(newContext, page, map)) {
                    // 账号密码登录完成，需要进行验证码确认
                    result.put("status", "success");
                    result.put("code", "201");
                    result.put("msg", "提供验证码");
                    newMap.put("code", "201");//201表示需要验证码
                    newMap.put("phoneNumber", map.get("proxyLoginPhone"));
                    newMap.put("electricityTaxPhoneAndPrivance", map.get("electricityTaxPhoneAndPrivance") + map.get("province"));
                    newMap.put("browser", newContext);
                    taskUtil.addTask(newMap);
                    return result;
                } else {
                    result.put("status", "error");
                    result.put("code", "203");
                    result.put("msg", "登录失败，请核对账号密码，联系管理员进行维护");
                    newContext.close();
                    return result;
                }
            }

        }
    }

    /**
     * 验证登录验证码
     *
     * @param map
     * @return
     */
    public Map<String, String> newCheckLoginCode(Map<String, String> map) {
        HashMap<String, String> result = new HashMap<>();
        Map<String, Object> taskMap = checkUserExist(map.get("electricityTaxPhone"));
        BrowserContext context = (BrowserContext) taskMap.get("browser");
        Page page = context.pages().get(0);
        if (doLoginCode(page, map.get("code"))) {
            result.put("status", "success");
            result.put("code", "200");
            result.put("msg", "验证成功");
            taskMap.put("code", "200");//200表示登录成功
            page.waitForTimeout(3000);
            page.getByText("单户办理").click();
            page.waitForTimeout(2000);
            //点击内容为“进入”，class为“el-button el-button--text”的按钮的第一个
            page.locator(".el-button.el-button--text:has-text('进入')").first().click();
            page.waitForTimeout(2000);
            page.getByText("确定").click();
            return result;
        } else {
            result.put("status", "error");
            result.put("code", "201");
            taskMap.put("code", "201");//201表示需要验证码
            result.put("msg", "验证码错误，请重新输入验证码");
            return result;
        }
    }

    public Map<String, String> checkLoginCode(Map<String, String> map) {
        HashMap<String, String> result = new HashMap<>();
        Map<String, Object> taskMap = checkUserExist(map.get("phoneNumber"));
        BrowserContext context = (BrowserContext) taskMap.get("browser");
        Page page = context.pages().get(0);
        if (doLoginCode(page, map.get("code"))) {
            result.put("status", "success");
            result.put("code", "200");
            result.put("msg", "验证成功");
            taskMap.put("code", "200");//200表示登录成功
            return result;
        } else {
            result.put("status", "error");
            result.put("code", "201");
            taskMap.put("code", "201");//201表示需要验证码
            result.put("msg", "验证码错误，请重新输入验证码");
            return result;
        }
    }

    /**
     * 下载发票
     *
     * @param map
     * @return
     */
    public Map<String, String> downInvoice(Map<String, String> map, AccountSets accountSets) {
        HashMap<String, String> result = new HashMap<>();
        Map<String, Object> taskMap = checkUserExist(map.get("phoneNumber"));
        BrowserContext context = (BrowserContext) taskMap.get("browser");
        Page page = context.pages().get(0);
        List<Page> pageList = context.pages();
        for (int i = 1; i < pageList.size(); i++) {
            pageList.get(i).close();
        }
        if (changeIdentity(page, map.get("creditCode"))) {
            //调用下载发票方法
            return dwonInvoice(context, map, accountSets);
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
        Map<String, Object> taskMap = checkUserExist(map.get("phoneNumber"));//shandong*13969134863
        BrowserContext context = (BrowserContext) taskMap.get("browser");
        Page page = context.pages().get(0);
        List<Page> pageList = context.pages();
        for (int i = 1; i < pageList.size(); i++) {
            pageList.get(i).close();
        }
        if (changeIdentity(page, map.get("creditCode"))) {
            //调用下载发票方法
            return downgouxuan(context, map, page, accountSets);
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
        Map<String, Object> taskMap = checkUserExist(map.get("phoneNumber"));
        BrowserContext context = (BrowserContext) taskMap.get("browser");
        Page page = context.pages().get(0);
        List<Page> pageList = context.pages();
        for (int i = 1; i < pageList.size(); i++) {
            pageList.get(i).close();
        }
        if (changeIdentity(page, map.get("creditCode"))) {
            //调用下载发票方法
            return downweigouxuan(context, map, page, accountSets);
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
    public Map<String, Object> checkUserExist(String phoneNumber) {
        List<Map<String, Object>> list = taskUtil.getAllTasks();
        if (list == null) {
            return null;
        } else {
            for (Map<String, Object> map : list) {
                // 检查当前Map是否包含指定的phoneNumber键，并且值相等
                if (map.containsKey("phoneNumber") && phoneNumber.equals(map.get("phoneNumber"))) {
                    return map; // 找到匹配的电话号码
                }
            }
            return null;
        }
    }

    /**
     * 二、检查broswer对象是否失效
     * 1、保留浏览器中第一个标签页面，其他页面关闭。
     * 2、刷新当前页面，点击左上角登录，如果未提示登录页面，则表示用户登录信息有效。login_box
     * 3、如果提示登录页面，则调用登录方法，重新登录。
     */
    public Boolean checkUserStatus(BrowserContext context, String province) {

        List<Page> pageList = context.pages();
        Page page = pageList.get(0);
        for (int i = 1; i < pageList.size(); i++) {
            pageList.get(i).close();
        }
        page.waitForTimeout(4000);
        try {
            // 使用更可靠的选择器和等待机制
            if (page.isVisible("text='我知道了'", new Page.IsVisibleOptions().setTimeout(3000))) {
                page.getByText("我知道了").click();
                // 等待登录按钮出现并点击
                page.waitForSelector(".loginBtn");
                page.querySelector(".loginBtn").click();
            }
        } catch (TimeoutError e) {
            // 如果超时，说明没有"我知道了"按钮，继续后续流程
            page.click(".loginBtn");
        }

        //判断页面中是否存在class=login_box的元素
        if (page.querySelector(".login_box") != null) {
            //需要进行登录
            return false;
        } else {
            //不需要进行登录
            return true;
        }
    }

    /**
     * 三 执行登录操作
     * 1、打开页面，输入登录信息。
     * 2、点击登录，请求前台验证码
     */
    public boolean doLogin(BrowserContext context, Page page, Map<String, String> map) {
        try {
            String province = map.get("province");
            String webUrl = Provice2URL.getUrl(province);
//            String  webUrl= FIRST_TAX_WEB_URL + map.get("province")+ LAST_TAX_WEB_URL;
            page.navigate(webUrl);
            page.waitForSelector(".loginBtn");
            page.waitForTimeout(2000);
            if (page.innerText("body").contains("我知道了")) {
                page.getByText("我知道了").click();
            }
            page.querySelector(".loginBtn").click();
            // 获取页面中统一信用代码输入框
            page.getByPlaceholder("统一社会信用代码/纳税人识别号").fill(map.get("creditCode"));
            page.getByPlaceholder("居民身份证号码/手机号码/用户名").fill(map.get("phoneNumber"));
            page.getByPlaceholder("个人用户密码").fill(map.get("password"));
//            Locator locator = page.locator(".handler");
//            BoundingBox boundingBox = locator.boundingBox();
//            page.mouse().move(boundingBox.x, boundingBox.y);
//            page.waitForTimeout(20);
//            page.mouse().down();
//            Locator dragText = page.locator(".drag_text");
//            BoundingBox dragTextBoundingBox = dragText.boundingBox();
//            page.mouse().move(boundingBox.x + dragTextBoundingBox.width, boundingBox.y);
//            page.waitForTimeout(20);
//            page.mouse().up();
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登录")).click();
            page.waitForSelector(".activeCls:has-text('短信验证')");
            page.getByText("短信验证", new Page.GetByTextOptions().setExact(true)).click();
            page.getByText("获取验证码").click();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

    }

    /**
     * 代理通道登录
     *
     * @param context
     * @param page
     * @param map
     * @return
     */

    public boolean newDoLogin(BrowserContext context, Page page, Map<String, String> map) {
        try {
            String province = map.get("province");
            String webUrl = Provice2URL.getUrl(province);
//            String  webUrl= FIRST_TAX_WEB_URL + map.get("province")+ LAST_TAX_WEB_URL;
            page.navigate(webUrl);
            page.waitForSelector(".loginBtn");
            page.waitForTimeout(2000);
            if (page.innerText("body").contains("我知道了")) {
                page.getByText("我知道了").click();
            }
            page.querySelector(".loginBtn").click();
            page.waitForTimeout(2000);
            if (map.get("phoneNumber").equals(map.get("proxyLoginPhone"))) {
                page.getByText("企业业务").click();
                // 获取页面中统一信用代码输入框
                page.getByPlaceholder("统一社会信用代码/纳税人识别号").fill(map.get("creditCode"));
                page.getByPlaceholder("居民身份证号码/手机号码/用户名").fill(map.get("proxyLoginPhone"));
                page.getByPlaceholder("个人用户密码").fill(map.get("password"));
            } else {
                page.getByText("代理业务").click();
                // 获取页面中统一信用代码输入框
                page.getByPlaceholder("代理机构统一社会信用代码/纳税人识别号").fill(map.get("creditCode"));
                page.getByPlaceholder("居民身份证号码/手机号码/用户名").fill(map.get("proxyLoginPhone"));
                page.getByPlaceholder("个人用户密码").fill(map.get("password"));
            }

//            Locator locator = page.locator(".handler");
//            BoundingBox boundingBox = locator.boundingBox();
//            page.mouse().move(boundingBox.x, boundingBox.y);
//            page.waitForTimeout(20);
//            page.mouse().down();
//            Locator dragText = page.locator(".drag_text");
//            BoundingBox dragTextBoundingBox = dragText.boundingBox();
//            page.mouse().move(boundingBox.x + dragTextBoundingBox.width, boundingBox.y);
//            page.waitForTimeout(20);
//            page.mouse().up();
//            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登录")).click();
            page.getByText("短信验证", new Page.GetByTextOptions().setExact(true)).click();
            page.getByText("获取验证码").click();
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }

    }

    /**
     * 四 输入验证码，进行登录
     * 1、验证码正确，登录成功，确认企业身份。
     * 2、验证码错误，登录失败，告知前台重新输入验证码
     */
    public boolean doLoginCode(Page page, String code) {
        page.getByPlaceholder("请输入短信验证码").fill(code);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("登录")).click();
        page.waitForTimeout(3000);
        //判断页面中是否存在placeholder="请输入短信验证码"的元素
        if (page.innerText("body").contains("请输入短信验证码")) {
            System.out.println("验证码失败");
            return false;
        } else {
            System.out.println("验证码正确，登录成功");
            return true;
        }
    }

    /**
     * 五、切换企业身份
     * 1、切换成功，下载发票
     * 2、未找到对应企业，报错，告知用户
     */
    private boolean changeIdentity(Page page, String shuihao) {
        page.waitForTimeout(3000);
        try {
            if (page.innerText("body").contains(shuihao)) {
                return true;
            } else {
                page.waitForTimeout(2000);
                page.hover(".t-menu__operations");
                page.waitForTimeout(1000);
                page.getByText("身份切换").click();
                page.waitForTimeout(2000);
                if (!page.innerText("body").contains("企业办税")) {
                    page.locator(".el-submenu__title:has-text('身份切换')").click();
                }
                page.waitForTimeout(2000);
                page.getByPlaceholder("请输入统一社会信用代码").fill(shuihao);
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("查询")).click();
                page.waitForTimeout(3000);
                if (page.innerText("body").contains("共 1 条")) {
                    if (page.innerText("body").contains("进入")) {
                        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("进入")).click();
                    } else {
                        page.waitForTimeout(2000);
                        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("切换")).click();
                    }
                    page.waitForTimeout(2000);
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("确定")).click();
                    page.waitForTimeout(2000);
                    return true;
                } else {
                    //点击返回按钮回到起始页
                    page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("返回")).click();
                    return false;
                }
            }
        } catch (Exception e) {
            String webUrl = Provice2URL.getUrl("shandong");
            page.navigate(webUrl);
            page.waitForSelector(".loginBtn");
            page.querySelector(".loginBtn").click();
            return changeIdentity(page, shuihao);
        }

    }

    /**
     * 五、切换企业身份
     * 1、切换成功，下载发票
     * 2、未找到对应企业，报错，告知用户
     */
    private boolean newChangeIdentity(Page page, String shuihao) {
        page.waitForTimeout(2000);
//        return true;
        if (page.innerText("body").contains(shuihao)) {
            return true;
        } else {
            page.hover(".t-menu__operations");
            page.waitForTimeout(500);
            page.getByText("身份切换").click();
            page.waitForTimeout(2000);
            page.getByText("身份切换").click();
            page.waitForTimeout(2000);
            page.getByText("企业办税").click();
            page.waitForTimeout(2000);
            page.getByPlaceholder("请输入统一社会信用代码").fill(shuihao);
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("查询")).click();
            page.waitForTimeout(3000);
            if (page.innerText("body").contains("共 1 条")) {
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("进入")).click();
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
     * 六、下载发票
     */
    private static final ExecutorService executor = Executors.newCachedThreadPool();

    public HashMap<String, String> dwonInvoice(BrowserContext context, Map<String, String> map, AccountSets accountSets) {
        Page page = context.pages().get(0);
        List<Page> pageList = context.pages();
        for (int i = 1; i < pageList.size(); i++) {
            pageList.get(i).close();
        }

        HashMap<String, String> result = new HashMap<>();
        try {
            page.waitForSelector(".leftMain", new Page.WaitForSelectorOptions().setTimeout(10000));
            page.getByPlaceholder("请输入关键词").fill("发票查询统计");
            try{
                page.waitForSelector(".popMainScreen", new Page.WaitForSelectorOptions().setTimeout(5000));
            }catch (Exception e){
                page.querySelector(".t-icon.t-icon-search.search-style").click();
            }
            Page page1 = context.waitForPage(() -> page.querySelector(".popMainScreen").click());
            page1.waitForSelector(".g-card-menu__content");
            page1.locator(".g-card-menu__content .g-card-menu-item:first-child").click();
            setQueryCondition(map.get("startDate"), map.get("endDate"), map.get("invoiceType"), page1);
            if (!page1.innerText("body").contains("暂无数据")) {
                page1.onDownload(download -> {
                    // Playwright 的 onDownload 事件在下载完成后触发，此处路径已可用
                    System.out.println("下载文件连接: " + download.url());
                    System.out.println("下载文件名称: " + download.suggestedFilename());
                    System.out.println("下载文件地址: " + download.path());

                    // 提交任务到共享线程池
                        try {
                            // 修正方法名拼写为 changeFileName
                            String newFileUrl = changeFileName(
                                    download.path().toString(),
                                    map.get("creditCode")
                            );

                            // 确保文件重命名成功后再读取
                            if (Files.exists(Paths.get(newFileUrl))) {
                                readExcelFile(newFileUrl, accountSets, map);
                            } else {
                                log.error("文件重命名失败: " + newFileUrl);
                            }
                        } catch (Exception e) {
                            // 添加异常处理逻辑
                            e.printStackTrace();
                            System.err.println("处理下载文件时发生错误: " + e.getMessage());
                        }
                });
                page1.waitForSelector(".button__export");
                page1.getByText("导出").click();
                page1.getByText("导出全部").click();
                page.waitForTimeout(5000);
                result.put("status", "success");
                result.put("code", "200");
                result.put("msg", map.get("invoiceType") + "[下载成功]");
                return result;
            } else {
                result.put("status", "success");
                result.put("code", "200");
                result.put("msg", map.get("invoiceType") + "查询时间段内该企业无发票信息");
                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
            result.put("status", "error");
            result.put("code", "200");
            result.put("msg", map.get("invoiceType") + "未知错误，请联系技术人员");
            return result;
        }

    }

    /**
     * 六、下载勾选发票
     */


    public HashMap<String, String> downgouxuan(BrowserContext context, Map<String, String> map, Page page, AccountSets accountSets) {
        HashMap<String, String> result = new HashMap<>();
        page.reload();
        try {
//            page.waitForSelector(".leftMain", new Page.WaitForSelectorOptions().setTimeout(10000));
            page.waitForTimeout(3000);
            // 获得第一个页面
            page.getByPlaceholder("请输入关键词").fill("发票用途确认");
            try {
                page.waitForSelector(".popMainScreen", new Page.WaitForSelectorOptions().setTimeout(5000));
            } catch (Exception e) {
                page.querySelector(".t-icon.t-icon-search.search-style").click();
            }
            Page page1 = context.waitForPage(() -> page.querySelector(".popMainScreen").click());
            page1.waitForSelector(".g-card-menu__content .g-card-menu-item:first-child");
            try {
                page1.waitForSelector("text=尊敬的纳税人", new Page.WaitForSelectorOptions().setTimeout(10000));
                page1.querySelector(".t-dialog.t-dialog__modal-default.t-dialog--default:has-text('尊敬的纳税人')").querySelector(".t-button__text:has-text('确定')").click();
            } catch (Exception e) {
            }
            page1.locator(".g-card-menu__content .g-card-menu-item:first-child").click();
            page1.waitForSelector(".t-tabs__nav-item-text-wrapper:has-text('统计确认')");
            try {
                page1.waitForSelector("text=当前有待处理农产品发票", new Page.WaitForSelectorOptions().setTimeout(5000));
                page1.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("暂不处理").setExact(true)).click();
            } catch (Exception e) {
            }
            page1.querySelector(".t-tabs__nav-item-text-wrapper:has-text('统计确认')").click();
            page1.waitForSelector(".fontsize.paddingright:has-text('税款所属期')");
            String taxPeriod = page1.querySelector(".fontsize.paddingright:has-text('税款所属期')").textContent();
            // 提取年月信息，格式：税款所属期：2025年08月
            String yearMonthStr = taxPeriod.replace("税款所属期：", "").trim();
            int year = Integer.parseInt(yearMonthStr.substring(0, 4));
            int month = Integer.parseInt(yearMonthStr.substring(5, 7));

            // 获取当前时间的上个月
            java.time.LocalDate now = java.time.LocalDate.now();
            java.time.YearMonth lastMonth = java.time.YearMonth.from(now).minusMonths(1);

            String downButtonCSS = "";
            String downButton = "";
            // 比较年月
            if (year != lastMonth.getYear() || month != lastMonth.getMonthValue()) {
                // TODO: 税款所属期与上个月日期一致，执行相应逻辑
                page1.waitForSelector(".t-button__text:has-text('查看历史确认信息')");
                downButtonCSS = ".t-button.t-button--variant-base.t-button--theme-primary:has-text('下载')";
                downButton = "下载";
                page1.querySelector(".t-button__text:has-text('查看历史确认信息')").click();
            }else{
                downButtonCSS = ".t-button.t-button--variant-base.t-button--theme-default:has-text('下载发票明细')";
                downButton = "下载发票明细";
            }
            try {

                page1.waitForSelector(downButtonCSS, new Page.WaitForSelectorOptions().setTimeout(3000));
            } catch (Exception e) {
                result.put("status", "success");
                result.put("code", "200");
                result.put("msg", map.get("invoiceType") + "查询时间段内该企业无发票信息");
                return result;
            }
            if (!page1.innerText("body").contains("暂无数据")) {
                page1.onDownload(download -> {
                    // Playwright 的 onDownload 事件在下载完成后触发，此处路径已可用
                    System.out.println("下载文件连接: " + download.url());
                    System.out.println("下载文件名称: " + download.suggestedFilename());
                    System.out.println("下载文件地址: " + download.path());
                    // 提交任务到共享线程池
                        try {
                            String newFileUrl = changeFileName(
                                    download.path().toString(),
                                    map.get("creditCode") + "-抵扣类发票"
                            );
                            // 确保文件重命名成功后再读取
                            if (Files.exists(Paths.get(newFileUrl))) {
                                Set<Date> dateList = checkInvoiceReadExcelFile(newFileUrl);
                                for (Date date : dateList) {
                                    String startDate = getFirstDayOfMonth(date);
                                    String endDate = getLastDayOfMonth(date);
                                    String invoiceType = "取得发票";
                                    map.put("invoiceType", invoiceType);
                                    map.put("startDate", startDate);
                                    map.put("endDate", endDate);
                                    dwonInvoice(context, map, accountSets);
                                }
                                readExcelFile2UpDateInvoice(newFileUrl, map);
                            } else {
                                System.err.println("文件重命名失败: " + newFileUrl);
                            }
                        } catch (Exception e) {
                            // 添加异常处理逻辑
                            e.printStackTrace();
                            System.err.println("处理下载文件时发生错误: " + e.getMessage());
                        }
                });
                page1.waitForTimeout(1000);
                page1.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(downButton)).click();
                page1.waitForTimeout(5000);
                result.put("status", "success");
                result.put("code", "200");
                result.put("msg", map.get("invoiceType") + "[下载成功]");
                return result;
            } else {
                result.put("status", "success");
                result.put("code", "200");
                result.put("msg", map.get("invoiceType") + "查询时间段内该企业无发票信息");
                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
            result.put("status", "error");
            result.put("code", "200");
            result.put("msg", map.get("invoiceType") + "未知错误，请联系技术人员");
            return result;
        }
    }

    /**
     * 下载未勾选发票
     */
    public HashMap<String, String> downweigouxuan(BrowserContext context, Map<String, String> map, Page page, AccountSets accountSets) {
        HashMap<String, String> result = new HashMap<>();
        page.reload();
        try {
            page.waitForSelector(".leftMain", new Page.WaitForSelectorOptions().setTimeout(10000));
            // 获得第一个页面
            page.getByPlaceholder("请输入关键词").fill("发票用途确认");
            try {
                page.waitForSelector(".popMainScreen", new Page.WaitForSelectorOptions().setTimeout(5000));
            } catch (Exception e) {
                page.querySelector(".t-icon.t-icon-search.search-style").click();
            }
            Page page1 = context.waitForPage(() -> page.querySelector(".popMainScreen").click());
            page1.waitForSelector(".g-card-menu__content .g-card-menu-item:first-child");
            try {
                page1.waitForSelector("text=尊敬的纳税人", new Page.WaitForSelectorOptions().setTimeout(10000));
                page1.querySelector(".t-dialog.t-dialog__modal-default.t-dialog--default:has-text('尊敬的纳税人')").querySelector(".t-button__text:has-text('确定')").click();
            } catch (Exception e) {
            }
            page1.locator(".g-card-menu__content .g-card-menu-item:first-child").click();
            page1.waitForSelector(".t-tabs__nav-item-text-wrapper:has-text('统计确认')");
            try {
                page1.waitForSelector("text=当前有待处理农产品发票", new Page.WaitForSelectorOptions().setTimeout(5000));
                page1.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("暂不处理").setExact(true)).click();
            } catch (Exception e) {
            }
            page1.waitForSelector(".t-tabs__nav-item-text-wrapper:has-text('统计确认')");
                setQueryweigouxuanCondition(map.get("startDate"), map.get("endDate"), map.get("invoiceType"), page1);
                if (!page1.innerText("body").contains("暂无数据")) {
                    page1.onDownload(download -> {
                        // Playwright 的 onDownload 事件在下载完成后触发，此处路径已可用
                        System.out.println("下载文件连接: " + download.url());
                        System.out.println("下载文件名称: " + download.suggestedFilename());
                        System.out.println("下载文件地址: " + download.path());
                        // 提交任务到共享线程池
                            try {
                                String newFileUrl = changeFileName(
                                        download.path().toString(),
                                        map.get("creditCode") + "-未勾选发票"
                                );
                                // 确保文件重命名成功后再读取
                                if (Files.exists(Paths.get(newFileUrl))) {
                                    Set<Date> dateList = uncheckInvoiceReadExcelFile(newFileUrl);
                                    for (Date date : dateList) {
                                        String startDate = getFirstDayOfMonth(date);
                                        String endDate = getLastDayOfMonth(date);
                                        String invoiceType = "取得发票";
                                        map.put("invoiceType", invoiceType);
                                        map.put("startDate", startDate);
                                        map.put("endDate", endDate);
                                        dwonInvoice(context, map, accountSets);
                                    }
                                    readExcelFileUpDateInvoice(newFileUrl, map);
                                } else {
                                    System.err.println("文件重命名失败: " + newFileUrl);
                                }
                            } catch (Exception e) {
                                // 添加异常处理逻辑
                                e.printStackTrace();
                                System.err.println("处理下载文件时发生错误: " + e.getMessage());
                            }
                    });
                    page1.waitForTimeout(3000);
                    page1.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("导出")).click();
                    page1.waitForTimeout(3000);
                    result.put("status", "success");
                    result.put("code", "200");
                    result.put("msg", map.get("invoiceType") + "发票下载成功");
                    return result;
                } else {
                    result.put("status", "success");
                    result.put("code", "200");
                    result.put("msg", map.get("invoiceType") + "查询时间段内该企业无发票信息");
                    return result;
                }
        } catch (Exception e) {
            e.printStackTrace();
            result.put("status", "error");
            result.put("code", "200");
            result.put("msg", map.get("invoiceType") + "未知错误，请联系技术人员");
            return result;
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


    /*
     * 修改文件名称
     * */
    public String changeFileName(String originalFilePath, String newFileName) {
        File originalFile = new File(originalFilePath);

        // 新的文件名和后缀
        String newFileExtension = ".xlsx"; // 例如改为.xlsx

        // 获取原始文件的父目录
        String parentDirectory = originalFile.getParent();

        // 构建新的文件路径
        String newFilePath = parentDirectory + File.separator + newFileName + "_" + new Date().getTime() + newFileExtension;
        File newFile = new File(newFilePath);

        // 重命名文件
        if (originalFile.renameTo(newFile)) {
            System.out.println("文件重命名成功");
            return newFilePath;
        } else {
            System.out.println("文件重命名失败");
            return null;
        }

    }

    /**
     * 读取excel信息，导入数据库中
     *
     * @param filePath
     */
    public void readExcelFile(String filePath, AccountSets accountSets, Map<String, String> map) {
        //对 excel 进行处理，将它转换为可以导入模版的样式。
        ExcelProcessor excelProcessor = new ExcelProcessor();
        try {
            excelProcessor.excue(filePath);

            File file = new File(filePath);
            FileInputStream input = new FileInputStream(file);
            if (map.get("invoiceSaveType").equals("2")) {
                invoiceImportService.saveImportInvoiceSeason(accountSets.getCreditCode(), input, accountSets.getId(), map);
            } else {
                invoiceImportService.saveImportInvoice(accountSets.getCreditCode(), input, accountSets.getId(), map);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 读取excel信息，导入数据库中
     *
     * @param filePath
     */
    public void readExcelFile2UpDateInvoice(String filePath, Map<String, String> map) {
        try (FileInputStream file = new FileInputStream(filePath);
             Workbook workbook = WorkbookFactory.create(file)) {

            // 2. 获取工作表（根据名称或索引）
            Sheet sheet = workbook.getSheetAt(0);

            // 3. 数据格式化工具
            DataFormatter dataFormatter = new DataFormatter();
            String checkDate = dataFormatter.formatCellValue(sheet.getRow(1).getCell(5));
            checkDate = checkDate.substring(0, 4) + "-" + checkDate.substring(4, 6) + "-01";
            // 4. 遍历行（跳过标题行）
            for (Row row : sheet) {
                Invoice invoice = new Invoice();
                if (row.getRowNum() < 3) continue;
                // 5. 读取单元格数据
                String code = dataFormatter.formatCellValue(row.getCell(4));

                String code1 = dataFormatter.formatCellValue(row.getCell(6));
                invoice.setAssignmentDate(LocalDate.parse(checkDate));
                invoice.setShowStatus(map.get("invoiceType"));
                invoice.setInvoiceNumber(code);
                invoice.setInvoiceNumber1(code1);
                invoice.setShowStatus("已勾选");
                if (StringUtils.isNotEmpty(checkDate)) {
                    invoice.setCheckRiQi(LocalDate.parse(checkDate));
                    checkInvoiceService.updateByInvoiceNumber(invoice);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }


    }

    /**
     * 读取未勾选当月的excel信息，导入数据库中
     */
    public void readExcelFileUpDateInvoice(String filePath, Map<String, String> map) {
        try (FileInputStream file = new FileInputStream(filePath);
             Workbook workbook = WorkbookFactory.create(file)) {

            // 2. 获取工作表（根据名称或索引）
            Sheet sheet = workbook.getSheetAt(0);

            // 3. 数据格式化工具
            DataFormatter dataFormatter = new DataFormatter();
            // 4. 遍历行（跳过标题行）
            for (Row row : sheet) {
                Invoice invoice = new Invoice();
                if (row.getRowNum() < 1) continue;
                // 5. 读取单元格数据

                String code = dataFormatter.formatCellValue(row.getCell(2));

                String code1 = dataFormatter.formatCellValue(row.getCell(4));
//                invoice.setAssignmentDate(LocalDate.parse(checkDate));
                invoice.setShowStatus(map.get("invoiceType"));
                invoice.setInvoiceNumber(code);
                invoice.setInvoiceNumber1(code1);
                invoice.setShowStatus("未勾选");
                checkInvoiceService.updateByInvoiceNumber(invoice);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }


    }

    /**
     * 设置查询条件
     *
     * @param startDate
     * @param endDate
     * @param page
     */
    private void setQueryCondition(String startDate, String endDate, String invoiceType, Page page) {
        page.waitForSelector(".t-input__inner");
        page.waitForTimeout(2000);
        if (page.innerText("body").contains("当前有待处理农产品发票")) {
            page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("暂不处理").setExact(true)).click();
        }
        if ("取得发票".equals(invoiceType)) {
            Locator locator = page.locator(".t-input__inner").nth(0);
            locator.click();
            page.click("li[title='取得发票']");
        }
        // 选择正常的发票，暂不处理，暂时放在导入数据时进行判断。
        String removeJsTemplate =
                "document.getElementsByClassName('t-input__inner')[$].removeAttribute" + "('readonly');";
        //开始时间
        page.evaluate(removeJsTemplate.replace("$", "12"));
        Locator startTimeLocator = page.locator(".t-input__inner").nth(12);
        analogKey(startTimeLocator, startDate);
        page.locator(".t-date-picker__cell--active").click();
        //结束时间
        Locator endTimeLocator = page.locator(".t-input__inner").nth(13);
        page.evaluate(removeJsTemplate.replace("$", "13"));
        analogKey(endTimeLocator, endDate);
        page.locator(".t-date-picker__cell--active").nth(1).click();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("查询")).click();
        page.waitForTimeout(6000);
    }

    /**
     * 设置查询条件
     *
     * @param startDate
     * @param endDate
     * @param page
     */
    private void setQueryweigouxuanCondition(String startDate, String endDate, String invoiceType, Page page) {
        page.waitForSelector(".t-tabs__nav-item-text-wrapper");
        page.getByText("更多查询").click();

        if ("未勾选".equals(invoiceType)) {
            Locator locator = page.locator(".t-input__inner").nth(0);
            locator.click();
            page.click("li[title='未勾选']");
        }
        String removeJsTemplate =
                "document.getElementsByClassName('t-input__inner')[$].removeAttribute" + "('readonly');";
        //发票开始时间
        page.evaluate(removeJsTemplate.replace("$", "1"));
        Locator startTimeLocator = page.locator(".t-input__inner").nth(1);
        String newStartDate = "2010-01-01";
        analogKey(startTimeLocator, newStartDate);
        page.locator(".t-date-picker__cell--active").click();
        //发票结束时间
        Locator endTimeLocator = page.locator(".t-input__inner").nth(2);
        page.evaluate(removeJsTemplate.replace("$", "2"));
        analogKey(endTimeLocator, getCurrentMonthLastDay());
        page.locator(".t-date-picker__cell--active").nth(1).click();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("查询")).click();
        page.waitForTimeout(6000);
    }

    /**
     * 修改查询日期
     *
     * @param locator
     * @param dateTime
     */
    private void analogKey(Locator locator, String dateTime) {
        locator.click();
        locator.press("Control+A");
        locator.press("Backspace");
        for (char c : dateTime.toCharArray()) {
            locator.press(String.valueOf(c));
        }
    }


    //    导入账套，start
    public void importAccountSet(UserVo userVo) {
        //读取excel文件，查看共有多少客户，每个客户创建账套
        String filePath = "E:\\qiye.xlsx";

        try (FileInputStream file = new FileInputStream(filePath);
             Workbook workbook = WorkbookFactory.create(file)) {

            // 2. 获取工作表（根据名称或索引）
            Sheet sheet = workbook.getSheet("企业列表"); // 或 workbook.getSheetAt(0)

            // 3. 数据格式化工具
            DataFormatter dataFormatter = new DataFormatter();

            // 4. 遍历行（跳过标题行）
            for (Row row : sheet) {
                AccountSets accountSets = new AccountSets();
                if (row.getRowNum() == 0) continue;
                // 5. 读取单元格数据
                accountSets.setCompanyName(dataFormatter.formatCellValue(row.getCell(0))); // 企业名称
                accountSets.setAccountStandard(AccountStandard.valueOf(dataFormatter.formatCellValue(row.getCell(1)))); // 会计准则
                accountSets.setVatType(VatType.valueOf(dataFormatter.formatCellValue(row.getCell(2)))); // 增值税种类
                accountSets.setVoucherReviewed(Boolean.valueOf(dataFormatter.formatCellValue(row.getCell(3)))); // 凭证审核
                accountSets.setCreditCode(dataFormatter.formatCellValue(row.getCell(4))); // 纳税人识别号
                accountSets.setEnableDate(LocalDate.parse(dataFormatter.formatCellValue(row.getCell(5)) + "-01")); // 启账期间
                accountSets.setProvince(dataFormatter.formatCellValue(row.getCell(6))); // 省份
                accountSets.setAstId(Long.valueOf(dataFormatter.formatCellValue(row.getCell(7))));
                accountSets.setElectricityTaxCode("Qy142536"); // 纳税人识别号
                AppConfig config = appConfigService.load();

                accountSets.setCurrentAccountDate(accountSets.getEnableDate());
                accountSets.setCreatorId(userVo.getId());
                accountSets.setEffectiveDate(LocalDate.now().plusYears(1000));
                accountSetsService.save(accountSets, true);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }


    }


    /**
     * 获取待缴税款
     */
    @Transactional
    public void getTaxAmount(Page page, Map<String, String> map, BrowserContext context) {
        page.waitForTimeout(100);
        page.getByPlaceholder("请输入关键词").fill("税费缴纳");
        page.waitForTimeout(2000);
        Page page1 = context.waitForPage(() -> page.querySelector(".popMainScreen").click());
        page.waitForTimeout(3000);
        String imUrl = screenViewFull(page1, map.get("creditCode"));
        jqf.delete(qImagePayment).where(qImagePayment.accountSetsId.eq(Long.valueOf(map.get("accountSetsId"))));
        Map<String, Object> taxAmount = new HashMap<>();
        taxAmount.put("accountSetsId", Long.valueOf(map.get("accountSetsId")));
        taxAmount.put("imageUrl", imUrl);
        taxAmount.put("id", System.currentTimeMillis());
        long i = lazyDao.executeSql("insertTaxAmount", taxAmount);
    }

    public void getTaxAmount(Page page, String creditCode, Long accountSetsId, BrowserContext context) {
        page.waitForTimeout(100);
        page.getByPlaceholder("请输入关键词").fill("税费缴纳");
        page.waitForTimeout(2000);
        Page page1 = context.waitForPage(() -> page.querySelector(".popMainScreen").click());
        page.waitForTimeout(5000);
        String imUrl = screenViewFull(page1, creditCode);
        jqf.delete(qImagePayment).where(qImagePayment.accountSetsId.eq(accountSetsId));
        Map<String, Object> taxAmount = new HashMap<>();
        taxAmount.put("accountSetsId", accountSetsId);
        taxAmount.put("imageUrl", imUrl);
        taxAmount.put("id", System.currentTimeMillis());
        long i = lazyDao.executeSql("insertTaxAmount", taxAmount);
    }

//    导入账套，end
//  一键报税，start

    /**
     * @param map
     * @return 获取账套需要报税的项目
     */
    public List<Map<String, String>> oneKeyTax(Map<String, String> map, String phoneNumber) {
        List<Map<String, String>> result = new ArrayList<>();
        Map<String, Object> taskMap = checkUserExist(phoneNumber);
        BrowserContext context = (BrowserContext) taskMap.get("browser");
        Page page = context.pages().get(0);
        List<Page> pageList = context.pages();
        for (int i = 1; i < pageList.size(); i++) {
            pageList.get(i).close();
        }
        page.waitForTimeout(2500);
        //保证页面返回后，页面加载完成
        if (changeIdentity(page, map.get("creditCode"))) {
            page.waitForTimeout(2500);
            long startTime = System.currentTimeMillis();
            long timeout = 30000; // 30秒超时
            while (true) {
                if (!page.innerText(".rightMain").contains("暂无数据")) {
                    break; //跳出循环
                }
                // 检查是否超时
                if (System.currentTimeMillis() - startTime > timeout) {
                    Map<String, String> errorMap = new HashMap<>();
                    errorMap.put("status", "error");
                    errorMap.put("code", "202");
                    errorMap.put("msg", "页面加载超时，请稍后重试");
                    List<Map<String, String>> errorResult = new ArrayList<>();
                    errorResult.add(errorMap);
                    return errorResult;
                }
                page.waitForTimeout(1000); // 每秒检查一次
            }
            //调用下载报税项目方法
            List<Map<String, String>> data = HtmlTableParser(page);

            // 获取待交税款
            getTaxAmount(page, map, context);

            return data;
        } else {
            //切换公司失败，报错，告知用户
            Map<String, String> errorMap = new HashMap<>();
            errorMap.put("status", "error");
            errorMap.put("code", "202");
            errorMap.put("msg", "当前账号的办税员身份与公司不匹配，切换登录账号或者联系管理员进行维护");
            result.add(errorMap);
            return result;
        }
    }

    /**
     * 获取需要报税的界面表格
     */
    public List<Map<String, String>> HtmlTableParser(Page page) {
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

    public Map<String, String> oneKeyTaxButton(Map<String, String> taskDataMap, LocalDate taskDate) {
//        Boolean flag = taxService.getTaskStatus("财务报表报送",taskDataMap.get("companyName"),taskDate);
        Map<String, String> result = new HashMap<>();

//        if(!"财务报表报送（季报）".equals(taskDataMap.get("taxType")) && !"财务报表报送（年报）".equals(taskDataMap.get("taxType")) && !flag){
//            // 如果报税条目不是财务报表报送，并且财务报表报送还没有完成。直接退出方法
//            result.put("status","error");
//            result.put("msg","财务报表推送未完成，"+taskDataMap.get("companyName")+taskDataMap.get("taxType")+"无法申报");
//            return result;
//        }
        Map<String, Object> taskMap = checkUserExist(taskDataMap.get("taxSystemKey"));
        BrowserContext context = (BrowserContext) taskMap.get("browser");
        Page page = context.pages().get(0);
        List<Page> pageList = context.pages();
        for (int i = 1; i < pageList.size(); i++) {
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
                result = caishuibaobiao(page1, taskDataMap);
            } else if ("增值税及附加税费申报（一般纳税人适用）".equals(taskDataMap.get("taxType"))) {
                page.getByPlaceholder("请输入关键词").fill("增值税及附加税费申报（一般纳税人适用）");
                page.waitForTimeout(2000);
                page1 = context.waitForPage(() -> page.querySelector(".popMainScreen").click());
                result = zengzhishuibaobiao(page1, taskDataMap);

            } else if ("增值税及附加税费申报（小规模纳税人）".equals(taskDataMap.get("taxType"))) {
                page.getByPlaceholder("请输入关键词").fill("增值税及附加税费申报（小规模纳税人）");
                page.waitForTimeout(2000);
                page1 = context.waitForPage(() -> page.querySelector(".popMainScreen").click());
                result = zengzhishuibaobiao(page1, taskDataMap);
            } else if ("居民企业（查账征收）企业所得税月（季）度申报".equals(taskDataMap.get("taxType"))) {
                page.waitForTimeout(100);
                page.getByPlaceholder("请输入关键词").fill("居民企业（查账征收）企业所得税月（季）度申报");
                page.waitForTimeout(2000);
                page1 = context.waitForPage(() -> page.querySelector(".popMainScreen").click());
                result = juminqiye(page1, taskDataMap);
            } else if ("财产和行为税税源采集及合并申报".equals(taskDataMap.get("taxType"))) {
                page.waitForTimeout(100);
                page.getByPlaceholder("请输入关键词").fill("财产和行为税税源采集及合并申报");
                page.waitForTimeout(2000);
                page1 = context.waitForPage(() -> page.querySelector(".popMainScreen").click());
                result = caichanhexingwei(page1, taskDataMap);
            } else {
                result.put("status", "error");
                result.put("code", "202");
                result.put("msg", "匹配错误");
            }
        } else {
            result.put("status", "error");
            result.put("code", "202");
            result.put("msg", "无办税员身份");
        }
        return result;
    }

    @Autowired
    public ReportService reportService;
    //财税报表start
    private static final int TIMEOUT_MS = 10000;

    public Map<String, String> caishuibaobiao(Page page, Map<String, String> map) {
        Map<String, String> result = new HashMap<>();
        List<String> img = new ArrayList<>();
        try {
            page.waitForTimeout(5000);
            if (page.innerText("body").contains("检测不通过")) {
                img.add(screenView(page, map.get("creditCode")));
                result.put("status", "error");
                result.put("code", "203");
                result.put("msg", "检测不通过");
                result.put("tipImg", JSONUtil.toJsonStr(img));
                return result;
            }
            if (page.innerText("body").contains("继续办理")) {
                img.add(screenView(page, map.get("creditCode")));
                page.getByText("继续办理").click();
            }

            if ("财务报表报送（季报）".equals(map.get("taxType"))) {
                page.getByText("季报").click();
            } else if ("财务报表报送（年报）".equals(map.get("taxType"))) {
                page.getByText("年报").click();
            }
            page.getByText("财报转换").click();
            page.getByText("下一步").click();
            page.waitForTimeout(1000);
            if (page.innerText("body").contains("该所属期已完成申报，是否要进行该所属期的申报错误更正")) {
                result.put("status", "success");
                result.put("code", "200");
                result.put("msg", "申报成功:");
                return result;
            }
            try {
                page.locator("input[type='file']").setInputFiles(Paths.get("D:\\financialExcel\\" + map.get("province") + "\\" + map.get("companyName") + "资产负债表.xls"));
                page.locator("input[type='file']").setInputFiles(Paths.get("D:\\financialExcel\\" + map.get("province") + "\\" + map.get("companyName") + "利润表.xls"));         //切换公司失败，报错，告知用户
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("转换报表")).click();
            } catch (Exception e) {
                // 处理异常情况
                result.put("status", "error");
                result.put("code", "203");
                result.put("msg", "文件下载不全:");
                return result;
            }

            //点击页面的提交申报按钮
            //找到内容为"我要填表"的按钮，进行点击
            ElementHandle submitFormButton = page.waitForSelector("span:text(' 提交申报 ')");
            submitFormButton.click();
            // 等待 Canvas 元素加载并获取句柄
            page.waitForSelector("canvas");
            ElementHandle canvas = page.querySelector("canvas");
            // 获取 Canvas 的边界框（坐标和宽高）
            BoundingBox box = canvas.boundingBox();

            // 提取 Canvas 的左上角坐标和尺寸
            double canvasX = box.x;
            double canvasY = box.y;
            int canvasWidth = 630; // 630（根据 HTML 定义）
            int canvasHeight = 80; // 80（根据 HTML 定义）
            // 计算绝对坐标（Canvas 左上角 + 相对偏移）
            double targetX1 = canvasX + 45 * 3 - 13;
            double targetY1 = canvasY + 80 / 4;
            double targetX2 = canvasX + 45 * 6 + 13;
            double targetY2 = canvasY + 80 * 3 / 5;
            double targetX3 = canvasX + 45 * 9 + 5;
            double targetY3 = canvasY + 80 / 2;
            double targetX4 = canvasX + 45 * 11 + 13;
            double targetY4 = canvasY + 80 * 3 / 5;
            // 获取鼠标控制器
            Mouse mouse = page.mouse();
            // 点击第一个位置
            mouse.move(targetX1, targetY1);
            mouse.click(targetX1, targetY1);
            // 点击第二个位置
            mouse.move(targetX2, targetY2);
            mouse.click(targetX2, targetY2);
            // 点击第三个位置
            mouse.move(targetX3, targetY3);
            mouse.click(targetX3, targetY3);
            // 点击第四个位置
            mouse.move(targetX4, targetY4);
            mouse.click(targetX4, targetY4);
            //获取page1页面中class名为t-dialog__header-content标签内容为"信息确认"的元素
            ElementHandle confirmButton = page.querySelector(".t-dialog__header-content:text('信息确认')");
            //获取confirmButton的祖先元素class名为t-dialog t-dialog--default t-dialog__modal-default的div元素
            ElementHandle dialogElement = confirmButton.evaluateHandle("el => el.closest('.t-dialog.t-dialog--default.t-dialog__modal-default')").asElement();
            //获取dialogElement元素中class名为t-button__text标签内容为"确定"的元素
            ElementHandle confirmButtonElement = dialogElement.querySelector(".t-button__text:text('确定')");
            //点击确定按钮
            page.waitForTimeout(3000);
            confirmButtonElement.click();
            page.waitForTimeout(5000);
            img.add(screenView(page, map.get("creditCode")));
            result.put("tipImg", JSONUtil.toJsonStr(img));
            result.put("status", "success");
            result.put("code", "200");
            result.put("msg", "申报成功");
            return result;
        } catch (Exception e) {
            // 处理异常情况
            result.put("status", "error");
            result.put("code", "203");
            result.put("msg", "操作失败: ");
            return result;
        }


    }

    private static void processTableData(Locator tableContainer) {
        // 获取所有数据行
        List<Locator> rows = tableContainer.locator("tbody tr").all();
        System.out.println("发现 " + rows.size() + " 行数据");

        // 处理每行数据
        rows.forEach(row -> {
            // 获取目标单元格
            List<Locator> targetCells = row.locator("td.title01").all();

            targetCells.forEach(cell -> {
                // 获取并打印文本
                String cellText = cell.innerText().trim();
                System.out.println("提取到数据: " + cellText);

                // 这里可以添加自定义处理逻辑
                // 例如保存到数据库或集合中
            });
        });

        System.out.println("数据提取完成");
    }

    //财税报表end
    //增值税及附加税费申报start
    public Map<String, String> zengzhishuibaobiao(Page page, Map<String, String> map) {
        List<String> img = new ArrayList<>();
        HashMap<String, String> result = new HashMap<>();
        try {
            page.waitForTimeout(3000);
            if (page.innerText("body").contains("检测不通过")) {
                img.add(screenView(page, map.get("creditCode")));
                result.put("tipImg", JSONUtil.toJsonStr(img));
                result.put("status", "error");
                result.put("msg", "检测不通过");
                return result;
            }
            if (page.innerText("body").contains("请先完成抄报税后再进行办理")) {
                img.add(screenView(page, map.get("creditCode")));
                result.put("tipImg", JSONUtil.toJsonStr(img));
                result.put("status", "error");
                result.put("msg", "税局提示：请先完成抄报税后再进行办理");
                return result;
            }
            page.waitForTimeout(5000);
            if ((page.innerText("body").contains("确定"))) {
                page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("确定")).click();
            }
            //判断一下class为gov-inspect-header__status-text，内容为"检测不通过"的元素是否存在

            page.waitForTimeout(3000);
            if (page.innerText("body").contains("我要填表")) {
                //找到内容为"我要填表"的按钮，进行点击
                ElementHandle fillFormButton = page.waitForSelector("span:text(' 我要填表 ')");
                fillFormButton.click();
            }
//             等待3秒
            page.waitForTimeout(3000);
            //获取页面上的日期
            // 定位包含"税款所属时间"的元素，并等待其加载
            Locator taxTimeLocator = page.locator("text=税款所属").first();
            taxTimeLocator.waitFor(); // 等待元素出现

            // 获取元素的文本内容
            String textContent = taxTimeLocator.textContent();
            // 定义日期正则表达式
            Pattern pattern = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
            Matcher matcher = pattern.matcher(textContent);
            page.waitForTimeout(3000);
            // 提取所有匹配的日期
            List<String> dateList = new ArrayList<>();
            while (matcher.find()) {
                dateList.add(matcher.group());
            }
            // 获取两个日期
            String startDate = dateList.get(0);  // "2025-04-01"
            String endDate = dateList.get(1);    // "2025-04-30"
            map.put("startDate", String.valueOf(LocalDate.parse(startDate)));
            map.put("endDate", String.valueOf(LocalDate.parse(endDate)));
            //调用查询发票接口，查询当前账套是否有特殊发票
            Long count = taxService.invoiceCount(map);
            if (count > 0) {
                //有特殊发票
                result.put("status", "error");
                result.put("code", "208");
                result.put("msg", "有特殊发票");
                return result;
            }
            //点击页面的提交申报按钮
            //找到内容为"我要填表"的按钮，进行点击
            ElementHandle submitFormButton = page.waitForSelector("span:text(' 提交申报 ')");
            submitFormButton.click();
            if (page.innerText("body").contains("是否立即提交申报表")) {
                page.getByText("返回修改").click();
                img.add(screenView(page, map.get("creditCode")));
                result.put("tipImg", JSONUtil.toJsonStr(img));
                result.put("status", "error");
                result.put("msg", "申报表校验结果中存在提示类问题，请先修改或确认无误后在提交申报表");
                return result;
            }
            // 等待 Canvas 元素加载并获取句柄
            page.waitForSelector("canvas");
            ElementHandle canvas = page.querySelector("canvas");
            // 获取 Canvas 的边界框（坐标和宽高）
            BoundingBox box = canvas.boundingBox();

            // 提取 Canvas 的左上角坐标和尺寸
            double canvasX = box.x;
            double canvasY = box.y;
            int canvasWidth = 630; // 630（根据 HTML 定义）
            int canvasHeight = 80; // 80（根据 HTML 定义）
            // 计算绝对坐标（Canvas 左上角 + 相对偏移）
            double targetX1 = canvasX + 45 * 3 - 13;
            double targetY1 = canvasY + 80 / 4;
            double targetX2 = canvasX + 45 * 6 + 13;
            double targetY2 = canvasY + 80 * 3 / 5;
            double targetX3 = canvasX + 45 * 9 + 5;
            double targetY3 = canvasY + 80 / 2;
            double targetX4 = canvasX + 45 * 11 + 13;
            double targetY4 = canvasY + 80 * 3 / 5;
            // 获取鼠标控制器
            Mouse mouse = page.mouse();
            // 点击第一个位置
            mouse.move(targetX1, targetY1);
            mouse.click(targetX1, targetY1);
            // 点击第二个位置
            mouse.move(targetX2, targetY2);
            mouse.click(targetX2, targetY2);
            // 点击第三个位置
            mouse.move(targetX3, targetY3);
            mouse.click(targetX3, targetY3);
            // 点击第四个位置
            mouse.move(targetX4, targetY4);
            mouse.click(targetX4, targetY4);
            //获取page1页面中class名为t-dialog__header-content标签内容为"信息确认"的元素
            ElementHandle confirmButton = page.querySelector(".t-dialog__header-content:text('信息确认')");
            //获取confirmButton的祖先元素class名为t-dialog t-dialog--default t-dialog__modal-default的div元素
            ElementHandle dialogElement = confirmButton.evaluateHandle("el => el.closest('.t-dialog.t-dialog--default.t-dialog__modal-default')").asElement();
            //获取dialogElement元素中class名为t-button__text标签内容为"确定"的元素
            ElementHandle confirmButtonElement = dialogElement.querySelector(".t-button__text:text('确定')");
            //点击确定按钮
            page.waitForTimeout(3000);
            confirmButtonElement.click();
            //等待系统处理完成
            page.waitForTimeout(15000);
            if (page.innerText("body").contains("提示")) {
                img.add(screenView(page, map.get("creditCode")));//截图存放在img中
                result.put("tipImg", JSONUtil.toJsonStr(img));
                result.put("status", "error");
                result.put("msg", "查看提示");
                return result;
            }
            if (page.innerText("body").contains("申报比对不通过")) {
                img.add(screenView(page, map.get("creditCode")));//截图存放在img中
                result.put("tipImg", JSONUtil.toJsonStr(img));
                result.put("status", "error");
                result.put("msg", "申报比对不通过");
                return result;
            }
            img.add(screenView(page, map.get("creditCode")));
            result.put("tipImg", JSONUtil.toJsonStr(img));
            result.put("status", "success");
            result.put("msg", "操作成功");
            return result;

        } catch (Exception e) {
            // 处理异常情况
            result.put("status", "error");
            result.put("code", "203");
            result.put("msg", "操作失败: " + e.getMessage());
            return result;
        }

    }

    /**
     * 居民企业（查账征收）企业所得税月（季）度申报
     *
     * @param page
     * @param map
     * @return
     */
    public Map<String, String> juminqiye(Page page, Map<String, String> map) {
        HashMap<String, String> result = new HashMap<>();
        List<String> img = new ArrayList<>();

        try {
            page.waitForTimeout(3000);
            if (page.innerText("body").contains("检测不通过")) {
                img.add(screenView(page, map.get("creditCode")));//截图存放在img中
                result.put("tipImg", JSONUtil.toJsonStr(img));
                result.put("status", "error");
                result.put("msg", "检测不通过");
                return result;
            }
            if ((page.innerText("body").contains("继续办理"))) {
                // 处理异常情况
                result.put("status", "error");
                result.put("code", "203");
                result.put("msg", "未报送财务报表");
                return result;
            } else {
                page.waitForTimeout(3000);
                if (page.innerText("body").contains("我要填表")) {
                    //找到内容为"我要填表"的按钮，进行点击
                    ElementHandle fillFormButton = page.waitForSelector("span:text(' 我要填表 ')");
                    fillFormButton.click();
                }
                page.waitForTimeout(3000);
                Frame frmSheet = page.frame("frmSheet");
                Locator input1 = frmSheet.locator("input[ng-model='sbxx.qccyrs2']");
                input1.waitFor();
                page.waitForTimeout(2000);
                String value1 = input1.inputValue();
                if ("0".equals(value1) || StringUtils.isEmpty(value1)) {
                    input1.fill("1");
                    input1.blur();
                }
                Locator input2 = frmSheet.locator("input[ng-model='sbxx.qmcyrs2']");
                input2.waitFor();
                String value2 = input2.inputValue();
                Long count = crmEmployeeService.getEmployeecount(Long.valueOf(map.get("accountSetsId")));
                if (count != null && count > 0) {
                    input2.fill(count.toString());
                } else {
                    if ("0".equals(value2) || StringUtils.isEmpty(value2)) {
                        input2.fill("1");
                    }
                }
                input2.blur();
                Locator input3 = frmSheet.locator("input[ng-model='sbxx.qczcze2']");
                input3.waitFor();
                String value3 = input3.inputValue();
                if ("0".equals(value3) || StringUtils.isEmpty(value3)) {
                    input3.fill("0.01");
                    input3.blur();
                }
                Locator input4 = frmSheet.locator("input[ng-model='sbxx.qmzcze2']");
                input4.waitFor();
                String value4 = input4.inputValue();
                if ("0".equals(value4) || StringUtils.isEmpty(value4)) {
                    input4.fill("0.01");
                    input4.blur();
                }
                //点击页面的提交申报按钮
                //找到内容为"我要填表"的按钮，进行点击
                ElementHandle submitFormButton = page.waitForSelector("span:text(' 提交申报 ')");
                submitFormButton.click();
                page.waitForTimeout(3000);

                Frame frame = page.frame("frmMain");
                if (page.innerText("body").contains("提示")) {

                    if (page.isVisible(".t-dialog__body.t-dialog__body__icon", new Page.IsVisibleOptions().setTimeout(3000))) {
                        img.add(screenView(page, map.get("creditCode")));//截图存放在img中
                        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("核实无误，提交申报")).click();
                        page.waitForTimeout(2000);
                    }
                }

                if (frame != null) {

                    if (frame.innerText("body").contains("你填写的申报数据中存在以下风险疑点，请您自查核实。风险疑点详情如下：")) {
                        img.add(screenView(page, map.get("creditCode")));//截图存放在img中
                    }
                    if (frame.innerText("body").contains("扫描通过，确定提交申报")) {
                        img.add(screenView(page, map.get("creditCode")));//截图存放在img中
                        frame.locator(".layui-layer-btn0").click();
                        page.waitForTimeout(2000);
                    } else {
                        Locator confirmButtonInFrame = frame.locator(".layui-layer-btn0");
                        confirmButtonInFrame.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
                        confirmButtonInFrame.click();
                    }
                    System.out.println("成功在iframe中点击确定按钮");
                } else {
                    System.out.println("未找到名为frmMain的iframe");
                }

                // 等待 Canvas 元素加载并获取句柄
                page.waitForSelector("canvas");
                ElementHandle canvas = page.querySelector("canvas");
                // 获取 Canvas 的边界框（坐标和宽高）
                BoundingBox box = canvas.boundingBox();

                // 提取 Canvas 的左上角坐标和尺寸
                double canvasX = box.x;
                double canvasY = box.y;
                int canvasWidth = 630; // 630（根据 HTML 定义）
                int canvasHeight = 80; // 80（根据 HTML 定义）
                // 计算绝对坐标（Canvas 左上角 + 相对偏移）
                double targetX1 = canvasX + 45 * 3 - 13;
                double targetY1 = canvasY + 80 / 4;
                double targetX2 = canvasX + 45 * 6 + 13;
                double targetY2 = canvasY + 80 * 3 / 5;
                double targetX3 = canvasX + 45 * 9 + 5;
                double targetY3 = canvasY + 80 / 2;
                double targetX4 = canvasX + 45 * 11 + 13;
                double targetY4 = canvasY + 80 * 3 / 5;
                // 获取鼠标控制器
                Mouse mouse = page.mouse();
                // 点击第一个位置
                mouse.move(targetX1, targetY1);
                mouse.click(targetX1, targetY1);
                // 点击第二个位置
                mouse.move(targetX2, targetY2);
                mouse.click(targetX2, targetY2);
                // 点击第三个位置
                mouse.move(targetX3, targetY3);
                mouse.click(targetX3, targetY3);
                // 点击第四个位置
                mouse.move(targetX4, targetY4);
                mouse.click(targetX4, targetY4);
                //获取page1页面中class名为t-dialog__header-content标签内容为"信息确认"的元素
                ElementHandle confirmButton = page.querySelector(".t-dialog__header-content:text('信息确认')");
                //获取confirmButton的祖先元素class名为t-dialog t-dialog--default t-dialog__modal-default的div元素
                ElementHandle dialogElement = confirmButton.evaluateHandle("el => el.closest('.t-dialog.t-dialog--default.t-dialog__modal-default')").asElement();
                //获取dialogElement元素中class名为t-button__text标签内容为"确定"的元素
                ElementHandle confirmButtonElement = dialogElement.querySelector(".t-button__text:text('确定')");
                //点击确定按钮
                confirmButtonElement.click();
                //等待系统处理完成
                page.waitForTimeout(8000);
                img.add(screenView(page, map.get("creditCode")));
                result.put("tipImg", JSONUtil.toJsonStr(img));
                result.put("status", "success");
                result.put("msg", "操作成功");
                return result;
            }
        } catch (Exception e) {
            // 处理异常情况
            result.put("status", "error");
            result.put("code", "203");
            result.put("msg", "操作失败: ");
            return result;
        }
    }

    public Map<String, String> caichanhexingwei(Page page, Map<String, String> map) {
        Map<String, String> result = new HashMap<>();
        List<String> img = new ArrayList<>();
        page.waitForTimeout(3000);
        if (page.innerText("body").contains("往期未申报列表")) {
            result.put("status", "error");
            result.put("code", "203");
            result.put("msg", "存在往期未申报列表");
            img.add(screenView(page, map.get("creditCode")));
            result.put("tipImg", JSONUtil.toJsonStr(img));
            return result;
        }

        try {
            //获取当前日期的上一季度
            int startMonth;
            LocalDate inputDate = LocalDate.now();
            int month = inputDate.getMonthValue();
            if (month >= 1 && month <= 3) {
                startMonth = 10;
            } else if (month >= 4 && month <= 6) {
                startMonth = 1;
            } else if (month >= 7 && month <= 9) {
                startMonth = 4;
            } else {
                startMonth = 7;
            }
            int year = inputDate.getYear();
            if (startMonth > 9) {
                year -= 1;
            }


            LocalDate startQuarter = LocalDate.of(year, startMonth, 1);
            BigDecimal outInvoiceAmount = invoiceService.getOutInvoiceAmount(Long.valueOf(map.get("accountSetsId")), startQuarter);
            BigDecimal outInvoiceAmountJianZhu = invoiceService.getOutInvoiceAmountJianZhu(Long.valueOf(map.get("accountSetsId")), startQuarter);
            page.waitForTimeout(3000);
            // 等待父元素加载完成
            page.waitForSelector(".card-body",
                    new Page.WaitForSelectorOptions().setTimeout(30000));

            // 1. 首先悬停在父元素上以显示隐藏按钮
            Locator parentMask = page.locator(".card-body");
            parentMask.hover();
            page.waitForTimeout(2000);
            //page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("转换报表")).click();
            page.locator("text=/^税源采集$/").first().click(new Locator.ClickOptions().setForce(true));
            // 等待元素加载
            page.waitForSelector("#parent");

            // 修改 inset 属性 - 格式为 "top right bottom left"
            page.locator("#parent").evaluate("element => {"
                    + "element.style.inset = '500px auto auto 200px';"  // 新位置: top=100px, left=200px
                    + "}");
            // 获取税目
            ElementHandle table = page.querySelector(".t-table--layout-fixed");
            if (table != null && !page.innerText("body").contains("暂无数据")) {
                // 获取表格的行
                List<ElementHandle> bodyRows = table.querySelectorAll("tbody tr");

                if (bodyRows.size() == 1 && "买卖合同".equals(bodyRows.get(0).querySelectorAll("td").get(1).innerText())) {
                    List<ElementHandle> cells = bodyRows.get(0).querySelectorAll("td");
                    String operationName = cells.get(cells.size() - 1).innerText();
                    yinHuaShuiReport(page, 1, operationName, map, outInvoiceAmount.subtract(outInvoiceAmountJianZhu));
                } else {
                    result.put("status", "error");
                    result.put("msg", "印花税过于复杂，程序无法自动处理，转人工处理");
                    img.add(screenView(page, map.get("creditCode")));
                    result.put("tipImg", JSONUtil.toJsonStr(img));
                    return result;
                }
            }

            page.waitForTimeout(4000);
            page.getByText("提交").nth(0).click();
            page.waitForTimeout(5000);
            if (page.innerText("body").contains("确定")) {
                page.getByText("确定").click();
            }
            //点击页面的提交申报按钮
            //找到内容为"我要填表"的按钮，进行点击
            ElementHandle submitFormButton = page.waitForSelector("span:text(' 提交申报 ')");
            submitFormButton.click();
            page.waitForTimeout(3000);
            // 通过frame名称定位iframe
            Frame frame = page.frame("frmMain");
            if (frame != null) {
                Locator confirmButtonInFrame = frame.locator(".layui-layer-btn0");
                confirmButtonInFrame.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
                confirmButtonInFrame.click();
                System.out.println("成功在iframe中点击确定按钮");
            } else {
                System.out.println("未找到名为frmMain的iframe");
            }

            // 等待 Canvas 元素加载并获取句柄
            page.waitForSelector("canvas");
            ElementHandle canvas = page.querySelector("canvas");
            // 获取 Canvas 的边界框（坐标和宽高）
            BoundingBox box = canvas.boundingBox();

            // 提取 Canvas 的左上角坐标和尺寸
            double canvasX = box.x;
            double canvasY = box.y;
            int canvasWidth = 630; // 630（根据 HTML 定义）
            int canvasHeight = 80; // 80（根据 HTML 定义）
            // 计算绝对坐标（Canvas 左上角 + 相对偏移）
            double targetX1 = canvasX + 45 * 3 - 13;
            double targetY1 = canvasY + 80 / 4;
            double targetX2 = canvasX + 45 * 6 + 13;
            double targetY2 = canvasY + 80 * 3 / 5;
            double targetX3 = canvasX + 45 * 9 + 5;
            double targetY3 = canvasY + 80 / 2;
            double targetX4 = canvasX + 45 * 11 + 13;
            double targetY4 = canvasY + 80 * 3 / 5;
            // 获取鼠标控制器
            Mouse mouse = page.mouse();
            // 点击第一个位置
            mouse.move(targetX1, targetY1);
            mouse.click(targetX1, targetY1);
            // 点击第二个位置
            mouse.move(targetX2, targetY2);
            mouse.click(targetX2, targetY2);
            // 点击第三个位置
            mouse.move(targetX3, targetY3);
            mouse.click(targetX3, targetY3);
            // 点击第四个位置
            mouse.move(targetX4, targetY4);
            mouse.click(targetX4, targetY4);
            //获取page1页面中class名为t-dialog__header-content标签内容为"信息确认"的元素
            ElementHandle confirmButton = page.querySelector(".t-dialog__header-content:text('信息确认')");
            //获取confirmButton的祖先元素class名为t-dialog t-dialog--default t-dialog__modal-default的div元素
            ElementHandle dialogElement = confirmButton.evaluateHandle("el => el.closest('.t-dialog.t-dialog--default.t-dialog__modal-default')").asElement();
            //获取dialogElement元素中class名为t-button__text标签内容为"确定"的元素
            ElementHandle confirmButtonElement = dialogElement.querySelector(".t-button__text:text('确定')");
            //点击确定按钮
            confirmButtonElement.click();
            //等待系统处理完成
            page.waitForTimeout(8000);
            result.put("status", "success");
            result.put("msg", "印花税信息填写成功");
            img.add(screenView(page, map.get("creditCode")));
            result.put("tipImg", JSONUtil.toJsonStr(img));
            return result;
        } catch (Exception e) {
            // 处理异常情况
            result.put("status", "error");
            result.put("code", "203");
            result.put("msg", "操作失败: " + e.getMessage());
            log.error("印花税信息填写失败", e);
            return result;
        }
    }
// 一键报税end

    /**
     * 截图并获取文件路径
     *
     * @param page
     * @return
     */
    public String screenView(Page page, String creditCode) {
        Path imgPath = Paths.get(screenImgPath);
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String filename = "viewport_" + creditCode + "_" + timestamp + ".png";
        Path filePath = imgPath.resolve(filename);

        page.screenshot(new Page.ScreenshotOptions()
                .setPath(filePath)
                .setType(ScreenshotType.PNG) // 图片格式
        );
        return filename;

    }

    public String screenViewFull(Page page, String creditCode) {
        Path imgPath = Paths.get(screenImgPath);
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String filename = "viewport_" + creditCode + "_" + timestamp + ".png";
        Path filePath = imgPath.resolve(filename);

        page.screenshot(new Page.ScreenshotOptions()
                .setPath(filePath)
                .setFullPage(true)
                .setType(ScreenshotType.PNG) // 图片格式
        );
        return filename;

    }

    public void yinHuaShuiReport(Page page, int index, String operationName, Map<String, String> map, BigDecimal outInvoiceAmount) {
        page.locator("text=/^" + operationName + "$/").nth(index - 1).click(new Locator.ClickOptions().setForce(true));

        page.waitForTimeout(3000);

        // 等待表单加载完成
        page.waitForSelector(".t-form", new Page.WaitForSelectorOptions().setTimeout(30000));

        // 填写计税金额 - 使用 first() 选择第一个可见的输入框
        String taxAmount = map.getOrDefault("taxAmount", String.valueOf(outInvoiceAmount));
        page.locator(".t-input-number.t-size-m.t-input-number--normal.zr-input-money input[placeholder='请输入'][type='text']").first().fill(taxAmount);

        // 填写应税凭证数量 - 查找数量输入框
        String certificateQuantity = map.getOrDefault("certificateQuantity", "1");
        page.locator(".gov-input-money input[placeholder='请输入']").fill(certificateQuantity);

        // 等待一下让输入生效
        page.waitForTimeout(2000);
        page.getByText("保存").click();
        page.waitForTimeout(2000);
        if (page.innerText("body").contains("确定")) {
            page.getByText("确定").click();
        }
        page.waitForTimeout(2000);
        if (page.innerText("body").contains("确定")) {
            page.getByText("确定").click();
        }
    }

    /**
     * @param page
     * @param creditCode
     * @return
     */
    public String payImg(Page page, String creditCode) {
        Path imgPath = Paths.get(screenImgPath);
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String filename = "payment" + creditCode + "_" + timestamp + ".png";
        Path filePath = imgPath.resolve(filename);
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(filePath)
                .setType(ScreenshotType.PNG) // 图片格式
        );
        return filename;
    }

    /**
     * 读取excel信息，返回勾选票所包含的日期 勾选
     */
    public Set<Date> checkInvoiceReadExcelFile(String filePath) {
        try {
            FileInputStream file = new FileInputStream(filePath);
            Workbook workbook = WorkbookFactory.create(file);
            // 2. 获取工作表（根据名称或索引）
            Sheet sheet = workbook.getSheetAt(0);
            Set<Date> dateRangeList = new HashSet<>();
            // 4. 遍历行（从第4行开始，跳过表头）
            for (int i = 3; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                // 5. 获取H列（索引7）的单元格
                Cell dateCell = row.getCell(7);
                if (dateCell == null) continue;

                // 6. 获取日期值
                Date date;
                try {
                    if (dateCell.getCellType() == CellType.STRING) {
                        // 如果日期是字符串格式，尝试解析
                        String dateStr = dateCell.getStringCellValue().trim();
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM");
                        date = sdf.parse(dateStr);
                    } else {
                        continue; // 跳过非日期单元格
                    }
                } catch (Exception e) {
                    continue; // 跳过解析失败的单元格
                }
                dateRangeList.add(date);
            }
            // 9. 关闭资源
            workbook.close();
            file.close();
            return dateRangeList;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取传入日期所在月份的第一天
     *
     * @param date 传入的日期
     * @return 该月第一天的日期字符串，格式：yyyy-MM-dd
     */
    private static String getFirstDayOfMonth(Date date) {
        java.time.LocalDate localDate = date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        java.time.LocalDate firstDay = localDate.withDayOfMonth(1);
        return firstDay.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    /**
     * 获取传入日期所在月份的最后一天
     *
     * @param date 传入的日期
     * @return 该月最后一天的日期字符串，格式：yyyy-MM-dd
     */
    private static String getLastDayOfMonth(Date date) {
        java.time.LocalDate localDate = date.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalDate();
        java.time.LocalDate lastDay = localDate.withDayOfMonth(localDate.lengthOfMonth());
        return lastDay.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }
    /**
     * 读取excel信息，导入数据库中 未勾选
     */
    public static Set<Date> uncheckInvoiceReadExcelFile(String filePath) {
        try {
            FileInputStream file = new FileInputStream(filePath);
            Workbook workbook = WorkbookFactory.create(file);
            // 2. 获取工作表（根据名称或索引）
            Sheet sheet = workbook.getSheetAt(0);
            Set<Date> dateRangeList = new HashSet<>();

            // 4. 遍历行（从第2行开始，跳过表头）
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                // 5. 获取H列（索引7）的单元格
                Cell dateCell = row.getCell(5);
                if (dateCell == null) continue;

                // 6. 获取日期值
                Date date;
                try {
                    if (dateCell.getCellType() == CellType.STRING) {
                        // 如果日期是字符串格式，尝试解析
                        String dateStr = dateCell.getStringCellValue().trim();
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM");
                        date = sdf.parse(dateStr);
                    } else {
                        continue; // 跳过非日期单元格
                    }
                } catch (Exception e) {
                    continue; // 跳过解析失败的单元格
                }
                // 8. 添加到结果
                dateRangeList.add(date);
            }
            // 9. 关闭资源
            workbook.close();
            file.close();
            return dateRangeList;
        } catch (Exception e) {
            return null;
        }
    }
    /**
     * 获取这个月最后一天的日期
     *
     * @return 这个月最后一天的日期字符串，格式：yyyy-MM-dd
     */
    private static String getCurrentMonthLastDay() {
        java.time.LocalDate now = java.time.LocalDate.now();
        java.time.LocalDate currentMonthLast = now.withDayOfMonth(now.lengthOfMonth());
        return currentMonthLast.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

}
