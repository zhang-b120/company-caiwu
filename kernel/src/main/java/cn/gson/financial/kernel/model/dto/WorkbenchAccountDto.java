package cn.gson.financial.kernel.model.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 工作台账套DTO，扩展AccountSetsDto，添加进度和登录状态信息
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class WorkbenchAccountDto extends AccountSetsDto implements Serializable {
    
    /**
     * 发票下载进度（0-100）
     */
    private Integer invoiceProgress = 0;
    
    /**
     * 凭证制作进度（0-100）
     */
    private Integer voucherProgress = 0;
    
    /**
     * 税局登录状态：ONLINE-已登录, OFFLINE-未登录
     */
    private String taxLoginStatus = "OFFLINE";
    
    /**
     * 1小时内切换次数
     */
    private Integer taxSwitchCount = 0;
    
    /**
     * 切换上限（每小时）
     */
    private Integer taxSwitchLimit = 5;
    
    /**
     * 税号（用于显示）
     */
    private String taxNumber;
}
