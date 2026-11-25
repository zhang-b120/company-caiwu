package cn.gson.financial.kernel.model.entity;

import cn.gson.financial.kernel.model.hibernate.AbsEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.GenericGenerator;

import java.time.LocalDate;
import java.time.LocalDateTime;

@DynamicInsert
@DynamicUpdate
@Entity
@Data
public class TaskExecuteList{
    @Id
    @GenericGenerator(name = "CosIdGenerator", strategy = "cn.gson.financial.kernel.model.hibernate.CosIdGenerator")
    @GeneratedValue(generator = "CosIdGenerator")
    @Comment("主键")
    private Long id;
    //发起人
    private String sponsor;
    //任务类型
    private Integer taskType;
    //任务条数
    private String taskNumber;
    //任务状态
    private String taskStatus;
    //任务执行时间
    private LocalDateTime taskExecutionTime;
    //任务执行结果
    private String taskResult;
    //任务执行内容
    private String taskData;

    //账套id
    private  Long accountSetsId;
    //省份
    @Comment("省份")
    private String province;
    @Comment("排序")
    private Integer sort;



}
