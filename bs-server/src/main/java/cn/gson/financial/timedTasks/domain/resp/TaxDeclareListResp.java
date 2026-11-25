package cn.gson.financial.timedTasks.domain.resp;

import cn.gson.financial.kernel.model.entity.TaxDeclare;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 应申报税费种列表响应：列表 + 最近更新时间（供前端展示、判定是否重新抓取）
 */
@Data
public class TaxDeclareListResp implements Serializable {
    /** 应申报税费种行数据（含每条 createTime、updateTime） */
    private List<TaxDeclare> list;
    /** 该账套下数据最近一次更新时间（取所有行中 updateTime 最大值） */
    private LocalDateTime lastUpdateTime;
}
