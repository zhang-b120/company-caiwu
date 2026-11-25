package cn.gson.financial.kernel.model.dto;

import cn.hutool.core.bean.BeanUtil;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import org.sagacity.sqltoy.config.annotation.Id;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Map;

//任务dto
@Getter
@Setter
public class AllTaskDto implements Serializable {
    /**
     * 主键id
     */
    private Long taskId;
    /*
     * 任务项id
     */
    private Long taskItemId;
    /**
     * 账套名称
     */
    private String companyName;
    /**
     * 账套id
     */
    private Long accountSetId;
    /**
     * 任务明细日志
     */
    private String taskItemLog;
    /**
     * 任务状态
     */
    private Integer taskItemStatus;
    /**
     * 任务类型
     */
    private String taskType;
    /**
     * 任务总数
     */
    private int taskAllCount;
    /**
     * 任务成功总数
     */
    private int taskSuccessCount;
    /**
     * 任务失败总数
     */
    private int taskFailCount;
    /**
     * 任务创建人
     */
    private String taskCreaterName;
    /**
     * 任务完成个数
     */
    private String taskUnCounts;
    /**
     * 任务时间
     */
    private String taskTime;

    //下载的信息
    private String taskParams;

    /**
     * 任务执行参数
     */
    private String params;

    /**、
     *下载的发票开始日期
     */
    private LocalDate invoiceStartDate;

    /**
     * 下载发票结束日期
     */
    private LocalDate invoiceEndDate;

    /**
     *下载发票的类型
     */
    private String invoiceType;


    public Map<String, Object> toMap() {
        return BeanUtil.beanToMap(this);
    }
}
