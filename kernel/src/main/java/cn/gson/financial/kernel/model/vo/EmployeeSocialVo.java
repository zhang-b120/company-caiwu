package cn.gson.financial.kernel.model.vo;

import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年12月28日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@AllArgsConstructor
public class EmployeeSocialVo {
    private Map<Long, Map<String, BigDecimal>> socialMap;
    private Map<Long, String> socialNameMap;

    /**
     * 获取员工社保字段信息
     *
     * @param empId
     * @return
     */
    public Map<String, BigDecimal> getEmpSocial(Long empId) {
        return socialMap.get(empId);
    }

    /**
     * 获取员工社保字段值
     *
     * @param empId
     * @return
     */
    public BigDecimal getEmpSocialVal(Long empId, String socialName) {
        Map<String, BigDecimal> map = socialMap.get(empId);
        return map == null ? BigDecimal.ZERO : map.getOrDefault(socialName, BigDecimal.ZERO);
    }

    /**
     * 获取社保字段名称
     *
     * @param socialId
     * @return
     */
    public String getSocialName(Long socialId) {
        return socialNameMap.get(socialId);
    }
}
