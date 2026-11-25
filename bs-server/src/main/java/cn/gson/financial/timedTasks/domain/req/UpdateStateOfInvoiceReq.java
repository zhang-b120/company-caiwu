package cn.gson.financial.timedTasks.domain.req;

import cn.gson.financial.timedTasks.domain.dto.InvoiceTaskDetailDTO;
import cn.gson.financial.timedTasks.domain.dto.TaskInvoiceResultDTO;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

@Data
public class UpdateStateOfInvoiceReq {
    /**
     * 任务ID
     */
    private Long taskId;
    /**
     * 账套ID
     */
    private Long accountSetsId;
    /**
     * 任务结果
     */
    private Integer result;
    /**
     * 任务状态
     */
    private Integer state;
    /**
     * 失败原因
     */
    private String failureReason;

    private List<TaskInvoiceResultDTO> resultDTOList;
}
