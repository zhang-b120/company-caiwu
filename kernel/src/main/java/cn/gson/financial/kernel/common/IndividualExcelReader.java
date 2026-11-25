package cn.gson.financial.kernel.common;

import cn.gson.financial.kernel.annotation.ColName;
import cn.gson.financial.kernel.model.entity.EmployeeInfo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.web.multipart.MultipartFile;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class IndividualExcelReader{
    public static List<EmployeeInfo> readExcel(String filePath) {
        List<EmployeeInfo> result = new ArrayList<EmployeeInfo>();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");

        try (FileInputStream fis = new FileInputStream(filePath);
             Workbook workbook = new HSSFWorkbook(fis)) { // 使用HSSF处理.xls文件

            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);

            // 1. 读取列名
            List<String> columnNames = new ArrayList<>();
            for (int j = 0; j <= 49; j++) { // 明确处理50列 (A列到AX列)
                Cell cell = headerRow.getCell(j);
                columnNames.add(cell != null ? cell.getStringCellValue().trim() : "Column" + (j + 1));
            }

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row dataRow = sheet.getRow(i);
                if (dataRow == null) continue;

                EmployeeInfo rowData = new EmployeeInfo();
                for (int j = 0; j < columnNames.size(); j++) {
                    String columnName = columnNames.get(j);
                    Cell cell = dataRow.getCell(j, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

                    // 保持不变的部分 ↓↓↓
                    String cellValue = null;
                    if (cell != null) {
                        switch (cell.getCellType()) {
                            case STRING:
                                cellValue = cell.getStringCellValue().trim();
                                break;
                            case NUMERIC:
                                if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
                                    cellValue = dateFormat.format(cell.getDateCellValue());
                                } else {
                                    double num = cell.getNumericCellValue();
                                    cellValue = (num == (int) num) ?
                                            String.valueOf((int) num) :
                                            String.valueOf(num);
                                }
                                break;
                            case BOOLEAN:
                                cellValue = String.valueOf(cell.getBooleanCellValue());
                                break;
                            case FORMULA:
                                switch (cell.getCachedFormulaResultType()) {
                                    case NUMERIC:
                                        if (DateUtil.isCellDateFormatted(cell)) {
                                            cellValue = dateFormat.format(cell.getDateCellValue());
                                        } else {
                                            cellValue = String.valueOf(cell.getNumericCellValue());
                                        }
                                        break;
                                    case STRING:
                                        cellValue = cell.getStringCellValue().trim();
                                        break;
                                    case BOOLEAN:
                                        cellValue = String.valueOf(cell.getBooleanCellValue());
                                        break;
                                    default:
                                        cellValue = "";
                                }
                                break;
                            default:
                                cellValue = "";
                        }
                    }
                    // 使用注解驱动赋值：
                    if (cellValue != null && !cellValue.isEmpty()) {
                        try {
                            rowData.setFieldByColName(columnName, cellValue);
                        } catch (Exception e) {
                            System.err.printf("设置字段失败 [行:%d 列:%s 值:%s]: %s%n",
                                    i + 1, columnName, cellValue, e.getMessage());
                        }
                    }
                }
                result.add(rowData);
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }
    public static List<EmployeeInfo> readEmployeeInfoFromStream(MultipartFile file){
        List<EmployeeInfo> employeeList = new ArrayList<>();

        try {
            InputStream inputStream = file.getInputStream();
            Workbook workbook = new HSSFWorkbook(inputStream);

            Sheet sheet = workbook.getSheetAt(0); // 获取第一个工作表

            // 读取表头，建立列名到列索引的映射
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new IOException("Excel文件没有表头行");
            }

            Map<String, Integer> columnIndexMap = new HashMap<>();

            for (Cell cell : headerRow) {
                String columnName = getCellValueAsString(cell);
                columnIndexMap.put(columnName, cell.getColumnIndex());
            }

            // 从第二行开始读取数据（跳过表头）
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                EmployeeInfo employee = new EmployeeInfo();
                boolean hasData = false;

                // 遍历所有有注解的字段
                for (Field field : EmployeeInfo.class.getDeclaredFields()) {
                    ColName annotation = field.getAnnotation(ColName.class);
                    if (annotation != null) {
                        String columnName = annotation.value();
                        Integer columnIndex = columnIndexMap.get(columnName);

                        if (columnIndex != null) {
                            Cell cell = row.getCell(columnIndex);
                            String cellValue = getCellValueAsString(cell);

                            if (!cellValue.isEmpty()) {
                                hasData = true;
                            }

                            try {
                                employee.setFieldByColName(columnName, cellValue);
                            } catch (Exception e) {
                                System.err.println("Error setting field " + columnName + " with value: " + cellValue);
                                // 可以选择记录日志但继续处理其他字段
                            }
                        }
                    }
                }

                // 只有当行中有数据时才添加到列表
                if (hasData) {
                    employeeList.add(employee);
                }
            }
        }
        catch (Exception e){
            System.out.println(e.getMessage());
        }
        return employeeList;
    }
    /**
     * 获取单元格值的字符串表示
     */
    private static String getCellValueAsString(Cell cell) {
        if (cell == null) {
            return "";
        }

        switch (cell.getCellType()) {
            case STRING:
                return cell.getStringCellValue().trim();
            case NUMERIC:
                if (DateUtil.isCellDateFormatted(cell)) {
                    // 日期格式处理
                    return cell.getDateCellValue().toString();
                } else {
                    // 数值处理，避免科学计数法
                    double numericValue = cell.getNumericCellValue();
                    if (numericValue == Math.floor(numericValue)) {
                        return String.valueOf((long) numericValue);
                    } else {
                        return String.valueOf(numericValue);
                    }
                }
            case BOOLEAN:
                return String.valueOf(cell.getBooleanCellValue());
            case FORMULA:
                try {
                    return cell.getStringCellValue();
                } catch (Exception e) {
                    try {
                        return String.valueOf(cell.getNumericCellValue());
                    } catch (Exception ex) {
                        return cell.getCellFormula();
                    }
                }
            case BLANK:
                return "";
            default:
                return "";
        }
    }
}
