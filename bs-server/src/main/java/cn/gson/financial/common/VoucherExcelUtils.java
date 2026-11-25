package cn.gson.financial.common;

import cn.gson.financial.kernel.exception.ServiceException;
import cn.gson.financial.kernel.model.dto.AccountingCategoryDto;
import cn.gson.financial.kernel.model.dto.VoucherDetailsDto;
import cn.gson.financial.kernel.model.dto.VoucherDto;
import cn.gson.financial.kernel.model.entity.AccountingCategoryDetails;
import cn.gson.financial.kernel.model.entity.Subject;
import cn.gson.financial.kernel.model.vo.UserVo;
import cn.gson.financial.kernel.service.AccountingCategoryDetailsService;
import cn.gson.financial.kernel.service.AccountingCategoryService;
import cn.gson.financial.kernel.service.SubjectService;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateFormatUtils;
import org.apache.commons.lang3.time.DateUtils;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 
 * <p><b>Copyright © 2010-2019开发公司All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.common</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019年10月15日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
@Component
@RequiredArgsConstructor
public final class VoucherExcelUtils {
    private String[] COLS = {};
    //辅助项目
    private String[] AUXILIARY = {};

    private DateFormat df = new SimpleDateFormat("yyyy-MM-dd");

    private final SubjectService subjectService;

    private final AccountingCategoryDetailsService accountingCategoryDetailsService;

    private final AccountingCategoryService accountingCategoryService;

    /**
     * 读取excel数据并转化
     *
     * @param fileName
     * @param is
     * @param userVo
     * @return
     * @throws IOException
     */
    public List<VoucherDto> readExcel(String fileName, InputStream is, UserVo userVo) throws IOException {
        this.loadCategory(userVo);

        List<VoucherDto> list = new ArrayList<>();
        Workbook workbook;
        if ("xls".equals(FilenameUtils.getExtension(fileName))) {
            workbook = new HSSFWorkbook(is);
        } else {
            workbook = new XSSFWorkbook(is);
        }
        Sheet sheet = workbook.getSheetAt(0);
        if (sheet != null) {
            //检查excel格式
            this.checkHeader(sheet);

            List<Map<String, Object>> data = new ArrayList<>(sheet.getLastRowNum());
            //所有编码类项目
            List<String> subjectCodeSet = new ArrayList<>();
            Map<String, Set<String>> auxiliaryMap = new HashMap<>(AUXILIARY.length);

            for (int i = 1, len = sheet.getLastRowNum(); i <= len; i++) {
                Row row = sheet.getRow(i);
                Map<String, Object> rowData = new HashMap<>(row.getLastCellNum());
                //根据表头遍历数据
                for (int j = 0; j < COLS.length; j++) {
                    Cell cell = row.getCell(j);
                    String key = COLS[j];
                    Object val;
                    try {
                        switch (cell.getCellType()) {
                            case NUMERIC:
                                if (DateUtil.isCellDateFormatted(cell)) {
                                    val = DateFormatUtils.format(cell.getDateCellValue(), "yyyy-MM-dd");
                                } else {
                                    val = cell.getNumericCellValue();
                                }
                                break;
                            default:
                                val = cell.getStringCellValue();
                                break;
                        }

                        if (key.equals("科目编码") && val instanceof Double) {
                            val = ((Double) val).intValue();
                        }
                        rowData.put(key, val);
                    } catch (Exception e) {
                        throw new ServiceException("[" + key + "]列读取错误！" + e.getMessage(), e);
                    }
                }

                subjectCodeSet.add(rowData.get("科目编码").toString());

                //汇总辅助项
                for (String a : AUXILIARY) {
                    if (!auxiliaryMap.containsKey(a)) {
                        auxiliaryMap.put(a, new HashSet<>());
                    }
                    Object val = rowData.get(a + "编码");
                    if (val != null && StringUtils.trimToNull(val.toString()) != null) {
                        auxiliaryMap.get(a).add(StringUtils.trim(val.toString()));
                    }
                }

                rowData.put("unix", StringUtils.join(rowData.get("日期"), "~", rowData.get("凭证号"), "~", rowData.get("备注")));
                data.add(rowData);
            }


            //科目数据
            Map<String, Subject> codeList = this.getByCodeList(userVo.getAccountSets().getId(), subjectCodeSet);
            //辅助项目数据
            Map<String, Map<String, AccountingCategoryDetails>> auxiliaryDataMap = this.getAuxiliaryDataMap(userVo.getAccountSets().getId(), auxiliaryMap);

            Map<String, List<Map<String, Object>>> unix = data.stream().collect(Collectors.groupingBy(map -> map.get("unix").toString()));

            unix.forEach((key, maps) -> {
                String[] split = StringUtils.split(key, "~");
                Calendar calendar;
                try {
                    calendar = DateUtils.toCalendar(df.parse(split[0]));
                } catch (ParseException e) {
                    throw new ServiceException("日期[" + split[0] + "]格式错误,请设置日期列单元格格式为‘文本’类型！", e);
                }
                String[] wordCode = StringUtils.split(split[1], "-");

                VoucherDto voucher = new VoucherDto();
                voucher.setWord(wordCode[0]);
                voucher.setCode(Integer.parseInt(wordCode[1]));
                if (split.length > 2) {
                    voucher.setRemark(split[2]);
                }
                voucher.setCreateDate(LocalDateTime.now());
                voucher.setYear(calendar.get(Calendar.YEAR));
                voucher.setMonth(calendar.get(Calendar.MONTH) + 1);
                voucher.setVoucherDate(LocalDate.from(calendar.toInstant()));
                voucher.setDetails(new ArrayList<>(maps.size()));
                voucher.setCreateMember(userVo.getId());
                voucher.setAccountSetsId(userVo.getAccountSets().getId());

                maps.forEach(map -> {
                    VoucherDetailsDto details = new VoucherDetailsDto();
                    JSONObject jo = new JSONObject(map);
                    details.setSummary(jo.getString("摘要"));
                    //TODO 导入待处理
//                    details.setSubjectName(jo.getString("科目名称"));
//                    details.setSubjectCode(jo.getString("科目编码"));
//                    details.setDebitAmount(jo.getDouble("借方本币"));
//                    details.setCreditAmount(jo.getDouble("贷方本币"));
//                    details.setAccountSetsId(userVo.getAccountSetsId());
//                    details.setAuxiliary(new ArrayList<>());
//                    details.setNum(DoubleValueUtil.getNotNullVal(jo.getDouble("借方数量"), jo.getDouble("贷方数量")));
//
//                    //处理科目ID
//                    if (!codeList.containsKey(details.getSubjectCode())) {
//                        throw new ServiceException("科目编码：" + details.getSubjectCode() + ",没有在账套中定义。");
//                    }
//                    details.setSubjectCode(codeList.get(details.getSubjectCode()).getCode());
//
//                    //处理辅助项目
//                    for (String a : AUXILIARY) {
//                        String val = jo.getString(a + "编码");
//                        if (StringUtils.isNotEmpty(val)) {
//                            if (!auxiliaryDataMap.get(a).containsKey(val)) {
//                                throw new ServiceException(a + "辅助编码：" + val + ",没有在账套辅助项中定义。");
//                            }
//                            details.getAuxiliary().add(auxiliaryDataMap.get(a).get(val));
//                        }
//                    }
//
//                    //处理名称
//                    if (details.getAuxiliary().size() > 0) {
//                        StringBuffer codeTitle = new StringBuffer(details.getSubjectCode());
//                        StringBuffer nameTitle = new StringBuffer(details.getSubjectName());
//                        StringBuffer auxiliaryTitle = new StringBuffer();
//
//                        details.getAuxiliary().forEach(d -> {
//                            codeTitle.append("_").append(d.getId());
//                            nameTitle.append("_").append(d.getName());
//                            auxiliaryTitle.append("_").append(d.getName());
//                        });
//                        details.setSubjectName(codeTitle.append("-").append(nameTitle).toString());
//                        details.setAuxiliaryTitle(auxiliaryTitle.toString());
//                    } else {
//                        details.setSubjectName(details.getSubjectCode() + "-" + details.getSubjectName());
//                    }

                    //details.setPrice();
                    voucher.getDetails().add(details);
                });

                list.add(voucher);
            });
        }

        log.info("data:{}", JSONObject.toJSONString(list));

        return list;
    }

    /**
     * 加载辅助字段
     *
     * @param userVo
     */
    private void loadCategory(UserVo userVo) {
        List<AccountingCategoryDto> list = this.accountingCategoryService.listByAccountSetsId(userVo.getAccountSets().getId());
        List<String> names = new ArrayList<>();
        for (AccountingCategoryDto accountingCategory : list) {
            names.add(accountingCategory.getName() + "编码");
            names.add(accountingCategory.getName() + "名称");
        }
        this.AUXILIARY = names.toArray(new String[0]);
        String cols = "日期,凭证号,摘要,科目编码,科目名称,外币代码,借方数量,借方外币,借方本币,贷方数量,贷方外币,贷方本币," + StringUtils.join(names, ",") + ",制单人,审核人,附件数,备注";
        this.COLS = cols.split(",");
    }

    private Map<String, Map<String, AccountingCategoryDetails>> getAuxiliaryDataMap(Long accountSetsId, Map<String, Set<String>> auxiliaryMap) {
        Map<String, Map<String, AccountingCategoryDetails>> data = new HashMap<>(auxiliaryMap.size());

        for (Map.Entry<String, Set<String>> entry : auxiliaryMap.entrySet()) {
            if (entry.getValue().size() > 0) {
                List<AccountingCategoryDetails> details = accountingCategoryDetailsService.getByCodeSet(entry.getKey(), accountSetsId, entry.getValue());
                Map<String, AccountingCategoryDetails> map = details.stream().collect(Collectors.toMap(AccountingCategoryDetails::getCode, s -> s));
                data.put(entry.getKey(), map);
            }
        }

        return data;
    }

    private Map<String, Subject> getByCodeList(Long accountSetsId, List<String> subjectCodeSet) {
        return this.subjectService.listByCode(accountSetsId, subjectCodeSet);
    }

    /**
     * 表头检查，不符合表头，不允许导入
     *
     * @param sheet
     */
    private void checkHeader(Sheet sheet) {
        Row row = sheet.getRow(0);
        for (int i = 0; i < COLS.length; i++) {
            String col = COLS[i];
            Cell cell = row.getCell(i);
            if (!col.equalsIgnoreCase(cell.getStringCellValue())) {
                throw new ServiceException("[" + col + "]位置表头错误！表头正确顺序:%s", StringUtils.join(COLS, ","));
            }
        }
    }
}
