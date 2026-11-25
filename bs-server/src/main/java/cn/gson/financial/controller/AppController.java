package cn.gson.financial.controller;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.controller.JsonResult;
import cn.gson.financial.kernel.model.dto.AccountSetsTemplateDto;
import cn.gson.financial.kernel.model.dto.CurrencyDto;
import cn.gson.financial.kernel.model.entity.RenewalCode;
import cn.gson.financial.kernel.model.entity.Report;
import cn.gson.financial.kernel.model.entity.User;
import cn.gson.financial.kernel.model.entity.UserImageStorageConfig;
import cn.gson.financial.kernel.model.enums.AccountStandard;
import cn.gson.financial.kernel.model.enums.VatType;
import cn.gson.financial.kernel.model.vo.UserVo;
import java.util.Objects;
import cn.gson.financial.kernel.service.*;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.sms4j.api.entity.SmsResponse;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static cn.gson.financial.kernel.common.Constants.S_AUTHORITIES_KEY;
import static cn.gson.financial.kernel.common.Constants.S_USER_KEY;

/**
 *
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.controller</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 *
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class AppController {

    private final UserService userService;

    private final SmsService smsService;

    private final AppConfigService appConfigService;

    private final Environment environment;

    private final RenewalCodeService renewalCodeService;

    private final CurrencyService currencyService;

    private final ReportService reportService;

    private final AccountSetsTemplateService accountSetsTemplateService;

    private final BuildProperties buildProperties;

    private final UserImageStorageConfigService userImageStorageConfigService;

    @RequestMapping("/")
    public JsonResult index() {
        return JsonResult.successful().setMsg("青艺科技");
    }

    @GetMapping("/reset/ast/current")
    public JsonResult resetAstCurrent() {
        SaSession session = StpUtil.getSession();
        UserVo user = session.getModel(S_USER_KEY, UserVo.class);
        return JsonResult.successful(userService.resetAstCurrent(user));
    }

    /**
     * 检查登录状获取态和并返回登录信息
     *
     * @return
     */

    @GetMapping("/init")
    public JsonResult init() {
        try {
            if (StpUtil.isLogin()) {
                SaSession session = StpUtil.getSession();
                UserVo user = session.getModel(S_USER_KEY, UserVo.class);
                UserVo userVo = userService.getUserVo(user.getId());
                userService.updateLastTime(user.getId());
                List<Report> reportList = new ArrayList<>();
                Map<Long, CurrencyDto> currencyMap = new HashMap<>();
                if (userVo.getAccountSets() != null) {
                    currencyMap = currencyService.list(userVo.getAccountSets().getId())
                            .stream().collect(Collectors.toMap(CurrencyDto::getId, currency -> currency));
                    //设置本位币
                    Optional<CurrencyDto> optional = currencyMap.values().stream().filter(c -> BooleanUtil.isTrue(c.getLocalCurrency())).findFirst();
                    optional.ifPresent(c -> {
                        userVo.setLocalCurrencyId(c.getId());
                    });

                    reportList = reportService.selectList(userVo.getAccountSets().getId());
                }
                session.set(S_USER_KEY, userVo);
                if (userVo.getRoleInfo() != null) {
                    session.set(S_AUTHORITIES_KEY, userVo.getRoleInfo());
                }
                //程序构建时间
                LocalDateTime buildTime = LocalDateTime.ofInstant(buildProperties.getTime(), ZoneId.systemDefault());
                return JsonResult.successful()
                        .data("prod", environment.acceptsProfiles(Profiles.of("prod")))
                        .data("webConfig", appConfigService.load())
                        .data(S_USER_KEY, userVo)
                        .data("reportList", reportList)
                        .data("buildTime", buildTime)
                        .data("currency", currencyMap);
            }
        } catch (Exception e) {
            log.error("初始化错误～", e);
            StpUtil.logout();
        }

        return JsonResult.failure()
                .data("prod", environment.acceptsProfiles(Profiles.of("prod")))
                .data("webConfig", appConfigService.load());
    }

    /**
     * 登录
     *
     * @param mobile
     * @param password
     * @return
     */

    @PostMapping("/login")
    public JsonResult login(String mobile, String password) {
        UserVo user = userService.login(mobile, password);
        String loginId = user.getId() + ":" + mobile;
        SaSession oldSession = StpUtil.getSessionByLoginId(loginId);
        if (oldSession != null) {
            //退出原来的session
            StpUtil.logout(loginId);
        }

        StpUtil.login(loginId);
        SaSession session = StpUtil.getSession();
        session.set(S_USER_KEY, user);
        if (user.getRoleInfo() != null) {
            session.set(S_AUTHORITIES_KEY, user.getRoleInfo());
        }
        return JsonResult.successful();
    }

    /**
     * 登出
     *
     * @return
     */
    @GetMapping("/logout")
    public JsonResult logout(HttpSession session) {
        StpUtil.logout();
        session.invalidate();
        return JsonResult.successful();
    }

    /**
     * 发送短信验证码
     *
     * @param mobile
     * @return
     */

    @GetMapping("/sendMsg/{mobile}")
    public JsonResult sendMsg(@PathVariable String mobile, HttpSession session) {
        String numeric = RandomUtil.randomNumbers(4);
        this.sendSmsCode(mobile, numeric);
        session.setAttribute(mobile, numeric);
        return JsonResult.successful();
    }

    /**
     * 续期码校验
     *
     * @param code
     * @return
     */
    @GetMapping("/renewal/valid")
    public JsonResult renewalValid(@RequestParam String code, Long astId, @SessionUser UserVo user) {
        RenewalCode renewalCode = renewalCodeService.valid(code, user, astId);
        UserVo userVo = userService.getUserVo(user.getId());
        StpUtil.getSession().set(S_USER_KEY, userVo);
        return JsonResult.successful(renewalCode);
    }


    /**
     * 注册发送短信验证码
     *
     * @param mobile
     * @return
     */

    @GetMapping("/regMsg/{mobile}")
    public JsonResult regMsg(@PathVariable String mobile, HttpSession session) {
        Assert.isTrue(appConfigService.load().getRegister(), "系统禁止注册~");

        if (this.userService.countByMobile(mobile) > 0) {
            return JsonResult.failure("此手机号已被注册，请直接用这个手机号登录或者更换手机号注册！");
        }
        String numeric = RandomUtil.randomNumbers(4);
        this.sendSmsCode(mobile, numeric);
        session.setAttribute(mobile, numeric);
        return JsonResult.successful();
    }

    /**
     * 注册
     *
     * @param mobile
     * @return
     */

    @PostMapping("/register")
    public JsonResult register(String mobile, String code, String password, HttpSession session) {
        cn.gson.financial.kernel.model.entity.AppConfig config = appConfigService.load();
        Assert.isTrue(config.getRegister(), "系统禁止注册~");

        if (this.userService.countByMobile(mobile) > 0) {
            return JsonResult.failure("此手机号已被注册，请直接用这个手机号登录或者更换手机号注册！");
        }

//        if (!code.equals(session.getAttribute(mobile))) {
//            return JsonResult.failure("验证码错误！");
//        }

        session.removeAttribute(mobile);

        User user = new User();
        user.setMobile(mobile);
        user.setAccountSetsNum(config.getAccountSetsNum());
        user.setUserNum(config.getUserNum());
        user.setRealName(mobile);
        if (StrUtil.isNotEmpty(password)) {
            user.setPassword(DigestUtil.bcrypt(password));
        } else {
            user.setPassword(DigestUtil.bcrypt(mobile.substring(5)));
        }
        this.userService.save(user);

        StpUtil.login(mobile);
        StpUtil.getSession().set(S_USER_KEY, this.userService.getUserVo(user.getId()));
        return JsonResult.successful();
    }

    /**
     * 更新个人设置
     *
     * @param realName、email
     * @return
     */
    @PostMapping("/updateUser")
    public JsonResult updateUser(String realName, String email, @SessionUser UserVo user) {
        user.setRealName(realName);
        user.setEmail(email);
        userService.save(user);
        StpUtil.getSession().set(S_USER_KEY, user);
        return JsonResult.successful();
    }

    /**
     * 重置密码
     *
     * @param newPassword
     * @param repeatPassword
     * @param mobile
     * @param code
     * @param session
     * @return
     */

    @PostMapping("/resetPassword")
    public JsonResult resetPassword(@RequestParam String newPassword,
                                    @RequestParam String repeatPassword,
                                    @RequestParam String mobile,
                                    @RequestParam String code, HttpSession session) {
        JsonResult result = JsonResult.instance(true);

        if (!code.equals(session.getAttribute(mobile))) {
            result = JsonResult.failure("验证码错误！");
        } else if (!newPassword.equals(repeatPassword)) {
            result = JsonResult.failure("密码设置错误！");
        }

        if (result.isSuccess()) {
            User user = userService.getByMobile(mobile);
            if (user != null) {
                user.setPassword(DigestUtil.bcrypt(newPassword));
                userService.save(user);

                session.removeAttribute(mobile);
            } else {
                result = JsonResult.failure("用户不存在！");
            }
        }

        return result;
    }

    /**
     * 修改密码
     *
     * @param original、newPassword
     * @return
     */
    @PostMapping("/updatePwd")
    public JsonResult updatePwd(String original, String newPassword, @SessionUser UserVo user, HttpSession session) {
        //核对旧密码
        User u = userService.getUserVo(user.getId());
        if (DigestUtil.sha256Hex(original).equals(u.getPassword()) || DigestUtil.bcryptCheck(original, u.getPassword())) {
            //更新新密码
            user.setPassword(DigestUtil.bcrypt(newPassword));
            userService.save(user);
            return this.logout(session);
        }
        return JsonResult.failure("原密码错误!");
    }

    /**
     * 修改手机号码
     *
     * @param verificationCode，mobile
     * @return
     */
    @PostMapping("/changePhoneNumber")
    public JsonResult changePhoneNumber(String verificationCode, String mobile, @SessionUser UserVo user, HttpSession session) {
        //Session里获取手机号对应验证码进行比对
        if (!verificationCode.equals(session.getAttribute(mobile))) {
            return JsonResult.failure("验证码错误!");
        }
        if (userService.countByMobile(mobile) > 0) {
            return JsonResult.failure("手机号已关联其他用户~");
        }
        //更新手机号
        user.setMobile(mobile);
        userService.save(user);
        StpUtil.getSession().set(S_USER_KEY, user);
        return JsonResult.successful();
    }

    /**
     * 变更默认账套
     *
     * @param accountSetsId
     * @param user
     * @return
     */
    @GetMapping("/changeAccountSets")
    public JsonResult changeAccountSets(Long accountSetsId, @SessionUser UserVo user) {
        if (user.getAccountSetsList().stream().filter(Objects::nonNull).anyMatch(accountSets -> accountSets.getId().equals(accountSetsId))) {
            userService.changeAccountSets(accountSetsId, user.getId());
            UserVo userVo = userService.getUserVo(user.getId());
            StpUtil.getSession()
                    .set(S_USER_KEY, userVo)
                    .set(S_AUTHORITIES_KEY, userVo.getRoleInfo());
        }
        return JsonResult.successful();
    }




    private void sendSmsCode(@PathVariable String mobile, String numeric) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            SmsResponse smsResponse = smsService.sendCaptcha(mobile, numeric);
            Assert.isTrue(smsResponse.isSuccess(), JSONUtil.toJsonStr(smsResponse.getData()));
        } else {
            log.info("\n 验证码:{}", numeric);
        }
    }

    /**
     * 可以创建账套的模板列表
     *
     * @return
     */
    @GetMapping("ast/list")
    public JsonResult astList() {
        List<AccountSetsTemplateDto> accountSetsTemplateDtos = accountSetsTemplateService.enableList();
        return JsonResult.successful()
                .data("accountStandard", AccountStandard.dict())
                .data("vatType", VatType.dict())
                .data("astList", accountSetsTemplateDtos);
    }

    /**
     * 查询用户图片存储配置列表
     *
     * @param user 当前登录用户
     * @return
     */
    @GetMapping("/userImageStorageConfig/list")
    public JsonResult listUserImageStorageConfig(@SessionUser UserVo user) {
        List<UserImageStorageConfig> configs = userImageStorageConfigService.listByUserId(user.getId());
        return JsonResult.successful(configs);
    }

    /**
     * 根据ID查询用户图片存储配置
     *
     * @param id   配置ID
     * @param user 当前登录用户
     * @return
     */
    @GetMapping("/userImageStorageConfig/get")
    public JsonResult getUserImageStorageConfig(Long id, @SessionUser UserVo user) {
        return userImageStorageConfigService.findById(id)
                .filter(config -> config.getUserId().equals(user.getId()))
                .map(config -> JsonResult.successful(config))
                .orElse(JsonResult.failure("配置不存在或无权限访问"));
    }

    /**
     * 保存或更新用户图片存储配置
     *
     * 使用表单提交（application/x-www-form-urlencoded），不使用 JSON。
     *
     * @param config 配置信息
     * @param user   当前登录用户
     * @return
     */
    @PostMapping("/userImageStorageConfig/save")
    public JsonResult saveUserImageStorageConfig(UserImageStorageConfig config, @SessionUser UserVo user) {
         Assert.isTrue(StrUtil.isNotBlank(config.getStorageRoot()), "存储路径不能为空");
         // 存储类型必填，当前前端固定传 1-本地图片；
         // 如果未来新增类型，只需要调整前端可选项和这里的校验逻辑即可。
         Assert.isTrue(config.getStorageType() != null, "存储类型不能为空");

        // 确保用户ID为当前登录用户
        config.setUserId(user.getId());
        
        UserImageStorageConfig saved = userImageStorageConfigService.save(config);
        return JsonResult.successful(saved);
    }

    /**
     * 删除用户图片存储配置
     *
     * @param id   配置ID
     * @param user 当前登录用户
     * @return
     */
    @PostMapping("/userImageStorageConfig/delete")
    public JsonResult deleteUserImageStorageConfig(Long id, @SessionUser UserVo user) {
        return userImageStorageConfigService.findById(id)
                .filter(config -> config.getUserId().equals(user.getId()))
                .map(config -> {
                    userImageStorageConfigService.delete(id);
                    return JsonResult.successful();
                })
                .orElse(JsonResult.failure("配置不存在或无权限删除"));
    }
}
