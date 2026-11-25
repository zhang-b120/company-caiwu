package cn.gson.financial.timedTasks.domain.req;

import jakarta.persistence.Column;
import lombok.Data;

import java.time.LocalDate;
import java.util.Date;

@Data
public class UpdateTaxPaymentReq {
    /**
     * 报税任务表id
     */
    private Long id;
    private Long accountSetsId;
    private String totalTaxes;
    /**
     * 图片
     */
    private String imgUrl;
    private String taxPaymentHtml;
    private LocalDate checkDate;
    /**
     * 失败原因
     */
    private String failureReason;
    /**
     * 执行结果 0 未执行 1成功 2失败
     */
    private Integer result;
    /**
     * 执行状态 0 未执行 1执行完成
     */
    private Integer state;
}
