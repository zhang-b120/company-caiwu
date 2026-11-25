package cn.gson.financial.resolver;

import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.annotation.SessionAccountSet;
import cn.gson.financial.kernel.annotation.SessionAccountSetId;
import cn.gson.financial.kernel.annotation.SessionUser;
import cn.gson.financial.kernel.config.SessionHandlerMethodArgumentResolver;
import cn.gson.financial.kernel.model.entity.AccountSets;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.UserService;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.ModelAndViewContainer;

import static cn.gson.financial.kernel.common.Constants.S_USER_KEY;

/**
 * 
 * <p><b>Copyright © 2010-2018开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.flyemu.resolver</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年3月09日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Component
public class SessionUserHandlerMethodArgumentResolver implements SessionHandlerMethodArgumentResolver {

    @Resource
    @Lazy
    private UserService userService;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return ((parameter.getParameterType().isAssignableFrom(UserVo.class)) && parameter.hasParameterAnnotation(SessionUser.class))
               || ((parameter.getParameterType().isAssignableFrom(Long.class)) && parameter.hasParameterAnnotation(SessionAccountSetId.class))
               || ((parameter.getParameterType().isAssignableFrom(AccountSets.class)) && parameter.hasParameterAnnotation(SessionAccountSet.class));
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer, NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
        UserVo user = StpUtil.getSession().getModel(S_USER_KEY, UserVo.class);

        if (user != null) {
            String accountSetId = webRequest.getHeader("accountSetId");
            if (StrUtil.isNotEmpty(accountSetId)) {
                AccountSets accountSets = userService.loadByAstId(Long.valueOf(accountSetId), user.getId());
                Assert.notNull(accountSets, "账套错误,请确认请求Header中accountSetId传递正确~");
                user.setAccountSets(accountSets);
            }
            if (parameter.hasParameterAnnotation(SessionUser.class)) {
                return user;
            } else if (parameter.hasParameterAnnotation(SessionAccountSetId.class) || parameter.hasParameterAnnotation(SessionAccountSet.class)) {
                if (parameter.hasParameterAnnotation(SessionAccountSet.class)) {
                    return user.getAccountSets();
                }
                return user.getAccountSets() != null ? user.getAccountSets().getId() : null;
            }
        }
        return null;
    }
}
