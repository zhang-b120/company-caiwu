package cn.gson.financial.kernel.model.yingDao.req;


import jakarta.persistence.Column;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

import java.io.Serial;

/**
 * @Author zhb
 * @Date 2025-09-04
 * @Description
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class UpdateIndividualTaxReq extends GetCommonReq{
    @Serial
    private static final long serialVersionUID = 5227516583692183914L;
    /**
     * 个税任务表的主键
     */
    private Long id;

    /**
     * 结果 0 未执行 1 失败 2成功
     */
    private Integer result;

    /**
     * 失败原因
     */
    private  String failedReason;

    /**
     * 截图名
     */
    private String imgName;


}
