package cn.gson.financial.kernel.model.repo;

import cn.gson.financial.kernel.model.entity.WxGroupChatMessageCollect;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 企业微信群聊采集仓储（工单 123 · FN-WXC-01）。
 *
 * <p>默认只暴露按"群聊名称 + 未删除"倒序查询，供 finmaster/财税侧回溯展示。
 * 主键自增（由 CosId 雪花生成），主键查询走 {@link JpaRepository#findById(Object)}。</p>
 */
public interface WxGroupChatMessageCollectRepository extends JpaRepository<WxGroupChatMessageCollect, Long> {

    /**
     * 按群聊名称 + 是否删除，按创建时间倒序拉取。
     *
     * @param chatName 群聊名称
     * @param deleted  0=未删除 / 1=已删除
     */
    List<WxGroupChatMessageCollect> findByChatNameAndDeletedOrderByCreateTimeDesc(String chatName, Integer deleted);
}
