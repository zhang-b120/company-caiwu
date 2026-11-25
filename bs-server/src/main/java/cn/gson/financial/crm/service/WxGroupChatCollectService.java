package cn.gson.financial.crm.service;

import cn.gson.financial.kernel.model.entity.WxGroupChatMessageCollect;
import cn.gson.financial.kernel.model.repo.WxGroupChatMessageCollectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 企业微信群聊采集服务（工单 123 · FN-WXC-01）。
 *
 * <p>finmaster 端命中"发票业务"关键词后会推送 (chatName, content) 到本仓的开放 API；
 * 本服务负责落表：每次推送都直接 insert 一行，{@code createTime=now, deleted=0}。</p>
 *
 * <p>Controller 端不做复杂逻辑，仅做基础参数校验后调本服务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WxGroupChatCollectService {

    private final WxGroupChatMessageCollectRepository repository;

    /**
     * 落库一条群聊采集记录。
     *
     * @param chatName 群聊名称
     * @param content  消息内容（命中前后约 10 分钟的聚合文本）
     * @return 落库后的实体（含主键）
     */
    @Transactional
    public WxGroupChatMessageCollect saveCollect(String chatName, String content) {
        WxGroupChatMessageCollect entity = new WxGroupChatMessageCollect();
        entity.setChatName(chatName);
        entity.setContent(content);
        entity.setCreateTime(LocalDateTime.now());
        entity.setDeleted(0);
        WxGroupChatMessageCollect saved = repository.save(entity);
        log.info("[WxGroupChatCollect] 入库成功 id={} chatName={} contentLen={}",
                saved.getId(), chatName,
                content == null ? 0 : content.length());
        return saved;
    }
}
