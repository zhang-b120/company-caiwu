package cn.gson.financial.kernel.model.ua.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * @Author zhb
 * @Date 2025-07-11
 * @Description
 */
@Data
public class TaxTableResultDTO implements Serializable {
    private static final long serialVersionUID = -2359606259896480963L;

    /**
     * 报税时间
     */
    private LocalDate taxTime;
    /**
     * 报税条目总数
     */
    private Integer allCount;
    /**
     * 报税条目申报成功数
     */
    private Integer successCount;
    /**
     * 报税图片
     */
    private String imgPath;
}