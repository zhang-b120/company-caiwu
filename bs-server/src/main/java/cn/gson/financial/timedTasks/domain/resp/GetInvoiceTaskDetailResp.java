package cn.gson.financial.timedTasks.domain.resp;

import cn.gson.financial.timedTasks.domain.dto.InvoiceTaskDetailDTO;
import lombok.Data;

import java.util.List;

/**
 * @Author zhb
 * @Date 2025-10-29
 * @Description
 */
@Data
public class GetInvoiceTaskDetailResp {

    private Long accountSetsId;

    private String companyName;

    private Integer result;

    private String failReason;

    private List<InvoiceTaskDetailDTO> detailDTOList;
}