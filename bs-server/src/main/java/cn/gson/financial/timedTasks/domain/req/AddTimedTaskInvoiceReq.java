package cn.gson.financial.timedTasks.domain.req;

import lombok.Data;

import java.time.LocalDate;
import java.util.Date;
import java.util.List;

/**
 * @Author zhb
 * @Date 2025-10-29
 * @Description
 */
@Data
public class AddTimedTaskInvoiceReq {

    /**
     * 账套ID
     */
    private List<Long> accountSetsIdList;

    /**
     * 任务时间
     */
    private Date checkDate;
}