package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

/**
 * 
 * <p><b>Copyright © 2010-2022开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : 辅助核算数据</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2022年10月22日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Getter
@Setter
@Entity
@DynamicUpdate
@DynamicInsert
@Comment("自定义辅助明细数据")
public class AccountingCategoryDetails extends AcDetails {

    @Column(name = "cus_column_0")
    private String cusColumn0;

    @Column(name = "cus_column_1")
    private String cusColumn1;

    @Column(name = "cus_column_2")
    private String cusColumn2;

    @Column(name = "cus_column_3")
    private String cusColumn3;

    @Column(name = "cus_column_4")
    private String cusColumn4;

    @Column(name = "cus_column_5")
    private String cusColumn5;

    @Column(name = "cus_column_6")
    private String cusColumn6;

    @Column(name = "cus_column_7")
    private String cusColumn7;

    @Column(name = "cus_column_8")
    private String cusColumn8;

    @Column(name = "cus_column_9")
    private String cusColumn9;

    @Column(name = "cus_column_10")
    private String cusColumn10;

    @Column(name = "cus_column_11")
    private String cusColumn11;

    @Column(name = "cus_column_12")
    private String cusColumn12;

    @Column(name = "cus_column_13")
    private String cusColumn13;

    @Column(name = "cus_column_14")
    private String cusColumn14;

    @Column(name = "cus_column_15")
    private String cusColumn15;
}
