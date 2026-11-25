package cn.gson.financial.agent.taxlogin.service;

import com.microsoft.playwright.Page;

public class SelectiveDomFilter {

    public static void keepSpecificElementsWithScripts(Page page, String... targetClasses) {
        page.evaluate("(targetClasses) => {\n" +
                "    console.log('=== 开始选择性DOM过滤 ===');\n" +
                "    console.log('目标class:', targetClasses);\n" +
                "    \n" +
                "    // 1. 收集所有需要保留的元素\n" +
                "    const elementsToKeep = new Set();\n" +
                "    \n" +
                "    // 保留html和body\n" +
                "    elementsToKeep.add(document.documentElement);\n" +
                "    elementsToKeep.add(document.body);\n" +
                "    \n" +
                "    // 2. 保留所有script标签\n" +
                "    const allScripts = document.querySelectorAll('script');\n" +
                "    allScripts.forEach(script => {\n" +
                "        elementsToKeep.add(script);\n" +
                "    });\n" +
                "    \n" +
                "    // 3. 保留所有style标签（确保样式）\n" +
                "    const allStyles = document.querySelectorAll('style');\n" +
                "    allStyles.forEach(style => {\n" +
                "        elementsToKeep.add(style);\n" +
                "    });\n" +
                "    \n" +
                "    // 4. 保留指定class的元素及其所有子元素\n" +
                "    targetClasses.forEach(className => {\n" +
                "        const elements = document.querySelectorAll(`.${className}`);\n" +
                "        console.log(`找到 ${elements.length} 个 .${className} 元素`);\n" +
                "        \n" +
                "        elements.forEach(element => {\n" +
                "            // 保留元素本身\n" +
                "            elementsToKeep.add(element);\n" +
                "            \n" +
                "            // 递归保留所有子元素\n" +
                "            function retainAllChildren(parent) {\n" +
                "                const children = parent.children;\n" +
                "                for (let i = 0; i < children.length; i++) {\n" +
                "                    const child = children[i];\n" +
                "                    elementsToKeep.add(child);\n" +
                "                    retainAllChildren(child); // 递归保留所有后代\n" +
                "                }\n" +
                "            }\n" +
                "            retainAllChildren(element);\n" +
                "            \n" +
                "            // 保留所有祖先元素直到body（确保DOM结构完整）\n" +
                "            let parent = element.parentElement;\n" +
                "            while (parent && parent !== document.body) {\n" +
                "                elementsToKeep.add(parent);\n" +
                "                parent = parent.parentElement;\n" +
                "            }\n" +
                "        });\n" +
                "    });\n" +
                "    \n" +
                "    console.log('需要保留的元素数量:', elementsToKeep.size);\n" +
                "    \n" +
                "    // 5. 删除不在保留列表中的元素\n" +
                "    function cleanDOM() {\n" +
                "        const allElements = document.body.querySelectorAll('*');\n" +
                "        const elementsArray = Array.from(allElements);\n" +
                "        \n" +
                "        // 从最内层开始删除\n" +
                "        elementsArray.reverse().forEach(element => {\n" +
                "            if (!elementsToKeep.has(element) && \n" +
                "                element !== document.body && \n" +
                "                element !== document.documentElement) {\n" +
                "                console.log('删除:', element.tagName, element.className || '');\n" +
                "                element.remove();\n" +
                "            }\n" +
                "        });\n" +
                "    }\n" +
                "    \n" +
                "    cleanDOM();\n" +
                "    \n" +
                "    // 6. 验证结果\n" +
                "    console.log('=== 过滤完成 ===');\n" +
                "    console.log('保留的script数量:', document.querySelectorAll('script').length);\n" +
                "    console.log('保留的style数量:', document.querySelectorAll('style').length);\n" +
                "    targetClasses.forEach(className => {\n" +
                "        console.log(`保留的 .${className} 数量:`, document.querySelectorAll(`.${className}`).length);\n" +
                "    });\n" +
                "}", targetClasses);
    }

    // 针对单个class的简化版本
    public static void keepSingleClassWithScripts(Page page, String targetClass) {
        keepSpecificElementsWithScripts(page, targetClass);
    }
}
