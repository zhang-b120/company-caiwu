package cn.gson.financial.kernel.controller;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.stp.StpUtil;
import cn.gson.financial.kernel.exception.ExperienceHasExpired;
import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.vo.UserVo;
import io.sentry.Sentry;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.NoHandlerFoundException;

import static cn.gson.financial.kernel.common.Constants.S_USER_KEY;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.base</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年08月07日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@ControllerAdvice
@Slf4j
public class ErrorControllerAdvice {

    /**
     * 参数校验异常统一处理
     * 返回状态码:200
     */
    @ResponseStatus(HttpStatus.OK)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseBody
    public JsonResult handleMethodArgumentNotValidException(MethodArgumentNotValidException e) {
        return JsonResult.failure("数据校验失败", 5001).setData(e.getBindingResult().getAllErrors().stream()
                .map(DefaultMessageSourceResolvable::getDefaultMessage)
                .toList());
    }

    /**
     * 服务异常
     *
     * @param throwable
     * @return
     */
    @ExceptionHandler({ServiceException.class})
    @ResponseStatus(HttpStatus.EXPECTATION_FAILED)
    @ResponseBody
    public JsonResult exception(final ServiceException throwable, HttpServletRequest request) {
        this.captureException(throwable, request);
        return JsonResult.failure(throwable.getMessage(), throwable.getCode());
    }

    /**
     * 体验到期
     *
     * @param throwable
     * @return
     */
    @ExceptionHandler({ExperienceHasExpired.class})
    @ResponseStatus(HttpStatus.EXPECTATION_FAILED)
    @ResponseBody
    public JsonResult exception(final ExperienceHasExpired throwable) {
        return JsonResult.failure(throwable.getMessage());
    }

    /**
     * 服务异常
     *
     * @param throwable
     * @return
     */
    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    @ResponseStatus(HttpStatus.OK)
    @ResponseBody
    public JsonResult iaException(final RuntimeException throwable, HttpServletRequest request) {
        this.captureException(throwable, request);
        return JsonResult.failure(throwable.getMessage());
    }


    @ExceptionHandler(NotLoginException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    @ResponseBody
    public JsonResult saTokenNotLoginException(final NotLoginException throwable) {
        log.info("{},{},{}", throwable.getLoginType(), throwable.getType(), throwable.getMessage());
        return JsonResult.failure(throwable.getMessage());
    }

    @ExceptionHandler(NotPermissionException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    @ResponseBody
    public JsonResult saTokenNotPermissionException(final NotPermissionException throwable) {
        log.info("{},{}", throwable.getLoginType(), throwable.getMessage());
        return JsonResult.failure(throwable.getMessage());
    }

    /**
     * 404 — 路径不存在（下游联调时返清晰提示，不走 Sentry）
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    @ResponseBody
    public JsonResult handleNotFound(NoHandlerFoundException e) {
        log.warn("[404] path 不存在: {} {}", e.getHttpMethod(), e.getRequestURL());
        return JsonResult.failure("path 不存在: " + e.getRequestURL(), 4040);
    }

    /**
     * 405 — 请求方法不被允许（如用 GET 调 POST 接口）
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    @ResponseBody
    public JsonResult handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.warn("[405] method 不被允许: {}, supported: {}", e.getMethod(), e.getSupportedHttpMethods());
        return JsonResult.failure("method 不被允许: " + e.getMethod() + "，支持: " + e.getSupportedHttpMethods(), 4050);
    }

    /**
     * 400 — 请求体格式非法（JSON 解析失败，如缺字段或类型错误）
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ResponseBody
    public JsonResult handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.warn("[400] 入参格式错误: {}", e.getMessage());
        return JsonResult.failure("入参格式错误，请检查请求体 JSON", 4000);
    }

    /**
     * SSE 连接超时属于正常生命周期，前端 EventSource 会自动重连，无需写响应体。
     * 客户端主动断开（ClientAbortException 包装为 AsyncRequestNotUsableException）同理：
     * 响应已提交或连接已断，无法再写，直接忽略即可。
     */
    @ExceptionHandler({AsyncRequestTimeoutException.class, AsyncRequestNotUsableException.class})
    public void handleAsyncLifecycle() {
        // intentionally empty — normal connection lifecycle, not an application error
    }

    /**
     * 统一其他异常
     *
     * @param throwable
     * @return
     */
    @ExceptionHandler(Throwable.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    @ResponseBody
    public JsonResult exception(final Throwable throwable, HttpServletRequest request) {
        this.captureException(throwable, request);
        return JsonResult.failure("操作被禁止或发生错误！");
    }

    private void captureException(Throwable throwable, HttpServletRequest request) {
        log.error("发生错误的请求：{}", request.getServletPath());
        if (StpUtil.isLogin()) {
            UserVo user = StpUtil.getSession().getModel(S_USER_KEY, UserVo.class);
            if (user != null) {
                log.error("当前用户信息：{}", user.getMobile(), throwable);
                if (user.getAccountSets() != null) {
                    log.error("当前用户AccountSetsId:{}", user.getAccountSets().getId(), throwable);
                }
            } else {
                log.error("IllegalArgumentException warn", throwable);
            }
        } else {
            log.error("Throwable Error", throwable);
        }
        Sentry.captureException(new ServiceException(throwable, "发生错误的请求：{}", request.getServletPath()));
    }
}
