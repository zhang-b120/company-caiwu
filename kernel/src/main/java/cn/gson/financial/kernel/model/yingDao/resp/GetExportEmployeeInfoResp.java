package cn.gson.financial.kernel.model.yingDao.resp;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * @Author zhb
 * @Date 2025-09-04
 * @Description
 */
@Data
public class GetExportEmployeeInfoResp implements Serializable {
    @Serial
    private static final long serialVersionUID = -8817265885578829228L;

    /**
     * 文件路径
     */
    private String fileUrl;

    /**
     * 文件名称
     */
    private String fileName;

    /**
     * 文件类型
     */
    private String fileSuffix;

    /**
     * 文件全路径
     */
    private String filePath;
}