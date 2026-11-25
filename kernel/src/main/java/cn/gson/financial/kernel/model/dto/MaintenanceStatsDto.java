package cn.gson.financial.kernel.model.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 维护统计信息DTO
 * 用于返回需要维护的账套统计信息
 */
@Data
public class MaintenanceStatsDto implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 个税密码为空的账套数量
     */
    private Long emptyPasswordCount;

    /**
     * 员工为零的账套数量
     */
    private Long emptyEmployeeCount;

    /**
     * 未结转到当前月的账套数量
     */
    private Long unCheckoutCount;
}

