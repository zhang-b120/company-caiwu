package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import cn.gson.financial.kernel.model.vo.BiVarVo;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 自定义报表</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年07月30日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("自定义报表")
public class BiReport extends AbsEntity {

    @Comment("报表名称")
    @Column(length = 64)
    private String name;

    @Comment("报表配置")
    @JdbcTypeCode(SqlTypes.JSON)
    private JSONObject config;

    @Comment("单元格配置")
    @JdbcTypeCode(SqlTypes.JSON)
    private JSONArray cells;

    @Comment("变量列表")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Map<String, BiVarVo>> vars;

    @Comment("排序")
    private Integer pos;

    @Comment("分组标记")
    @Column(length = 32)
    private String tag;

    @Comment("备注")
    private String remark;

    @CreationTimestamp
    private LocalDateTime createTime;

    @UpdateTimestamp
    private LocalDateTime updateTime;
}
