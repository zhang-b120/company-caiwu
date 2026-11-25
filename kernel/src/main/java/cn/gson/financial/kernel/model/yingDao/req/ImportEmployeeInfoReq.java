package cn.gson.financial.kernel.model.yingDao.req;

import jakarta.persistence.Column;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

import java.io.Serial;

/**
 * @Author zhb
 * @Date 2025-09-02
 * @Description
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ImportEmployeeInfoReq extends GetCommonReq{
    @Serial
    private static final long serialVersionUID = -8695152724583821112L;

    /**
     * 文件名
     */
    private String fileName;


    /**
     * 结果 0 未执行 1 失败 2成功
     */
    private Integer result;

    /**
     *失败原因
     */
    private String failedReason;

    /**
     * 截图名
     */
    private String imgName;

    /**
     * 截图路径
     */
    private String imgUrl;
}
