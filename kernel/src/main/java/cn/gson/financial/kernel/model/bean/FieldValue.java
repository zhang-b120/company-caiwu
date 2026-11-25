package cn.gson.financial.kernel.model.bean;

import cn.gson.financial.kernel.model.entity.SalaryField;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.NumberUtil;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.model.bean</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年12月28日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Setter
@Getter
public class FieldValue extends HashMap<String, Value> {

    private List<SalaryField> salaryFields = new ArrayList<>();

    @Override
    public Value put(String key, Value val) {
        return super.put(key, val);
    }

    public Optional<Value> getVal(String name) {
        Stream<Value> valueStream = this.values().stream().filter(f -> f.getName().equalsIgnoreCase(name));
        return valueStream.findFirst();
    }

    /**
     * 应发工资合计金额（找不到该字段 / 值为空 / 非数字时返 0）。
     * <p>FN-FIX-CHECKOUT-PRECHECK-DATA-AWARE：「该工资是否应产生凭证」的共享判定单一真相源——
     * salaryCheck 据此只对 {@code salarySum() > 0} 的工资要求凭证（0 金额工资 autogen 跳过不建，
     * 不应再要求其有凭证，否则永远过不了结转前检查）。
     */
    public BigDecimal salarySum() {
        return getVal("应发工资合计")
                .map(Value::getValue)
                .map(Object::toString)
                .filter(NumberUtil::isNumber)
                .map(BigDecimal::new)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * 添加字段值
     *
     * @param field
     * @param val
     * @return
     */
    public FieldValue add(SalaryField field, Value val) {
        Assert.isFalse(this.containsKey(field.getId()), field.getFieldName() + "字段值已存在~");
        salaryFields.add(field);
        if (!field.getEnabled()) {
            val.setValue("0");
        }
        super.put(field.getId().toString(), val);
        return this;
    }

    public void add(FieldValue fieldValue) {
        for (String fid : fieldValue.keySet()) {
            if (this.containsKey(fid)) {
                Value value1 = fieldValue.get(fid);
                Value value2 = this.get(fid);
                Object val1 = value1.getValue();
                Object val2 = value2.getValue();

                if (val1 != null && val2 != null && NumberUtil.isNumber(val1.toString()) && NumberUtil.isNumber(val2.toString())) {
                    value2.setValue(NumberUtil.add(val1.toString(), val2.toString()));
                    this.put(fid, value2);
                }
            } else {
                this.put(fid, BeanUtil.toBean(fieldValue.get(fid), Value.class));
            }
        }
    }
}
