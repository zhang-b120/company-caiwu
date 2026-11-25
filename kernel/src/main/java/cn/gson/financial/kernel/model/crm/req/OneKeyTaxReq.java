package cn.gson.financial.kernel.model.crm.req;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 一键报税请求实体
 */
@Data
public class OneKeyTaxReq implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<OneKeyTaxCompanyReq> list;
}

