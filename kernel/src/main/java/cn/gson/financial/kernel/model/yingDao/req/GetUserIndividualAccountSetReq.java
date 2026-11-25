package cn.gson.financial.kernel.model.yingDao.req;

import cn.hutool.core.bean.BeanUtil;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.util.Map;

/**
 * @Author zhb
 * @Date 2025-09-01
 * @Description
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class GetUserIndividualAccountSetReq extends GetCommonReq{
    @Serial
    private static final long serialVersionUID = -6494992378306465431L;

    /**
     * 用户Id
     */
    private Long userId;

    /**
     * 类型
     */
    private String type;
    /**
     *  省份
     */
    private  String province;


    public Map<String, Object> toMap() {
        return BeanUtil.beanToMap(this);
    }
}
