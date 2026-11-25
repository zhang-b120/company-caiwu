package cn.gson.financial.sync.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;
import java.util.Objects;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.vo</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年09月11日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Data
@AllArgsConstructor
public class VoucherTagVo {
    private LocalDate date;
    private String word;
    private int code;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VoucherTagVo that = (VoucherTagVo) o;
        return code == that.code && Objects.equals(date, that.date) && Objects.equals(word, that.word);
    }

    @Override
    public int hashCode() {
        return Objects.hash(date, word, code);
    }
}
