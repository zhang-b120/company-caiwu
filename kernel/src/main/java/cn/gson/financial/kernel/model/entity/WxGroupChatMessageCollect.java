package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import jakarta.persistence.Table;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.GenericGenerator;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 企业微信群聊采集（工单 123 · FN-WXC-01 / 工单 89 WXKW-04「入库」落地）。
 *
 * <p>qykj-finmaster 命中"发票业务"关键词后，把命中时刻前后 10 分钟的群聊消息打包推到本仓
 * {@code OutSystem2CRMController.collectWxGroupChat}，每次命中落一行。供后续 CRM/财税侧
 * 查证、回溯使用。</p>
 *
 * <p>建表方式：与 {@code IndustryBenchmark}/{@code BankLib} 同 — 仅靠 JPA {@code ddl-auto=update}
 * 自动建表，<b>无 Flyway 迁移</b>。表名 {@code fxy_financial_wx_group_chat_collect}。</p>
 */
@Data
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("企业微信群聊采集（命中关键词的前后10分钟聊天，一次命中一行）")
public class WxGroupChatMessageCollect implements Serializable {

    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    @Comment("群聊名称（finmaster 推送时的群名）")
    @Column(name = "chat_name", length = 255)
    private String chatName;

    @Comment("消息内容（命中前后约10分钟的聚合聊天文本；体量可能很大，采用 LONGTEXT）")
    @Column(name = "content", columnDefinition = "LONGTEXT")
    private String content;

    @Comment("创建时间（入库时间，由 service 端 LocalDateTime.now() 填入）")
    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Comment("是否删除：0=未删除（默认）/ 1=已删除（逻辑删除位）")
    @Column(name = "deleted")
    private Integer deleted;
}
