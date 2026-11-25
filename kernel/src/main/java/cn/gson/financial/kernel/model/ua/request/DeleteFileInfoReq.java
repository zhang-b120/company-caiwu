package cn.gson.financial.kernel.model.ua.request;

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
public class DeleteFileInfoReq extends GetCommonReq{
    private static final long serialVersionUID = -4010105292769824764L;

    private Long id;
}