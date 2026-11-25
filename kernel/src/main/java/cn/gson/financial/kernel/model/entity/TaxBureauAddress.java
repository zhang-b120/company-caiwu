package cn.gson.financial.kernel.model.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;

@DynamicInsert
@DynamicUpdate
@Entity
@Data
@Table(name = "tax_bureau_address")
public class TaxBureauAddress {

    @Id
    @Comment("主键")
    @Column(name = "id", nullable = false)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Comment("城市拼音")
    @Column(name = "pin_yin")
    private String pinYin;

    @Comment("城市名称")
    @Column(name = "city")
    private String city;

    @Comment("城市税务局网址")
    @Column(name = "city_tax_url")
    private String cityTaxUrl;

    @Comment("最小先执行1")
    @Column(name = "min_first_sort_one")
    private Long minFirstSortOne;

    @Comment("计算执行次数1")
    @Column(name = "execute_count_one")
    private Long executeCountOne;

    @Comment("最大执行次数1")
    @Column(name = "max_execute_count_one")
    private Long maxExecuteCountOne;
}


