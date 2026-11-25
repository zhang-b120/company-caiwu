package cn.gson.financial.kernel.tool;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.lang.Dict;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jxls.area.Area;
import org.jxls.builder.xls.XlsCommentAreaBuilder;
import org.jxls.command.AbstractCommand;
import org.jxls.command.Command;
import org.jxls.common.CellRef;
import org.jxls.common.Context;
import org.jxls.common.Size;
import org.jxls.expression.JexlExpressionEvaluator;
import org.jxls.transform.Transformer;
import org.jxls.transform.poi.PoiTransformer;
import org.jxls.util.JxlsHelper;

import java.io.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 
 * <p><b>Copyright © 2010-2023 开发公司 All Rights Reserved<b></p>
 * <ul style="margin:15px;">
 * <li>Description : cn.gson.financial.kernel.tool</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2023年12月04日</li>
 * <li>@author     : 开发者</li>
 * </ul>
 * 
 */
@Slf4j
public class JxlsUtils {
    public static void exportExcel(InputStream is, OutputStream os, Map<String, Object> model) throws Exception {
        Context context = new Context();
        if (model != null) {
            for (String key : model.keySet()) {
                context.putVar(key, model.get(key));
            }
        }
        JxlsHelper jxlsHelper = JxlsHelper.getInstance();
        Transformer transformer = jxlsHelper.createTransformer(is, os);
        XlsCommentAreaBuilder.addCommandMapping("number", NumberCommand.class);
        JexlExpressionEvaluator evaluator = (JexlExpressionEvaluator) transformer.getTransformationConfig().getExpressionEvaluator();
        Map<String, Object> funcs = new HashMap<>();
        funcs.put("utils", new JxlsUtils());//添加自定义功能
        evaluator.setJexlEngine(new JexlBuilder().namespaces(funcs).create());
        jxlsHelper.processTemplate(context, transformer);
    }

    public static void exportExcel(File xls, File out, Map<String, Object> model) throws Exception {
        exportExcel(new FileInputStream(xls), new FileOutputStream(out), model);
    }

    public static void exportExcel(String templateName, OutputStream os, Map<String, Object> model) throws Exception {
        exportExcel(getTemplate(templateName), os, model);
    }

    public static InputStream getTemplate(String name) {
        return ResourceUtil.getStream(name);
    }

    public String dateFmt(LocalDate date, String fmt) {
        if (date == null) {
            return "";
        }
        return date.format(DateTimeFormatter.ofPattern(fmt));
    }

    // if判断
    public Object ifelse(boolean b, Object o1, Object o2) {
        return b ? o1 : o2;
    }

    public static class NumberCommand extends AbstractCommand {
        Area area;

        /**
         * @return command name
         */
        @Override
        public String getName() {
            return "number";
        }

        /**
         * Applies a command at the given cell reference
         *
         * @param cellRef cell reference where the command must be applied
         * @param context bean context to use
         * @return size of enclosing command area after transformation
         */
        @Override
        public Size applyAt(CellRef cellRef, Context context) {
            Size resultSize = area.applyAt(cellRef, context);
            Dict h = (Dict) context.getVar("h");
            if (h != null && ((h.containsKey("number") && h.getBool("number")) || (h.containsKey("isRowNum") && h.getBool("isRowNum")))) {
                PoiTransformer transformer = (PoiTransformer) area.getTransformer();
                XSSFWorkbook workbook = transformer.getXSSFWorkbook();
                XSSFSheet sheet = workbook.getSheet(cellRef.getSheetName());
                XSSFCell cell = sheet.getRow(cellRef.getRow()).getCell(cellRef.getCol());
                XSSFCellStyle copy = cell.getCellStyle().copy();
                if ((h.containsKey("isRowNum") && h.getBool("isRowNum"))) {
                    copy.setAlignment(HorizontalAlignment.CENTER);
                    sheet.setColumnWidth(cellRef.getCol(), 256 * 6);
                } else {
                    copy.setAlignment(HorizontalAlignment.RIGHT);
                    sheet.setColumnWidth(cellRef.getCol(), 256 * 20);
                }
                cell.setCellStyle(copy);
            }
            return resultSize;
        }

        @Override
        public Command addArea(Area area) {
            super.addArea(area);
            this.area = area;
            return this;
        }
    }
}
