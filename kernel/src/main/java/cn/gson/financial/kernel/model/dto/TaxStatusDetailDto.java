package cn.gson.financial.kernel.model.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 首页报税任务详情 DTO
 */
@Data
public class TaxStatusDetailDto {

    private Long accountSetsId;
    private LocalDate period;
    private Integer status;
    private Integer result;
    private String resultMessage;

    private List<Item> items;

    @Data
    public static class Item {
        private Long id;
        private String taxType;
        private String taxState;
        private LocalDate taxEndTime;
        private Integer status;
        private Integer result;
        private String screenshotUrl;
        private String resultMessage;
    }
}

