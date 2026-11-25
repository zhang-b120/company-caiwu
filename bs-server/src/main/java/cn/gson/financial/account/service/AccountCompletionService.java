package cn.gson.financial.account.service;

import cn.gson.financial.account.entity.Account;
import cn.gson.financial.account.repository.AccountRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 科目完善服务类
 * 负责处理科目信息的整合、编码生成和规则应用
 */
@Service
public class AccountCompletionService {

    @Autowired
    private AccountRepository accountRepository;

    // 编码规范：4-3-3-2，用于定义各级子科目的长度
    private static final int[] CODE_SEGMENTS = {4, 3, 3, 2};
    // 本次运行时推断的编码规范（若可推断则优先使用）
    private int[] runtimeCodeSegments = null;

    /**
     * 根据初始化科目信息和待完善科目信息，完善科目信息的主方法。
     * 该方法实现了科目合并、优先级处理、新科目编码生成和余额方向继承等核心业务逻辑。
     *
     * @param initialAccounts 初始化科目信息 (模板)，包含完整的编码、名称和余额方向。
     * @param pendingAccounts 待完善科目信息 (客户提供)，可能只包含名称或不完整的编码。
     * @param subjectByCode 科目编码到科目信息的映射，用于推断科目类别
     * @return 包含完善后科目信息和原始待完善科目信息的列表
     */
    public List<Map<String, Object>> completeAccounts(List<Account> initialAccounts, List<Account> pendingAccounts, Map<String, Object> subjectByCode) {
        // 在本次处理开始时，根据现有数据（初始+待完善）推断编码分段规则
        this.runtimeCodeSegments = inferCodeSegmentsFromData(initialAccounts, pendingAccounts);
        // 使用Set来存储最终的科目，确保唯一性并方便查找。初始科目全部保留。
        Set<Account> finalAccounts = new HashSet<>(initialAccounts);
        // 维护一个已存在的编码集合，用于新编码生成时的唯一性检查。
        Set<String> existingCodes = initialAccounts.stream()
                .map(Account::getCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 存储处理结果的列表
        List<Map<String, Object>> resultList = new ArrayList<>();
        
        // 遍历待完善科目，逐一进行处理
        for (Account pendingAcc : pendingAccounts) {

            // 判断是否为一级科目
            boolean isLevel1Account = isLevel1Account(pendingAcc);

            Account matchedAccount = null;
            
            if (isLevel1Account) {
                // 一级科目匹配规则：1、按科目名称匹配；2、按科目编码匹配
                matchedAccount = matchLevel1Account(pendingAcc, initialAccounts);
            } else {
                // 非一级科目匹配规则：1、按父级科目名称+子级科目名称匹配；2、按父级科目编码+子级科目名称匹配
                matchedAccount = matchNonLevel1Account(pendingAcc, initialAccounts);
            }
            
            Account finalAccount = null;
            
            if (matchedAccount != null) {
                // 找到匹配的科目，使用匹配到的科目信息
                finalAccount = new Account(matchedAccount.getCode(), matchedAccount.getName(),
                        matchedAccount.getBalanceDirection(), matchedAccount.getParentCode(), matchedAccount.getParentName());
                finalAccounts.remove(matchedAccount);
                finalAccounts.add(finalAccount);
            } else {
                // 没有找到匹配的科目，新增科目
                Account newAccount = createNewAccount(pendingAcc, new ArrayList<>(finalAccounts), existingCodes);
                if (newAccount != null) {
                    finalAccounts.add(newAccount);
                    existingCodes.add(newAccount.getCode());
                    finalAccount = newAccount;
                } else {
                }
            }
            
            // 构建结果对象
            Map<String, Object> resultItem = new HashMap<>();
            Account accountForCategory = finalAccount != null ? finalAccount : pendingAcc;
            
            if (finalAccount != null) {
                // 规范化编码格式
                String normalizedCode = normalizeCodeToDefaultFormat(finalAccount.getCode());
                String normalizedParentCode = finalAccount.getParentCode() != null ? 
                    normalizeCodeToDefaultFormat(finalAccount.getParentCode()) : null;
                
                resultItem.put("code", normalizedCode);
                resultItem.put("name", extractSelfName(finalAccount.getName()));
                resultItem.put("balanceDirection", finalAccount.getBalanceDirection());
                resultItem.put("parentCode", normalizedParentCode);
                resultItem.put("parentName", extractSelfName(finalAccount.getParentName()));
            } else {
                // 如果无法处理，使用原始数据，但也要规范化编码
                String normalizedCode = normalizeCodeToDefaultFormat(pendingAcc.getCode());
                String normalizedParentCode = pendingAcc.getParentCode() != null ? 
                    normalizeCodeToDefaultFormat(pendingAcc.getParentCode()) : null;
                
                resultItem.put("code", normalizedCode);
                resultItem.put("name", extractSelfName(pendingAcc.getName()));
                resultItem.put("balanceDirection", pendingAcc.getBalanceDirection());
                resultItem.put("parentCode", normalizedParentCode);
                resultItem.put("parentName", extractSelfName(pendingAcc.getParentName()));
            }
            
            // 添加科目类别
            String category = inferCategory(accountForCategory, subjectByCode);
            resultItem.put("category", category);
            
            // 添加原始数据
            resultItem.put("oldCode", pendingAcc.getCode());
            resultItem.put("oldName", extractSelfName(pendingAcc.getName()));
            
            resultList.add(resultItem);
        }

        return resultList;
    }

    /**
     * 仅保留科目名称最后一级，例如：
     * "一级>二级>三级" -> "三级"
     */
    private String extractSelfName(String accountName) {
        if (accountName == null) return null;
        final String SEP = ">";
        int idx = accountName.lastIndexOf(SEP);
        return idx >= 0 ? accountName.substring(idx + SEP.length()) : accountName;
    }
    
    /**
     * 推断科目类别：优先取系统科目中的类型；否则依据编码首位 1资产/2负债/3权益/4成本/5损益；
     * 若自身无编码则尝试使用父级编码。
     */
    @SuppressWarnings("unchecked")
    private String inferCategory(Account account, Map<String, Object> subjectByCode) {
        // 1) 系统标准科目里已有类型
        if (account.getCode() != null) {
            Object vo = subjectByCode.get(account.getCode());
            if (vo != null && vo instanceof Map) {
                Map<String, Object> subjectMap = (Map<String, Object>) vo;
                Object type = subjectMap.get("type");
                if (type != null && !type.toString().trim().isEmpty()) {
                    return type.toString();
                }
            }
        }
        // 2) 用自身编码首位判断
        String bySelf = categoryByCodePrefix(account.getCode());
        if (bySelf != null) return bySelf;
        // 3) 尝试父级编码
        String parentCode = account.getParentCode();
        if (parentCode != null) {
            Object pvo = subjectByCode.get(parentCode);
            if (pvo != null && pvo instanceof Map) {
                Map<String, Object> parentSubjectMap = (Map<String, Object>) pvo;
                Object type = parentSubjectMap.get("type");
                if (type != null && !type.toString().trim().isEmpty()) {
                    return type.toString();
                }
            }
            String byParent = categoryByCodePrefix(parentCode);
            if (byParent != null) return byParent;
        }
        return null;
    }

    private String categoryByCodePrefix(String code) {
        if (code == null || code.isEmpty()) return null;
        char c = code.charAt(0);
        switch (c) {
            case '1': return "资产";
            case '2': return "负债";
            case '3': return "权益";
            case '4': return "成本";
            case '5': return "损益";
            default: return null;
        }
    }
    
    /**
     * 将编码规范化到4-3-3-2格式
     * @param code 原始编码
     * @return 规范化后的编码
     */
    private String normalizeCodeToDefaultFormat(String code) {
        if (code == null || code.trim().isEmpty()) {
            return code;
        }
        
        code = code.trim();
        
        // 如果编码长度不符合4-3-3-2规范，尝试规范化
        int[] segments = (runtimeCodeSegments != null ? runtimeCodeSegments : CODE_SEGMENTS);
        
        // 如果当前分段规则就是4-3-3-2，直接返回
        if (Arrays.equals(segments, CODE_SEGMENTS)) {
            return code;
        }
        
        // 使用现有的规范化逻辑
        return normalizeCodeToDefault(code, segments, CODE_SEGMENTS);
    }

    /**
     * 判断是否为一级科目
     * @param account 待判断的科目
     * @return 是否为一级科目
     */
    private boolean isLevel1Account(Account account) {
        if (account == null) return false;
        
        // 如果有编码，根据编码层级判断
        if (account.getCode() != null && !account.getCode().trim().isEmpty()) {
            int level = getCodeLevel(account.getCode());
            return level == 1;
        }
        
        // 如果没有编码，根据名称判断（不包含">"分隔符的为一级科目）
        if (account.getName() != null && !account.getName().contains(">")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 一级科目匹配规则
     * 1、按科目名称匹配
     * 2、按科目编码匹配
     * @param pendingAcc 待匹配的科目
     * @param initialAccounts 初始化科目列表
     * @return 匹配到的科目，如果没有匹配到返回null
     */
    private Account matchLevel1Account(Account pendingAcc, List<Account> initialAccounts) {
        // 1、按科目名称匹配
        Optional<Account> nameMatch = initialAccounts.stream()
                .filter(acc -> acc.getName() != null && pendingAcc.getName() != null
                        && normalizeString(acc.getName()).equals(normalizeString(pendingAcc.getName()))
                        && getCodeLevel(acc.getCode()) == 1)
                .findFirst();
        
        if (nameMatch.isPresent()) {
            return nameMatch.get();
        }
        
        // 2、按科目编码匹配
        if (pendingAcc.getCode() != null && !pendingAcc.getCode().trim().isEmpty()) {
            Optional<Account> codeMatch = initialAccounts.stream()
                    .filter(acc -> acc.getCode() != null && pendingAcc.getCode() != null
                            && normalizeString(acc.getCode()).equals(normalizeString(pendingAcc.getCode()))
                            && getCodeLevel(acc.getCode()) == 1)
                    .findFirst();
            
            if (codeMatch.isPresent()) {
                return codeMatch.get();
            }
        }
        return null;
    }
    
    /**
     * 非一级科目匹配规则
     * 1、按父级科目名称+子级科目名称匹配
     * 2、按父级科目编码+子级科目名称匹配
     * @param pendingAcc 待匹配的科目
     * @param initialAccounts 初始化科目列表
     * @return 匹配到的科目，如果没有匹配到返回null
     */
    private Account matchNonLevel1Account(Account pendingAcc, List<Account> initialAccounts) {
        // 提取子级科目名称
        String childName = extractChildNameFromPending(pendingAcc);
        if (childName == null || childName.trim().isEmpty()) {
            return null;
        }
        
        // 1、按父级科目名称+子级科目名称匹配
        if (pendingAcc.getName() != null && pendingAcc.getName().contains(">")) {
            String parentName = extractParentName(pendingAcc.getName());
            if (parentName != null && !parentName.trim().isEmpty()) {
                Optional<Account> parentNameMatch = initialAccounts.stream()
                        .filter(acc -> acc.getName() != null && normalizeString(acc.getName()).equals(normalizeString(childName))
                                && acc.getParentName() != null && normalizeString(acc.getParentName()).equals(normalizeString(parentName)))
                        .findFirst();
                
                if (parentNameMatch.isPresent()) {
                    return parentNameMatch.get();
                }
            }
        }
        
        // 2、按父级科目编码+子级科目名称匹配
        if (pendingAcc.getCode() != null && !pendingAcc.getCode().trim().isEmpty()) {
            // 根据编码推断父级编码
            String parentCode = inferParentCodeFromChildCode(pendingAcc.getCode());
            if (parentCode != null && !parentCode.trim().isEmpty()) {
                Optional<Account> parentCodeMatch = initialAccounts.stream()
                        .filter(acc -> acc.getName() != null && normalizeString(acc.getName()).equals(normalizeString(childName))
                                && acc.getParentCode() != null && normalizeString(acc.getParentCode()).equals(normalizeString(parentCode)))
                        .findFirst();
                
                if (parentCodeMatch.isPresent()) {
                    return parentCodeMatch.get();
                }
            }
        }
        return null;
    }
    
    /**
     * 根据子级编码推断父级编码
     * @param childCode 子级编码
     * @return 父级编码
     */
    private String inferParentCodeFromChildCode(String childCode) {
        if (childCode == null || childCode.length() <= 4) {
            return null;
        }
        
        int[] segments = (runtimeCodeSegments != null ? runtimeCodeSegments : CODE_SEGMENTS);
        int acc = 0;
        
        // 找到最接近的父级长度
        for (int i = 0; i < segments.length - 1; i++) {
            acc += segments[i];
            if (childCode.length() > acc) {
                return childCode.substring(0, acc);
            }
        }
        
        return null;
    }
    
    /**
     * 创建新科目
     * @param pendingAcc 待创建的科目
     * @param currentAllAccounts 当前所有科目
     * @param existingCodes 已存在的编码集合
     * @return 新创建的科目
     */
    private Account createNewAccount(Account pendingAcc, List<Account> currentAllAccounts, Set<String> existingCodes) {
        // 尝试使用原有的生成逻辑
        Account generated = generateNewAccount(pendingAcc, currentAllAccounts, existingCodes);
        if (generated != null) {
            return generated;
        }
        
        // 如果原有逻辑失败，尝试多父级科目生成逻辑
        List<Account> newAccounts = generateNewAccountsForMultipleParents(pendingAcc, currentAllAccounts, existingCodes);
        if (!newAccounts.isEmpty()) {
            return newAccounts.get(0); // 返回第一个生成的科目
        }
        
        return null;
    }

    /**
     * 基于现有科目编码动态推断编码分段规则。
     * 规则：
     * - 固定首层长度为4
     * - 从所有编码集合中抽取"父级长度"：若存在codeA为codeB的前缀，则len(codeA)视为一个层级长度
     * - 将所有推断出的长度（含最大长度）排序，计算相邻差值作为分段
     * - 若推断结果不合理，则回退默认{4,3,3,2}
     */
    private int[] inferCodeSegmentsFromData(List<Account> initialAccounts, List<Account> pendingAccounts) {
        try {
            // 先按“名称层级”优先推断（满足你的业务规则）：
            int[] byName = inferCodeSegmentsFromPendingByName(pendingAccounts);
            if (byName != null) return byName;

            Set<String> codes = new HashSet<>();
            if (initialAccounts != null) {
                for (Account a : initialAccounts) if (a.getCode() != null) codes.add(a.getCode().trim());
            }
            if (pendingAccounts != null) {
                for (Account a : pendingAccounts) if (a.getCode() != null) codes.add(a.getCode().trim());
            }
            // 仅有很少编码或无有效数据，回退默认
            if (codes.isEmpty()) return CODE_SEGMENTS;

            // 规范化去重
            List<String> allCodes = codes.stream().distinct().collect(Collectors.toList());
            // 记录出现过的长度
            Set<Integer> levelLengths = new HashSet<>();
            levelLengths.add(4); // 约定一级为4

            // 快速查找用
            Set<String> codeSet = new HashSet<>(allCodes);

            // 若存在前缀关系，则将前缀长度加入候选层级长度
            for (String code : allCodes) {
                if (code.length() <= 4) continue;
                for (int cut = code.length() - 1; cut > 4; cut--) {
                    String prefix = code.substring(0, cut);
                    if (codeSet.contains(prefix)) {
                        levelLengths.add(prefix.length());
                        break; // 只取最近的父级长度
                    }
                }
            }

            // 也把出现过的最大长度纳入，确保叶子层长度存在
            int maxLen = allCodes.stream().mapToInt(String::length).max().orElse(4);
            if (maxLen > 4) levelLengths.add(maxLen);

            // 排序
            List<Integer> sorted = levelLengths.stream().distinct().sorted().collect(Collectors.toList());
            // 需至少包含两层：4 和 某个更大的长度
            if (sorted.size() < 2) return CODE_SEGMENTS;

            // 构造成段
            List<Integer> segments = new ArrayList<>();
            for (int i = 0; i < sorted.size(); i++) {
                if (i == 0) {
                    if (sorted.get(i) != 4) return CODE_SEGMENTS; // 首层必须是4
                    segments.add(4); // 第一段固定为4
                } else {
                    int delta = sorted.get(i) - sorted.get(i - 1);
                    if (delta <= 0) return CODE_SEGMENTS; // 非法
                    segments.add(delta);
                }
            }
            // 转数组（segments中第一位是4，后续为增量）需要转为规范分段：首段=4，其余为differences
            int[] inferred = new int[segments.size()];
            for (int i = 0; i < segments.size(); i++) inferred[i] = segments.get(i);
            return inferred;
        } catch (Exception e) {
            return CODE_SEGMENTS;
        }
    }

    // 基于 pendingAccounts 的名称层级（按 '>' 分隔）优先推断编码分段：
    // 步骤：
    // 1) 找到名称中含有最多层级分隔符 '>' 的记录，统计最大层级 maxLevel。
    // 2) 按层级分组（1级=无'>'，2级=一个'>', ...），每层随机取一条有编码的记录，记其编码总长度 len[level]。
    // 3) 构造分段：segment[0]=len[1]；segment[i]=len[i+1]-len[i]，要求严格递增且>0。
    // 任一层无可用编码或不满足条件则返回 null，由上层回退到其他推断方式。
    private int[] inferCodeSegmentsFromPendingByName(List<Account> pendingAccounts) {
        if (pendingAccounts == null || pendingAccounts.isEmpty()) return null;

        // 统计每条记录的层级深度
        Map<Integer, List<Account>> depthToAccounts = new HashMap<>();
        int maxDepth = 0;
        for (Account acc : pendingAccounts) {
            String name = acc.getName();
            if (name == null || name.trim().isEmpty()) continue;
            int depth = 1;
            int idx = 0;
            while (true) {
                int pos = name.indexOf('>', idx);
                if (pos == -1) break;
                depth++;
                idx = pos + 1;
            }
            maxDepth = Math.max(maxDepth, depth);
            depthToAccounts.computeIfAbsent(depth, k -> new ArrayList<>()).add(acc);
        }

        if (maxDepth <= 0) return null;

        // 每个层级取一条带编码的样本，记录其编码总长度
        List<Integer> levelLens = new ArrayList<>();
        for (int level = 1; level <= maxDepth; level++) {
            List<Account> list = depthToAccounts.get(level);
            if (list == null || list.isEmpty()) return null;
            Integer len = null;
            for (Account a : list) {
                if (a.getCode() != null && !a.getCode().trim().isEmpty()) {
                    len = a.getCode().trim().length();
                    break; // 取第一条即可（若有多条随机取一条也可）
                }
            }
            if (len == null) return null; // 该层找不到可用编码
            levelLens.add(len);
        }

        // 验证严格递增
        for (int i = 1; i < levelLens.size(); i++) {
            if (levelLens.get(i) <= levelLens.get(i - 1)) {
                return null;
            }
        }

        // 构造分段
        int[] segments = new int[levelLens.size()];
        segments[0] = levelLens.get(0);
        for (int i = 1; i < levelLens.size(); i++) {
            segments[i] = levelLens.get(i) - levelLens.get(i - 1);
            if (segments[i] <= 0) return null;
        }

        return segments;
    }

    /**
     * 为多个父级科目生成子级科目
     * 支持一个科目名称在多个父级科目下同时存在的情况
     *
     * @param pendingAccount 待生成编码的科目
     * @param currentAllAccounts 当前系统中所有已知的科目
     * @param existingCodes 当前系统中所有已使用的编码集合
     * @return 生成的子级科目列表
     */
    public List<Account> generateNewAccountsForMultipleParents(Account pendingAccount, List<Account> currentAllAccounts, Set<String> existingCodes) {
        List<Account> newAccounts = new ArrayList<>();
        

        // 1. 优先使用编码推断父级科目
        List<Account> inferredParents = new ArrayList<>();
        if (pendingAccount.getCode() != null && !pendingAccount.getCode().trim().isEmpty()) {
            inferredParents = inferParentAccountsByCode(pendingAccount.getCode(), currentAllAccounts);
        }
        
        // 2. 从待完善科目名称中推断子科目名称和父级科目名称
        String childName = "";
        String expectedParentName = "";
        
        if (pendingAccount.getName() != null && pendingAccount.getName().contains(">")) {
            int lastIndex = pendingAccount.getName().lastIndexOf(">");
            childName = pendingAccount.getName().substring(lastIndex + 1);
            expectedParentName = pendingAccount.getName().substring(0, lastIndex);
        } else {
            childName = pendingAccount.getName();
        }
        
        if (childName == null || childName.trim().isEmpty()) {
            return newAccounts; // 无法提取子科目名称
        }
        
        // 3. 查找所有可能的父级科目
        List<Account> potentialParentAccounts = new ArrayList<>();
        
        // 优先使用编码推断的父级科目
        if (!inferredParents.isEmpty()) {
            potentialParentAccounts = inferredParents;
        } else if (!expectedParentName.isEmpty()) {
            // 如果编码推断失败，使用名称匹配
            potentialParentAccounts = findPotentialParentAccounts(currentAllAccounts, expectedParentName);
        } else {
            // 最后使用智能查找
            potentialParentAccounts = findPotentialParentAccounts(currentAllAccounts, expectedParentName);
        }
        
        if (potentialParentAccounts.isEmpty()) {
            return newAccounts;
        }
        

        // 4. 为每个匹配的父级科目生成一个子级科目
        for (Account parentAccount : potentialParentAccounts) {
            String parentCode = parentAccount.getCode();
            String balanceDirection = parentAccount.getBalanceDirection();
            

            // 生成唯一的子科目编码
            String newCode = findUniqueSubCode(parentCode, existingCodes);
            if (newCode != null) {
                // 构建完整的科目名称（父级名称 + 子级名称）
                String fullName = parentAccount.getName() + ">" + childName;
                
                Account newAccount = new Account(newCode, fullName, balanceDirection, parentCode, parentAccount.getName());
                newAccounts.add(newAccount);
                
                // 更新已存在的编码集合，确保后续生成的编码唯一
                existingCodes.add(newCode);
                
            } else {
            }
        }
        
        return newAccounts;
    }
    
    /**
     * 查找所有可能的父级科目
     * 支持为多个父级科目都添加子级科目的场景
     * 
     * @param currentAllAccounts 当前系统中所有已知的科目
     * @param expectedParentName 期望的父级科目名称
     * @return 可能的父级科目列表
     */
    private List<Account> findPotentialParentAccounts(List<Account> currentAllAccounts, String expectedParentName) {
        if (!expectedParentName.isEmpty()) {
            // 如果指定了期望的父级科目名称，查找匹配的父级科目
            return currentAllAccounts.stream()
                    .filter(acc -> acc.getName() != null && 
                                 normalizeString(acc.getName()).equals(normalizeString(expectedParentName)))
                    .collect(Collectors.toList());
        } else {
            // 如果没有指定父级科目名称，需要智能查找所有可能的父级科目
            // 这里实现真正的多父级科目查找逻辑
            
            // 1. 首先查找所有可以作为父级的科目（编码长度符合4-3-3-2规范）
            List<Account> potentialParents = currentAllAccounts.stream()
                    .filter(acc -> acc.getCode() != null && isParentLevelCode(acc.getCode()))
                    .collect(Collectors.toList());
            
            // 2. 如果找到了多个父级科目，返回所有可能的父级科目
            if (potentialParents.size() > 1) {
                // 按编码排序，确保生成顺序一致
                potentialParents.sort(Comparator.comparing(Account::getCode));
                return potentialParents;
            } else if (potentialParents.size() == 1) {
                // 如果只找到一个父级科目，返回这个
                return potentialParents;
            } else {
                // 如果没有找到符合规范的父级科目，尝试查找其他可能的父级科目
                List<Account> fallbackParents = currentAllAccounts.stream()
                        .filter(acc -> acc.getCode() != null && acc.getCode().length() <= 7)
                        .collect(Collectors.toList());
                
                if (fallbackParents.size() > 1) {
                    fallbackParents.sort(Comparator.comparing(Account::getCode));
                }
                
                return fallbackParents;
            }
        }
    }
    
    /**
     * 根据编码推断父级科目
     * 优先使用编码来找到正确的父级科目
     * 
     * @param childCode 子级科目编码
     * @param currentAllAccounts 当前系统中所有已知的科目
     * @return 推断出的父级科目列表
     */
    private List<Account> inferParentAccountsByCode(String childCode, List<Account> currentAllAccounts) {
        if (childCode == null || childCode.length() < 4) {
            return new ArrayList<>();
        }
        
        List<Account> inferredParents = new ArrayList<>();
        
        // 尝试不同的父级编码长度
        int[] possibleParentLengths = {4, 7, 10}; // 4-3-3-2规范中的父级长度
        
        for (int parentLength : possibleParentLengths) {
            if (childCode.length() > parentLength) {
                String potentialParentCode = childCode.substring(0, parentLength);
                
                // 查找是否存在这个父级编码
                Optional<Account> parentAccount = currentAllAccounts.stream()
                        .filter(acc -> potentialParentCode.equals(acc.getCode()))
                        .findFirst();
                
                if (parentAccount.isPresent()) {
                    inferredParents.add(parentAccount.get());
                }
            }
        }
        
        return inferredParents;
    }
    
    /**
     * 判断编码是否为父级科目编码
     * 根据4-3-3-2编码规范判断
     * 
     * @param code 科目编码
     * @return 是否为父级科目编码
     */
    private boolean isParentLevelCode(String code) {
        if (code == null) return false;
        int length = code.length();
        int[] segments = (runtimeCodeSegments != null ? runtimeCodeSegments : CODE_SEGMENTS);
        // 计算各层累计长度
        int acc = 0;
        for (int i = 0; i < segments.length - 1; i++) { // 父级：不含最后一层
            acc += segments[i];
            if (length == acc) return true;
        }
        return false;
    }



    /**
     * 生成新的科目编码和余额方向。
     * 这是科目完善功能的核心辅助方法，负责根据父科目信息和编码规范生成唯一的子科目编码。
     *
     * @param pendingAccount 待生成编码的科目 (通常只有名称，例如"其他应收款-差旅费")。
     * @param currentAllAccounts 当前系统中所有已知的科目 (包括初始科目和已生成的科目)，用于查找父科目。
     * @param existingCodes 当前系统中所有已使用的编码集合，用于确保新编码的唯一性。
     * @return 带有新编码和余额方向的 Account 对象。如果无法推断父科目或无法生成唯一编码，则返回 null。
     */
    public Account generateNewAccount(Account pendingAccount, List<Account> currentAllAccounts, Set<String> existingCodes) {
        // 1. 从待完善科目名称中推断父科目名称。
        // 这是一个简化的逻辑，实际应用中可能需要更复杂的映射表或NLP技术来识别父子关系。
        String parentName = extractParentName(pendingAccount.getName());
        if (parentName == null) {
            // 如果无法推断父科目，则无法生成编码，返回null。
            return null;
        }

        // 2. 在当前所有科目中查找父科目。
        Optional<Account> parentAccountOpt = currentAllAccounts.stream()
                .filter(acc -> acc.getName() != null && normalizeString(acc.getName()).equals(normalizeString(parentName)))
                .findFirst();

        if (parentAccountOpt.isEmpty()) {
            // 如果找不到对应的父科目，则无法生成编码，返回null。
            return null;
        }

        Account parentAccount = parentAccountOpt.get();
        String parentCode = parentAccount.getCode();
        // 3. 继承父科目的余额方向。这是根据业务规则"新生成科目的余额方向，按照其父级科目在初始化科目信息中的方向确定"实现的。
        String balanceDirection = parentAccount.getBalanceDirection(); 

        // 4. 根据父科目编码和现有编码生成唯一的子科目编码。
        String newCode = findUniqueSubCode(parentCode, existingCodes);

        if (newCode == null) {
            return null; // 无法生成唯一编码，返回null。
        }

        // 返回新生成的科目对象。
        return new Account(newCode, pendingAccount.getName(), balanceDirection, parentCode, parentAccount.getName());
    }

    /**
     * 保存完善后的科目信息到数据库
     *
     * @param accounts 完善后的科目列表
     * @return 保存成功的科目数量
     */
    public int saveCompletedAccounts(List<Account> accounts) {
        if (accounts == null || accounts.isEmpty()) {
            return 0;
        }
        
        List<Account> savedAccounts = accountRepository.saveAll(accounts);
        return savedAccounts.size();
    }

    /**
     * 从数据库加载初始化科目信息
     *
     * @return 初始化科目列表
     */
    public List<Account> loadInitialAccounts() {
        return accountRepository.findAll();
    }

    /**
     * 辅助方法：从科目名称中提取直接父科目名称。
     * 使用自定义分隔符 ">"，以避免真实名称中包含 "-" 造成歧义。
     * 示例：
     *  - "一级科目>二级科目"                    -> 返回 "一级科目"
     *  - "一级科目>二级科目>三级科目"          -> 返回 "二级科目"
     *  - "一级科目>二级科目>三级科目>四级科目" -> 返回 "三级科目"
     * @param accountName 完整科目名称（使用 “>” 分隔层级）。
     * @return 直接父科目名称；无法提取（例如没有分隔符）时返回 null。
     */
    private String extractParentName(String accountName) {
        if (accountName == null) {
            return null;
        }
        final String SEP = ">";
        int lastIdx = accountName.lastIndexOf(SEP);
        if (lastIdx <= 0) {
            return null; // 没有父级
        }
        // 截出父链 "一级>二级>三级"，再取最后一级作为父名称
        String parentChain = accountName.substring(0, lastIdx);
        int secondLastIdx = parentChain.lastIndexOf(SEP);
        if (secondLastIdx < 0) {
            return parentChain; // 只有一级父：返回 "一级科目"
        }
        return parentChain.substring(secondLastIdx + SEP.length());
    }

    /**
     * 辅助方法：根据父科目编码和现有编码生成唯一的子科目编码。
     * 遵循 4-3-3-2 编码规范，并确保递增和全局唯一性。
     *
     * @param parentCode 父科目编码。
     * @param existingCodes 当前所有已使用的编码集合，用于唯一性检查。
     * @return 唯一的子科目编码，如果无法生成（例如，所有可能的编码都已被占用）则返回 null。
     */
    private String findUniqueSubCode(String parentCode, Set<String> existingCodes) {
        // 规范化父级编码到4-3-3-2格式
        String normalizedParentCode = normalizeCodeToDefaultFormat(parentCode);
        if (normalizedParentCode == null) {
            return null;
        }
        
        int currentLevel = getCodeLevel(normalizedParentCode); // 获取当前父科目层级 (1-4)。
        // 检查父科目层级是否有效，以及是否还有下一级子科目可以生成。
        if (currentLevel == 0 || currentLevel >= CODE_SEGMENTS.length) {
            return null; // 不支持的父科目编码层级，或已是最低层级。
        }

        // 获取当前层级子编码的长度（例如，一级科目下子编码长度为3）。
        int segmentLength = CODE_SEGMENTS[currentLevel]; 

        // 尝试生成递增的子编码。从1开始，直到所有可能的组合。
        // Math.pow(10, segmentLength) - 1 确定了该层级子编码的最大值（例如，3位就是999，2位就是99）。
        for (int i = 1; i <= Math.pow(10, segmentLength) - 1; i++) {
            // 格式化子编码，确保位数正确（例如，1格式化为001）。
            String subCode = String.format("%0" + segmentLength + "d", i);
            String newFullCode = normalizedParentCode + subCode; // 拼接成完整的科目编码。

            // 检查新生成的编码是否已存在于系统中，确保唯一性。
            if (!existingCodes.contains(newFullCode)) {
                return newFullCode; // 找到唯一的编码，返回。
            }
        }
        return null; // 遍历所有可能的子编码后，仍无法生成唯一的子编码，返回null。
    }

    /**
     * 辅助方法：根据编码长度判断科目层级。
     * 遵循 4-3-3-2 规范：4位一级，7位二级，10位三级，12位四级。
     *
     * @param code 科目编码。
     * @return 层级 (1-4)，如果编码长度不符合规范则返回 0。
     */
    private int getCodeLevel(String code) {
        if (code == null) return 0;
        int length = code.length();
        // 始终使用标准的4-3-3-2规范来判断层级
        int acc = 0;
        for (int i = 0; i < CODE_SEGMENTS.length; i++) {
            acc += CODE_SEGMENTS[i];
            if (length == acc) return i + 1; // 层级从1开始
        }
        return 0; // 不匹配任何层级
    }

    /**
     * 标准化字符串，用于科目名称和编码的匹配
     * 处理空格、大小写、全角半角等差异
     * 
     * @param str 原始字符串
     * @return 标准化后的字符串
     */
    private String normalizeString(String str) {
        if (str == null) return null;
        
        return str
                .trim() // 去除首尾空格
                .toLowerCase() // 转换为小写
                .replaceAll("\\s+", "") // 去除所有空格（包括中间的空格）
                .replaceAll("　", "") // 去除全角空格
                .replaceAll("，", ",") // 全角逗号转半角
                .replaceAll("。", ".") // 全角句号转半角
                .replaceAll("（", "(") // 全角括号转半角
                .replaceAll("）", ")") // 全角括号转半角
                .replaceAll("【", "[") // 全角方括号转半角
                .replaceAll("】", "]") // 全角方括号转半角
                .replaceAll("：", ":") // 全角冒号转半角
                .replaceAll("；", ";") // 全角分号转半角
                .replaceAll("！", "!") // 全角感叹号转半角
                .replaceAll("？", "?") // 全角问号转半角
                .replaceAll("－", "-") // 全角减号转半角
                .replaceAll("—", "-") // 全角破折号转半角
                .replaceAll("…", "...") // 全角省略号转半角
                .replaceAll("'", "'") // 全角单引号转半角
                .replaceAll("\"", "\"") // 全角双引号转半角
                .replaceAll("'", "'") // 全角单引号转半角
                .replaceAll("\"", "\"") // 全角双引号转半角
                .replaceAll("￥", "¥") // 全角人民币符号转半角
                .replaceAll("％", "%") // 全角百分号转半角
                .replaceAll("＆", "&") // 全角与号转半角
                .replaceAll("＊", "*") // 全角星号转半角
                .replaceAll("＋", "+") // 全角加号转半角
                .replaceAll("＝", "=") // 全角等号转半角
                .replaceAll("＜", "<") // 全角小于号转半角
                .replaceAll("＞", ">") // 全角大于号转半角
                .replaceAll("～", "~") // 全角波浪号转半角
                .replaceAll("＠", "@") // 全角at符号转半角
                .replaceAll("＃", "#") // 全角井号转半角
                .replaceAll("＄", "$") // 全角美元符号转半角
                .replaceAll("＾", "^") // 全角脱字符转半角
                .replaceAll("｛", "{") // 全角大括号转半角
                .replaceAll("｝", "}") // 全角大括号转半角
                .replaceAll("｜", "|") // 全角竖线转半角
                .replaceAll("＼", "\\") // 全角反斜杠转半角
                .replaceAll("／", "/") // 全角斜杠转半角
                .replaceAll("｀", "`") // 全角反引号转半角
                .replaceAll("～", "~"); // 全角波浪号转半角
    }


    /**
     * 从待完善科目中提取子科目名称
     */
    private String extractChildNameFromPending(Account pendingAcc) {
        if (pendingAcc.getName() == null) {
            return null;
        }
        
        if (pendingAcc.getName().contains(">")) {
            int lastIndex = pendingAcc.getName().lastIndexOf(">");
            return pendingAcc.getName().substring(lastIndex + 1);
        } else {
            return pendingAcc.getName();
        }
    }





    private String normalizeCodeToDefault(String code, int[] fromSegments, int[] toSegments) {
        if (code == null) return null;
        try {
            // 计算当前编码属于第几层（按fromSegments）
            int accLen = 0;
            int level = 0;
            for (int i = 0; i < fromSegments.length; i++) {
                accLen += fromSegments[i];
                if (code.length() == accLen) { level = i + 1; break; }
            }
            if (level == 0) return code; // 无法匹配分段，放弃归一

            // 切分
            List<String> parts = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < level; i++) {
                int segLen = fromSegments[i];
                if (start + segLen > code.length()) return code; // 越界保护
                parts.add(code.substring(start, start + segLen));
                start += segLen;
            }

            // 每级按目标长度左侧补0（仅当目标更长时）
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.size(); i++) {
                String p = parts.get(i);
                int targetLen = i < toSegments.length ? toSegments[i] : p.length();
                if (targetLen > p.length()) {
                    sb.append(String.format("%1$" + targetLen + "s", p).replace(' ', '0'));
                } else {
                    sb.append(p);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return code;
        }
    }
}