package cn.gson.financial.kernel.common;/* Copyright © 2020 Yuech and/or its affiliates. All rights reserved. */

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import jakarta.servlet.http.HttpServletResponse;

import cn.gson.financial.kernel.model.entity.AccountSets;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.util.CellRangeAddress;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;


/**
 * @Author zhb
 * @Date 2025-05-20
 * @Description
 */
public class ExcelUtils {

    /**
     * 导入参数 默认第二行
     */
    public static final int ROW = 1;
    /**
     * 导入参数 默认第一页
     */
    public static final int SHEET = 0;

    public static final String UTF8 = "UTF-8";


    /**
     * excel导出
     *
     * @param response
     * @param excelName 文件名
     * @param clazz     映射类
     * @param data      映射类对应集合
     * @throws Exception
     */
    public static <T> void exportFun(HttpServletResponse response, String excelName, Class<T> clazz, List<T> data) throws Exception {
        response.setContentType("application/vnd.ms-excel;charset=UTF-8");
        response.setCharacterEncoding(UTF8);
        String fileName = URLEncoder.encode(excelName.concat(".xlsx"), UTF8);
        response.setHeader("Content-disposition", "attachment;filename=" + fileName);
        EasyExcel.write(response.getOutputStream(), clazz)
                .excelType(ExcelTypeEnum.XLSX)
                .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                .sheet(excelName).doWrite(data);
    }

    /**
     * 导出资产负债表Excel
     */
    public static void exportBalanceSheet(HttpServletResponse response, String reportName, 
                                        AccountSets accountSets, LocalDate voucherDate, String dimension,
                                        List<cn.gson.financial.kernel.model.dto.ReportItemsDto> leftColumn, 
                                        List<cn.gson.financial.kernel.model.dto.ReportItemsDto> rightColumn) throws java.io.IOException {
        
        // 设置响应头
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(UTF8);
        String fileName = URLEncoder.encode(reportName + "_" + voucherDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx", UTF8);
        response.setHeader("Content-disposition", "attachment;filename=" + fileName);

        // 创建工作簿
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet(reportName);

        // 设置样式
        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle boldStyle = createBoldStyle(workbook);

        int rowNum = 0;

        // 标题行
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(reportName);
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 7));

        // 二级标题行
        Row subTitleRow = sheet.createRow(rowNum++);
        Cell subTitleCell = subTitleRow.createCell(6);
        subTitleCell.setCellValue(getReportTypeText());
        subTitleCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 6, 7));

        // 企业信息行
        Row companyRow = sheet.createRow(rowNum++);
        Cell companyCell = companyRow.createCell(0);
        companyCell.setCellValue("编制单位：" + accountSets.getCompanyName());
        companyCell.setCellStyle(dataStyle);

        Cell dateCell = companyRow.createCell(3);
        dateCell.setCellValue(formatDate(voucherDate, dimension));
        dateCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(2, 2, 3, 5));

        Cell unitCell = companyRow.createCell(7);
        unitCell.setCellValue("单位：元");
        unitCell.setCellStyle(dataStyle);

        // 表头行
        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"资产", "行次", "期末余额", "年初余额", "负债和所有者权益", "行次", "期末余额", "年初余额"};
        for (int i = 0; i < headers.length; i++) {
            Cell headerCell = headerRow.createCell(i);
            headerCell.setCellValue(headers[i]);
            headerCell.setCellStyle(headerStyle);
        }

        // 设置列宽
        int[] columnWidths = {46, 7, 22, 22, 46, 7, 22, 22};
        for (int i = 0; i < columnWidths.length; i++) {
            sheet.setColumnWidth(i, columnWidths[i] * 256);
        }

        // 填充数据
        int maxRows = Math.max(leftColumn.size(), rightColumn.size());

        for (int i = 0; i < maxRows; i++) {
            Row dataRow = sheet.createRow(rowNum++);
            
                  // 填充左侧数据（资产）
            if (i < leftColumn.size()) {
                fillRowData(dataRow, 0, leftColumn.get(i), dataStyle, numberStyle, boldStyle);
            }

            // 填充右侧数据（负债和所有者权益）
            if (i < rightColumn.size()) {
                fillRowData(dataRow, 4, rightColumn.get(i), dataStyle, numberStyle, boldStyle);
            }
        }

        // 设置边框
        addBorders(sheet, 3, rowNum - 1, 8);

           // 输出到响应流
        workbook.write(response.getOutputStream());
        workbook.close();
    }

    private static CellStyle createTitleStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private static CellStyle createHeaderStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 13);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        addBorderToStyle(style);
        return style;
    }

    private static CellStyle createDataStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setFontHeightInPoints((short) 9);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        addBorderToStyle(style);
        return style;
    }

    private static CellStyle createNumberStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setFontHeightInPoints((short) 9);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00_);[Red](#,##0.00)"));
        addBorderToStyle(style);
        return style;
    }

    private static CellStyle createBoldStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 9);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        addBorderToStyle(style);
        return style;
    }

    private static void addBorderToStyle(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }

    private static void addBorders(Sheet sheet, int startRow, int endRow, int columns) {
        for (int rowIndex = startRow; rowIndex <= endRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row != null) {
                for (int colIndex = 0; colIndex < columns; colIndex++) {
                    Cell cell = row.getCell(colIndex);
                    if (cell == null) {
                        cell = row.createCell(colIndex);
                    }
                    CellStyle style = cell.getCellStyle();
                    if (style == null) {
                        style = sheet.getWorkbook().createCellStyle();
                        cell.setCellStyle(style);
                    }
                    addBorderToStyle(style);
                }
            }
        }
    }

    private static String getReportTypeText() {
        return "会计准则报表";
    }

    private static String formatDate(LocalDate voucherDate, String dimension) {
        if ("QUARTER".equals(dimension)) {
            int quarter = (voucherDate.getMonthValue() - 1) / 3 + 1;
            return voucherDate.getYear() + "年第" + quarter + "季度";
        } else {
            return voucherDate.format(DateTimeFormatter.ofPattern("yyyy年MM月"));
        }
    }

    private static void fillRowData(Row dataRow, int startCol, cn.gson.financial.kernel.model.dto.ReportItemsDto item, 
                                  CellStyle dataStyle, CellStyle numberStyle, CellStyle boldStyle) {
        if (item == null) return;

        // 项目名称
        Cell nameCell = dataRow.createCell(startCol);
        String indent = getIndent(item.getLevel());
        nameCell.setCellValue(indent + item.getTitle());
        boolean isBold = item.getIsBolder() != null && item.getIsBolder();
        nameCell.setCellStyle(isBold ? boldStyle : dataStyle);

        // 行次
        Cell rowNumCell = dataRow.createCell(startCol + 1);
        String rowNum = item.getRowNum();
        if (rowNum != null && !rowNum.isEmpty()) {
            rowNumCell.setCellValue(rowNum);
        }
        rowNumCell.setCellStyle(dataStyle);

        // 期末余额和年初余额
        Map<String, java.math.BigDecimal> values = item.getValues();
        if (values != null) {
            // 期末余额
            Cell endBalanceCell = dataRow.createCell(startCol + 2);
            java.math.BigDecimal endBalance = values.get("期末余额");
            if (endBalance != null && endBalance.compareTo(java.math.BigDecimal.ZERO) != 0) {
                endBalanceCell.setCellValue(endBalance.doubleValue());
                endBalanceCell.setCellStyle(numberStyle);
            } else {
                endBalanceCell.setCellStyle(numberStyle);
            }

            // 年初余额
            Cell beginBalanceCell = dataRow.createCell(startCol + 3);
            java.math.BigDecimal beginBalance = values.get("年初余额");
            if (beginBalance != null && beginBalance.compareTo(java.math.BigDecimal.ZERO) != 0) {
                beginBalanceCell.setCellValue(beginBalance.doubleValue());
                beginBalanceCell.setCellStyle(numberStyle);
            } else {
                beginBalanceCell.setCellStyle(numberStyle);
            }
        }
    }

    private static String getIndent(Integer level) {
        if (level == null || level <= 0) {
            return "";
        }
        return "  ".repeat(level * 2);
    }

    /**
     * 导入excel文件
     *  只读取第一页 sheet = 0 ,只读取第二页 sheet = 1
     *  第一行没有表头,从第一行开始读取   row = 0 ,第一行有表头,从第二行开始读取   row = 1
     *
     * @param in    {@link InputStream}
     * @param sheet {@link Integer}   指定读取某一页
     * @param row   {@link Integer}   起始行
     * @param clazz {@link Class}
     * @return {@link List<T>}
     * @author myk
     */
    public static <T> List<T> importFun(InputStream in, int sheet, int row, Class<T> clazz) {
        return EasyExcel.read(in)
                .head(clazz)
                .sheet(sheet)
                .headRowNumber(row)
                .doReadSync();
    }

    /**
     * 导入excel文件
     * ps: 默认是只读取第一页,从第二行开始
     *
     * @param in    {@link InputStream}
     * @param clazz {@link Class}
     * @return {@link List<T>}
     * @author myk
     */
    public static <T> List<T> importFun(InputStream in, Class<T> clazz) {
        return importFun(in, SHEET, ROW, clazz);
    }

    /**
     * 导出利润表Excel
     */
    public static void exportIncomeStatement(HttpServletResponse response, String reportName, 
                                           AccountSets accountSets, LocalDate voucherDate, String dimension,
                                           List<cn.gson.financial.kernel.model.dto.ReportItemsDto> items) throws java.io.IOException {
        
        // 设置响应头
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(UTF8);
        String fileName = URLEncoder.encode(reportName + "_" + voucherDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx", UTF8);
        response.setHeader("Content-disposition", "attachment;filename=" + fileName);

        // 创建工作簿
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet(reportName);

        // 设置样式
        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle boldStyle = createBoldStyle(workbook);

        int rowNum = 0;

        // 标题行
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(reportName);
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));

        // 二级标题行
        Row subTitleRow = sheet.createRow(rowNum++);
        Cell subTitleCell = subTitleRow.createCell(2);
        subTitleCell.setCellValue(getReportTypeText());
        subTitleCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 2, 3));

        // 企业信息行
        Row companyRow = sheet.createRow(rowNum++);
        Cell companyCell = companyRow.createCell(0);
        companyCell.setCellValue("编制单位：" + accountSets.getCompanyName());
        companyCell.setCellStyle(dataStyle);

        Cell dateCell = companyRow.createCell(1);
        dateCell.setCellValue(formatDate(voucherDate, dimension));
        dateCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(2, 2, 1, 2));

        Cell unitCell = companyRow.createCell(3);
        unitCell.setCellValue("单位：元");
        unitCell.setCellStyle(dataStyle);

        // 表头行
        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"项目", "行次", "本年累计金额", "本期金额"};
        for (int i = 0; i < headers.length; i++) {
            Cell headerCell = headerRow.createCell(i);
            headerCell.setCellValue(headers[i]);
            headerCell.setCellStyle(headerStyle);
        }

        // 设置列宽
        int[] columnWidths = {46, 7, 22, 22};
        for (int i = 0; i < columnWidths.length; i++) {
            sheet.setColumnWidth(i, columnWidths[i] * 256);
        }

        // 填充数据
        for (cn.gson.financial.kernel.model.dto.ReportItemsDto item : items) {
            Row dataRow = sheet.createRow(rowNum++);
            fillIncomeStatementRowData(dataRow, item, dataStyle, numberStyle, boldStyle);
        }

        // 设置边框
        addBorders(sheet, 3, rowNum - 1, 4);

        // 输出到响应流
        workbook.write(response.getOutputStream());
        workbook.close();
    }

    private static void fillIncomeStatementRowData(Row dataRow, cn.gson.financial.kernel.model.dto.ReportItemsDto item, 
                                                 CellStyle dataStyle, CellStyle numberStyle, CellStyle boldStyle) {
        if (item == null) return;

        // 项目名称
        Cell nameCell = dataRow.createCell(0);
        String indent = getIndent(item.getLevel());
        nameCell.setCellValue(indent + item.getTitle());
        boolean isBold = item.getIsBolder() != null && item.getIsBolder();
        nameCell.setCellStyle(isBold ? boldStyle : dataStyle);

        // 行次
        Cell rowNumCell = dataRow.createCell(1);
        String rowNum = item.getRowNum();
        if (rowNum != null && !rowNum.isEmpty()) {
            rowNumCell.setCellValue(rowNum);
        }
        rowNumCell.setCellStyle(dataStyle);

        // 本年累计金额和本期金额
        Map<String, java.math.BigDecimal> values = item.getValues();
        if (values != null) {
            // 本年累计金额
            Cell yearAmountCell = dataRow.createCell(2);
            java.math.BigDecimal yearAmount = values.get("本年累计金额");
            if (yearAmount != null && yearAmount.compareTo(java.math.BigDecimal.ZERO) != 0) {
                yearAmountCell.setCellValue(yearAmount.doubleValue());
                yearAmountCell.setCellStyle(numberStyle);
            } else {
                yearAmountCell.setCellStyle(numberStyle);
            }

            // 本期金额
            Cell periodAmountCell = dataRow.createCell(3);
            java.math.BigDecimal periodAmount = values.get("本期金额");
            if (periodAmount != null && periodAmount.compareTo(java.math.BigDecimal.ZERO) != 0) {
                periodAmountCell.setCellValue(periodAmount.doubleValue());
                periodAmountCell.setCellStyle(numberStyle);
            } else {
                periodAmountCell.setCellStyle(numberStyle);
            }
        }
    }

    /**
     * 导出现金流量表Excel
     */
    public static void exportCashFlowStatement(HttpServletResponse response, String reportName, 
                                             AccountSets accountSets, LocalDate voucherDate, String dimension,
                                             List<cn.gson.financial.kernel.model.dto.ReportItemsDto> items) throws java.io.IOException {
        
        // 设置响应头
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(UTF8);
        String fileName = URLEncoder.encode(reportName + "_" + voucherDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".xlsx", UTF8);
        response.setHeader("Content-disposition", "attachment;filename=" + fileName);

        // 创建工作簿
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet(reportName);

        // 设置样式
        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle boldStyle = createBoldStyle(workbook);

        int rowNum = 0;

        // 标题行
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(reportName);
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));

        // 二级标题行
        Row subTitleRow = sheet.createRow(rowNum++);
        Cell subTitleCell = subTitleRow.createCell(2);
        subTitleCell.setCellValue(getReportTypeText());
        subTitleCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 2, 3));

        // 企业信息行
        Row companyRow = sheet.createRow(rowNum++);
        Cell companyCell = companyRow.createCell(0);
        companyCell.setCellValue("编制单位：" + accountSets.getCompanyName());
        companyCell.setCellStyle(dataStyle);

        Cell dateCell = companyRow.createCell(1);
        dateCell.setCellValue(formatDate(voucherDate, dimension));
        dateCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(2, 2, 1, 2));

        Cell unitCell = companyRow.createCell(3);
        unitCell.setCellValue("单位：元");
        unitCell.setCellStyle(dataStyle);

        // 表头行
        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"项目", "行次", "本年累计金额", "本期金额"};
        for (int i = 0; i < headers.length; i++) {
            Cell headerCell = headerRow.createCell(i);
            headerCell.setCellValue(headers[i]);
            headerCell.setCellStyle(headerStyle);
        }

        // 设置列宽
        int[] columnWidths = {52, 7, 22, 22}; // 现金流量表项目名称较长，增加第一列宽度
        for (int i = 0; i < columnWidths.length; i++) {
            sheet.setColumnWidth(i, columnWidths[i] * 256);
        }

        // 填充数据
        for (cn.gson.financial.kernel.model.dto.ReportItemsDto item : items) {
            Row dataRow = sheet.createRow(rowNum++);
            fillCashFlowStatementRowData(dataRow, item, dataStyle, numberStyle, boldStyle);
        }

        // 设置边框
        addBorders(sheet, 3, rowNum - 1, 4);

        // 输出到响应流
        workbook.write(response.getOutputStream());
        workbook.close();
    }

    private static void fillCashFlowStatementRowData(Row dataRow, cn.gson.financial.kernel.model.dto.ReportItemsDto item, 
                                                   CellStyle dataStyle, CellStyle numberStyle, CellStyle boldStyle) {
        if (item == null) return;

        // 项目名称
        Cell nameCell = dataRow.createCell(0);
        String indent = getIndent(item.getLevel());
        nameCell.setCellValue(indent + item.getTitle());
        boolean isBold = item.getIsBolder() != null && item.getIsBolder();
        nameCell.setCellStyle(isBold ? boldStyle : dataStyle);

        // 行次
        Cell rowNumCell = dataRow.createCell(1);
        String rowNum = item.getRowNum();
        if (rowNum != null && !rowNum.isEmpty()) {
            rowNumCell.setCellValue(rowNum);
        }
        rowNumCell.setCellStyle(dataStyle);

        // 本年累计金额和本期金额
        Map<String, java.math.BigDecimal> values = item.getValues();
        if (values != null) {
            // 本年累计金额
            Cell yearAmountCell = dataRow.createCell(2);
            java.math.BigDecimal yearAmount = values.get("本年累计金额");
            if (yearAmount != null && yearAmount.compareTo(java.math.BigDecimal.ZERO) != 0) {
                yearAmountCell.setCellValue(yearAmount.doubleValue());
                yearAmountCell.setCellStyle(numberStyle);
            } else {
                yearAmountCell.setCellStyle(numberStyle);
            }

            // 本期金额
            Cell periodAmountCell = dataRow.createCell(3);
            java.math.BigDecimal periodAmount = values.get("本期金额");
            if (periodAmount != null && periodAmount.compareTo(java.math.BigDecimal.ZERO) != 0) {
                periodAmountCell.setCellValue(periodAmount.doubleValue());
                periodAmountCell.setCellStyle(numberStyle);
            } else {
                periodAmountCell.setCellStyle(numberStyle);
            }
        }
    }

    /**
     * 导出资产负债表Excel为字节数组
     */
    public static byte[] exportBalanceSheetBytes(String reportName, 
                                               AccountSets accountSets, LocalDate voucherDate, String dimension,
                                               List<cn.gson.financial.kernel.model.dto.ReportItemsDto> leftColumn, 
                                               List<cn.gson.financial.kernel.model.dto.ReportItemsDto> rightColumn) throws java.io.IOException {
        
        // 创建工作簿
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet(reportName);

        // 设置样式
        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle boldStyle = createBoldStyle(workbook);

        int rowNum = 0;

        // 标题行
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(reportName);
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 7));

        // 二级标题行
        Row subTitleRow = sheet.createRow(rowNum++);
        Cell subTitleCell = subTitleRow.createCell(6);
        subTitleCell.setCellValue(getReportTypeText());
        subTitleCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 6, 7));

        // 企业信息行
        Row companyRow = sheet.createRow(rowNum++);
        Cell companyCell = companyRow.createCell(0);
        companyCell.setCellValue("编制单位：" + accountSets.getCompanyName());
        companyCell.setCellStyle(dataStyle);

        Cell dateCell = companyRow.createCell(2);
        dateCell.setCellValue(formatDate(voucherDate, dimension));
        dateCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(2, 2, 2, 5));

        Cell unitCell = companyRow.createCell(7);
        unitCell.setCellValue("单位：元");
        unitCell.setCellStyle(dataStyle);

        // 表头行
        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"资产", "行次", "期末余额", "年初余额", "负债和所有者权益", "行次", "期末余额", "年初余额"};
        for (int i = 0; i < headers.length; i++) {
            Cell headerCell = headerRow.createCell(i);
            headerCell.setCellValue(headers[i]);
            headerCell.setCellStyle(headerStyle);
        }

        // 设置列宽
        int[] columnWidths = {25, 7, 22, 22, 25, 7, 22, 22};
        for (int i = 0; i < columnWidths.length; i++) {
            sheet.setColumnWidth(i, columnWidths[i] * 256);
        }

        // 填充数据
        int maxRows = Math.max(leftColumn.size(), rightColumn.size());
        for (int i = 0; i < maxRows; i++) {
            Row dataRow = sheet.createRow(rowNum++);
            
            // 填充左侧（资产）数据
            if (i < leftColumn.size()) {
                fillBalanceSheetRowData(dataRow, leftColumn.get(i), 0, dataStyle, numberStyle, boldStyle);
            }
            
            // 填充右侧（负债和所有者权益）数据
            if (i < rightColumn.size()) {
                fillBalanceSheetRowData(dataRow, rightColumn.get(i), 4, dataStyle, numberStyle, boldStyle);
            }
        }

        // 设置边框
        addBorders(sheet, 3, rowNum - 1, 8);

        // 输出到字节数组
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        workbook.write(outputStream);
        workbook.close();
        return outputStream.toByteArray();
    }

    /**
     * 填充资产负债表行数据
     */
    private static void fillBalanceSheetRowData(Row dataRow, cn.gson.financial.kernel.model.dto.ReportItemsDto item, 
                                              int startCol, CellStyle dataStyle, CellStyle numberStyle, CellStyle boldStyle) {
        if (item == null) return;

        // 项目名称
        Cell nameCell = dataRow.createCell(startCol);
        String indent = getIndent(item.getLevel());
        nameCell.setCellValue(indent + item.getTitle());
        boolean isBold = item.getIsBolder() != null && item.getIsBolder();
        nameCell.setCellStyle(isBold ? boldStyle : dataStyle);

        // 行次
        Cell rowNumCell = dataRow.createCell(startCol + 1);
        String rowNum = item.getRowNum();
        if (rowNum != null && !rowNum.isEmpty()) {
            rowNumCell.setCellValue(rowNum);
        }
        rowNumCell.setCellStyle(dataStyle);

        // 期末余额和年初余额
        Map<String, java.math.BigDecimal> values = item.getValues();
        if (values != null) {
            // 期末余额
            Cell endAmountCell = dataRow.createCell(startCol + 2);
            java.math.BigDecimal endAmount = values.get("期末余额");
            if (endAmount != null && endAmount.compareTo(java.math.BigDecimal.ZERO) != 0) {
                endAmountCell.setCellValue(endAmount.doubleValue());
                endAmountCell.setCellStyle(numberStyle);
            } else {
                endAmountCell.setCellStyle(numberStyle);
            }

            // 年初余额
            Cell beginAmountCell = dataRow.createCell(startCol + 3);
            java.math.BigDecimal beginAmount = values.get("年初余额");
            if (beginAmount != null && beginAmount.compareTo(java.math.BigDecimal.ZERO) != 0) {
                beginAmountCell.setCellValue(beginAmount.doubleValue());
                beginAmountCell.setCellStyle(numberStyle);
            } else {
                beginAmountCell.setCellStyle(numberStyle);
            }
        }
    }

    /**
     * 导出利润表Excel为字节数组
     */
    public static byte[] exportIncomeStatementBytes(String reportName, 
                                                  AccountSets accountSets, LocalDate voucherDate, String dimension,
                                                  List<cn.gson.financial.kernel.model.dto.ReportItemsDto> items) throws java.io.IOException {
        
        // 创建工作簿
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet(reportName);

        // 设置样式
        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle boldStyle = createBoldStyle(workbook);

        int rowNum = 0;

        // 标题行
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(reportName);
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));

        // 二级标题行
        Row subTitleRow = sheet.createRow(rowNum++);
        Cell subTitleCell = subTitleRow.createCell(2);
        subTitleCell.setCellValue(getReportTypeText());
        subTitleCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 2, 3));

        // 企业信息行
        Row companyRow = sheet.createRow(rowNum++);
        Cell companyCell = companyRow.createCell(0);
        companyCell.setCellValue("编制单位：" + accountSets.getCompanyName());
        companyCell.setCellStyle(dataStyle);

        Cell dateCell = companyRow.createCell(1);
        dateCell.setCellValue(formatDate(voucherDate, dimension));
        dateCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(2, 2, 1, 2));

        Cell unitCell = companyRow.createCell(3);
        unitCell.setCellValue("单位：元");
        unitCell.setCellStyle(dataStyle);

        // 表头行
        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"项目", "行次", "本年累计金额", "本期金额"};
        for (int i = 0; i < headers.length; i++) {
            Cell headerCell = headerRow.createCell(i);
            headerCell.setCellValue(headers[i]);
            headerCell.setCellStyle(headerStyle);
        }

        // 设置列宽
        int[] columnWidths = {46, 7, 22, 22};
        for (int i = 0; i < columnWidths.length; i++) {
            sheet.setColumnWidth(i, columnWidths[i] * 256);
        }

        // 填充数据
        for (cn.gson.financial.kernel.model.dto.ReportItemsDto item : items) {
            Row dataRow = sheet.createRow(rowNum++);
            fillIncomeStatementRowData(dataRow, item, dataStyle, numberStyle, boldStyle);
        }

        // 设置边框
        addBorders(sheet, 3, rowNum - 1, 4);

        // 输出到字节数组
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        workbook.write(outputStream);
        workbook.close();
        return outputStream.toByteArray();
    }

    /**
     * 导出现金流量表Excel为字节数组
     */
    public static byte[] exportCashFlowStatementBytes(String reportName, 
                                                    AccountSets accountSets, LocalDate voucherDate, String dimension,
                                                    List<cn.gson.financial.kernel.model.dto.ReportItemsDto> items) throws java.io.IOException {
        
        // 创建工作簿
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet(reportName);

        // 设置样式
        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle boldStyle = createBoldStyle(workbook);

        int rowNum = 0;

        // 标题行
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(reportName);
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));

        // 二级标题行
        Row subTitleRow = sheet.createRow(rowNum++);
        Cell subTitleCell = subTitleRow.createCell(2);
        subTitleCell.setCellValue(getReportTypeText());
        subTitleCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 2, 3));

        // 企业信息行
        Row companyRow = sheet.createRow(rowNum++);
        Cell companyCell = companyRow.createCell(0);
        companyCell.setCellValue("编制单位：" + accountSets.getCompanyName());
        companyCell.setCellStyle(dataStyle);

        Cell dateCell = companyRow.createCell(1);
        dateCell.setCellValue(formatDate(voucherDate, dimension));
        dateCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(2, 2, 1, 2));

        Cell unitCell = companyRow.createCell(3);
        unitCell.setCellValue("单位：元");
        unitCell.setCellStyle(dataStyle);

        // 表头行
        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"项目", "行次", "本年累计金额", "本期金额"};
        for (int i = 0; i < headers.length; i++) {
            Cell headerCell = headerRow.createCell(i);
            headerCell.setCellValue(headers[i]);
            headerCell.setCellStyle(headerStyle);
        }

        // 设置列宽
        int[] columnWidths = {52, 7, 22, 22}; // 现金流量表项目名称较长，增加第一列宽度
        for (int i = 0; i < columnWidths.length; i++) {
            sheet.setColumnWidth(i, columnWidths[i] * 256);
        }

        // 填充数据
        for (cn.gson.financial.kernel.model.dto.ReportItemsDto item : items) {
            Row dataRow = sheet.createRow(rowNum++);
            fillCashFlowStatementRowData(dataRow, item, dataStyle, numberStyle, boldStyle);
        }

        // 设置边框
        addBorders(sheet, 3, rowNum - 1, 4);

        // 输出到字节数组
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        workbook.write(outputStream);
        workbook.close();
        return outputStream.toByteArray();
    }

    // ======================== 流式导出方法 ========================

    /**
     * 导出资产负债表Excel到流
     */
    public static void exportBalanceSheetStream(String reportName, 
                                              AccountSets accountSets, LocalDate voucherDate, String dimension,
                                              List<cn.gson.financial.kernel.model.dto.ReportItemsDto> leftColumn, 
                                              List<cn.gson.financial.kernel.model.dto.ReportItemsDto> rightColumn,
                                              java.io.OutputStream outputStream) throws java.io.IOException {
        
        // 创建工作簿
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet(reportName);

        // 设置样式
        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle boldStyle = createBoldStyle(workbook);

        int rowNum = 0;

        // 标题行
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(reportName);
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 7));

        // 二级标题行
        Row subTitleRow = sheet.createRow(rowNum++);
        Cell subTitleCell = subTitleRow.createCell(6);
        subTitleCell.setCellValue(getReportTypeText());
        subTitleCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 6, 7));

        // 企业信息行
        Row companyRow = sheet.createRow(rowNum++);
        Cell companyCell = companyRow.createCell(0);
        companyCell.setCellValue("编制单位：" + accountSets.getCompanyName());
        companyCell.setCellStyle(dataStyle);

        Cell dateCell = companyRow.createCell(2);
        dateCell.setCellValue(formatDate(voucherDate, dimension));
        dateCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(2, 2, 2, 5));

        Cell unitCell = companyRow.createCell(7);
        unitCell.setCellValue("单位：元");
        unitCell.setCellStyle(dataStyle);

        // 表头行
        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"资产", "行次", "期末余额", "年初余额", "负债和所有者权益", "行次", "期末余额", "年初余额"};
        for (int i = 0; i < headers.length; i++) {
            Cell headerCell = headerRow.createCell(i);
            headerCell.setCellValue(headers[i]);
            headerCell.setCellStyle(headerStyle);
        }

        // 设置列宽
        int[] columnWidths = {25, 7, 22, 22, 25, 7, 22, 22};
        for (int i = 0; i < columnWidths.length; i++) {
            sheet.setColumnWidth(i, columnWidths[i] * 256);
        }

        // 填充数据
        int maxRows = Math.max(leftColumn.size(), rightColumn.size());
        for (int i = 0; i < maxRows; i++) {
            Row dataRow = sheet.createRow(rowNum++);
            
            // 填充左侧（资产）数据
            if (i < leftColumn.size()) {
                fillBalanceSheetRowData(dataRow, leftColumn.get(i), 0, dataStyle, numberStyle, boldStyle);
            }
            
            // 填充右侧（负债和所有者权益）数据
            if (i < rightColumn.size()) {
                fillBalanceSheetRowData(dataRow, rightColumn.get(i), 4, dataStyle, numberStyle, boldStyle);
            }
        }

        // 设置边框
        addBorders(sheet, 3, rowNum - 1, 8);

        // 直接写入输出流
        workbook.write(outputStream);
        workbook.close();
    }

    /**
     * 导出利润表Excel到流
     */
    public static void exportIncomeStatementStream(String reportName, 
                                                 AccountSets accountSets, LocalDate voucherDate, String dimension,
                                                 List<cn.gson.financial.kernel.model.dto.ReportItemsDto> items,
                                                 java.io.OutputStream outputStream) throws java.io.IOException {
        
        // 创建工作簿
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet(reportName);

        // 设置样式
        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle boldStyle = createBoldStyle(workbook);

        int rowNum = 0;

        // 标题行
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(reportName);
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));

        // 二级标题行
        Row subTitleRow = sheet.createRow(rowNum++);
        Cell subTitleCell = subTitleRow.createCell(2);
        subTitleCell.setCellValue(getReportTypeText());
        subTitleCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 2, 3));

        // 企业信息行
        Row companyRow = sheet.createRow(rowNum++);
        Cell companyCell = companyRow.createCell(0);
        companyCell.setCellValue("编制单位：" + accountSets.getCompanyName());
        companyCell.setCellStyle(dataStyle);

        Cell dateCell = companyRow.createCell(1);
        dateCell.setCellValue(formatDate(voucherDate, dimension));
        dateCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(2, 2, 1, 2));

        Cell unitCell = companyRow.createCell(3);
        unitCell.setCellValue("单位：元");
        unitCell.setCellStyle(dataStyle);

        // 表头行
        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"项目", "行次", "本年累计金额", "本期金额"};
        for (int i = 0; i < headers.length; i++) {
            Cell headerCell = headerRow.createCell(i);
            headerCell.setCellValue(headers[i]);
            headerCell.setCellStyle(headerStyle);
        }

        // 设置列宽
        int[] columnWidths = {46, 7, 22, 22};
        for (int i = 0; i < columnWidths.length; i++) {
            sheet.setColumnWidth(i, columnWidths[i] * 256);
        }

        // 填充数据
        for (cn.gson.financial.kernel.model.dto.ReportItemsDto item : items) {
            Row dataRow = sheet.createRow(rowNum++);
            fillIncomeStatementRowData(dataRow, item, dataStyle, numberStyle, boldStyle);
        }

        // 设置边框
        addBorders(sheet, 3, rowNum - 1, 4);

        // 直接写入输出流
        workbook.write(outputStream);
        workbook.close();
    }

    /**
     * 导出现金流量表Excel到流
     */
    public static void exportCashFlowStatementStream(String reportName, 
                                                   AccountSets accountSets, LocalDate voucherDate, String dimension,
                                                   List<cn.gson.financial.kernel.model.dto.ReportItemsDto> items,
                                                   java.io.OutputStream outputStream) throws java.io.IOException {
        
        // 创建工作簿
        Workbook workbook = new XSSFWorkbook();
        Sheet sheet = workbook.createSheet(reportName);

        // 设置样式
        CellStyle titleStyle = createTitleStyle(workbook);
        CellStyle headerStyle = createHeaderStyle(workbook);
        CellStyle dataStyle = createDataStyle(workbook);
        CellStyle numberStyle = createNumberStyle(workbook);
        CellStyle boldStyle = createBoldStyle(workbook);

        int rowNum = 0;

        // 标题行
        Row titleRow = sheet.createRow(rowNum++);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(reportName);
        titleCell.setCellStyle(titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 3));

        // 二级标题行
        Row subTitleRow = sheet.createRow(rowNum++);
        Cell subTitleCell = subTitleRow.createCell(2);
        subTitleCell.setCellValue(getReportTypeText());
        subTitleCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 2, 3));

        // 企业信息行
        Row companyRow = sheet.createRow(rowNum++);
        Cell companyCell = companyRow.createCell(0);
        companyCell.setCellValue("编制单位：" + accountSets.getCompanyName());
        companyCell.setCellStyle(dataStyle);

        Cell dateCell = companyRow.createCell(1);
        dateCell.setCellValue(formatDate(voucherDate, dimension));
        dateCell.setCellStyle(dataStyle);
        sheet.addMergedRegion(new CellRangeAddress(2, 2, 1, 2));

        Cell unitCell = companyRow.createCell(3);
        unitCell.setCellValue("单位：元");
        unitCell.setCellStyle(dataStyle);

        // 表头行
        Row headerRow = sheet.createRow(rowNum++);
        String[] headers = {"项目", "行次", "本年累计金额", "本期金额"};
        for (int i = 0; i < headers.length; i++) {
            Cell headerCell = headerRow.createCell(i);
            headerCell.setCellValue(headers[i]);
            headerCell.setCellStyle(headerStyle);
        }

        // 设置列宽
        int[] columnWidths = {52, 7, 22, 22}; // 现金流量表项目名称较长，增加第一列宽度
        for (int i = 0; i < columnWidths.length; i++) {
            sheet.setColumnWidth(i, columnWidths[i] * 256);
        }

        // 填充数据
        for (cn.gson.financial.kernel.model.dto.ReportItemsDto item : items) {
            Row dataRow = sheet.createRow(rowNum++);
            fillCashFlowStatementRowData(dataRow, item, dataStyle, numberStyle, boldStyle);
        }

        // 设置边框
        addBorders(sheet, 3, rowNum - 1, 4);

        // 直接写入输出流
        workbook.write(outputStream);
        workbook.close();
    }
}