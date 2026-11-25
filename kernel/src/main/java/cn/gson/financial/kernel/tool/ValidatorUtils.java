package cn.gson.financial.kernel.tool;

import cn.gson.financial.kernel.exception.ServiceException;
import cn.hutool.core.collection.CollUtil;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.groups.Default;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 
 * <p><b>Copyright © 2010-2021开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.hr.kernel.common</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2021年08月17日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
public class ValidatorUtils {
    private static final Validator validate = Validation.buildDefaultValidatorFactory().getValidator();

    /**
     * 校验所有字段并返回不合法字段
     *
     * @param domain
     * @return
     */
    public static void validate(@Valid Object domain) {
        Set<ConstraintViolation<Object>> validateResult = validate.validate(domain, Default.class);
        if (!validateResult.isEmpty()) {
            List<String> errors = new ArrayList<>();
            for (ConstraintViolation<Object> cv : validateResult) {
                errors.add(cv.getMessage());
                log.warn("{}.{}:{}", cv.getRootBean().getClass().getName(), cv.getPropertyPath(), cv.getMessage());
            }
            throw new ServiceException(CollUtil.join(errors, ","));
        }
    }
}
