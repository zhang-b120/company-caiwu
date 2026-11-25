package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@DynamicInsert
@DynamicUpdate
@Comment("添加用户短信信息")
public class UserMessage {
    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;

    @Comment("发送者电话")
    private String senderPhoneNumber;

    @Comment("接收者电话")
    private String receiverPhoneNumber;

    @Comment("接收的短信内容")
    private String messageContent;

    @Comment("发送时间")
    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime timestamp;
}
