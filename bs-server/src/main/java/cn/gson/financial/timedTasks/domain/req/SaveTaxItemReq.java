package cn.gson.financial.timedTasks.domain.req;

import lombok.Data;

import java.util.List;
import java.util.Map;
@Data
public class SaveTaxItemReq {
    private List<Map<String, String>> tableData;
    private Long accountSetsId;
    private String companyName;
}
