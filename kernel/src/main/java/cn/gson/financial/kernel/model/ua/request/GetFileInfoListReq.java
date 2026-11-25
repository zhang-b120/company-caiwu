package cn.gson.financial.kernel.model.ua.request;

import cn.gson.financial.kernel.controller.Page;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * @Author zhb
 * @Date 2025-07-17
 * @Description
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class GetFileInfoListReq extends GetCommonReq{
    private static final long serialVersionUID = 5687026224045488771L;

    private Page page;

    private String type;

}