package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper=false)
public class GetPrivance extends AbsEntity {

    private String taxSystemKey;
    private String province;
    private String electricityTaxPhone;
    private Integer accountSetCount;
    private String status;
}
