package cn.gson.financial.kernel.common;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.support.ExcelTypeEnum;
import com.alibaba.excel.write.builder.ExcelWriterBuilder;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.alibaba.excel.write.style.column.LongestMatchColumnWidthStyleStrategy;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.poi.ss.formula.functions.T;

import java.io.*;
import java.net.URLEncoder;
import java.util.List;

/**
 * @Author zhb
 * @Date 2025-09-02
 * @Description
 */
public class EasyExcelUtils {

    /**
     * 导入excel文件
     * @param in
     * @param clazz
     * @return
     * @param <T>
     */
    public static <T> List<T> importFun(InputStream in, Class<T> clazz) {
        return EasyExcelUtils.importFun(in, 0, 1, clazz);
    }

    /**
     * 导入excel文件
     * @param in
     * @param sheet
     * @param row
     * @param clazz
     * @return
     * @param <T>
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
     * @param filePath
     * @param clazz
     * @return
     * @param <T>
     */
    public static <T> List<T> importFun(String filePath, Class<T> clazz) {
        File file = new File(filePath);
        InputStream in = null;
        try {
            in = new FileInputStream(file);
        } catch (Exception e) {

        }
        return EasyExcelUtils.importFun(in, clazz);
    }

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
        response.setCharacterEncoding("UTF-8");
        String fileName = URLEncoder.encode(excelName.concat(".xlsx"), "UTF-8");
        response.setHeader("Content-disposition", "attachment;filename=" + fileName);
        EasyExcel.write(response.getOutputStream(), clazz)
                .excelType(ExcelTypeEnum.XLSX)
                .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                .sheet(excelName).doWrite(data);
    }

    /**
     * excel导出到本地文件
     *
     * @param filePath  文件完整路径（包含文件名）
     * @param excelName 工作表名称
     * @param clazz     映射类
     * @param data      映射类对应集合
     * @param <T>       泛型类型
     */
    public static <T> void exportToLocal(String filePath, String excelName, Class<T> clazz, List<T> data) {
        EasyExcel.write(filePath, clazz)
                .excelType(ExcelTypeEnum.XLSX)
                .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                .sheet(excelName)
                .doWrite(data);
    }

    /**
     * excel导出到本地文件（使用输出流）
     *
     * @param outputStream 输出流
     * @param excelName    工作表名称
     * @param clazz        映射类
     * @param data         映射类对应集合
     * @param <T>          泛型类型
     */
    public static <T> void exportToLocal(OutputStream outputStream, String excelName, Class<T> clazz, List<T> data) {
        try {
            EasyExcel.write(outputStream, clazz)
                    .excelType(ExcelTypeEnum.XLS)
                    .registerWriteHandler(new LongestMatchColumnWidthStyleStrategy())
                    .sheet(excelName)
                    .doWrite(data);
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    // 关闭流时的异常处理
                }
            }
        }
    }
}
