package cn.gson.financial.financialProcess.req;

import lombok.Data;

import java.util.List;

@Data
public class ResetTicketStatusReq {
    private List<Long> ids;
    private String accountingPeriod;
}
