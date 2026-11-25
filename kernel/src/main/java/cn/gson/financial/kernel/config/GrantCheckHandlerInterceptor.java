package cn.gson.financial.kernel.config;

import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.annotation.DueCheck;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.vo.UserVo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.LocalDate;

import static cn.gson.financial.kernel.common.Constants.S_USER_KEY;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.resolver</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年12月05日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Component
public class GrantCheckHandlerInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HandlerMethod method = (HandlerMethod) handler;
        if (method.hasMethodAnnotation(DueCheck.class)) {
            if (StpUtil.isLogin()) {
                UserVo user = StpUtil.getSession().getModel(S_USER_KEY, UserVo.class);
                if (user.getAccountSets() != null && user.getAccountSets().getEffectiveDate() != null) {
                    if (user.getAccountSets().getEffectiveDate().isBefore(LocalDate.now())) {
                        throw new ServiceException("您的账套已过期，请续期~");
                    }
                }
            }
        }
        return true;
    }
}
