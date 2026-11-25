package cn.gson.financial.kernel.model.vo;

import cn.hutool.core.lang.Dict;
import lombok.Data;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>***************************************</p>
 * <ul style="margin:15px;">
 * <li>Description : financial</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2024年11月12日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * <p>***************************************</p>
 */
@Data
public class AgeAnalysisQueryVo {
    private Long subjectId;
    private LocalDate start;
    private LocalDate end;
    private Boolean ignoreZero = true;
    private Long accountSetsId;
    private Long localCurrencyId;
    private List<Integer> days;

    public int getMaxDay() {
        return days.get(days.size() - 1);
    }

    public List<Integer> getEachDays() {
        ArrayList<Integer> integers = new ArrayList<>(days);
        integers.add(days.get(days.size() - 1) + 1);
        return integers;
    }

    public boolean isEqualDate() {
        return end.isEqual(start);
    }

    public LocalDate getEnd() {
        return end.withDayOfMonth(end.lengthOfMonth());
    }

    public List<Dict> getDayHeader() {
        List<Dict> list = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            if (i == 0) {
                list.add(Dict.create().set("day", days.get(i)).set("title", days.get(i) + "天以内"));
            } else {
                list.add(Dict.create().set("day", days.get(i)).set("title", days.get(i - 1) + "-" + days.get(i) + "天"));
                if (i == days.size() - 1) {
                    list.add(Dict.create().set("day", (days.get(i) + 1)).set("title", days.get(i) + "天以上"));
                }
            }
        }
        return list;
    }

    public LocalDate getStart() {
        return start.withDayOfMonth(1);
    }
}
