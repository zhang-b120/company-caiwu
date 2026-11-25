package cn.gson.financial.kernel.model.ua.request;

import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
public class GetSalaryReviewReq extends GetCommonReq{

    private Long salaryId;
    private Boolean state;
}
