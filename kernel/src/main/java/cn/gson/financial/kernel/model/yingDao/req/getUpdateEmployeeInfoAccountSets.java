package cn.gson.financial.kernel.model.yingDao.req;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

@EqualsAndHashCode(callSuper = true)
@Data
public class getUpdateEmployeeInfoAccountSets extends GetCommonReq {
    @Serial
    private static final long serialVersionUID = 5585004637306946399L;
        private Long accountSetId;
        private Long id;
        private String result;
}
