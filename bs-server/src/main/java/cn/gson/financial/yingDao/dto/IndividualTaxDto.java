package cn.gson.financial.yingDao.dto;

import lombok.Data;

@Data
public class IndividualTaxDto {

    private Long id;

    /**
     * 单位名称
     */
    private String companyName;

    /**
     * 个税密码
     */
    private String individualTaxCode;

    /**
     * 统一社会信用代码
     */
    private String creditCode;

    /**
     * 账套id
     */
    public Long accountSetsId;

}
