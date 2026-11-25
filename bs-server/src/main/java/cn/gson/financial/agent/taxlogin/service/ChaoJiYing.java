package cn.gson.financial.agent.taxlogin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public class ChaoJiYing {

	/**
	 * 超级鹰配置信息
	 * TODO: 请根据实际情况修改以下配置
	 */
	public static final String USERNAME = "5689426";      // 超级鹰用户名
	public static final String PASSWORD = "Aa123456";     // 超级鹰密码（或MD5）
	public static final String SOFT_ID = "977309";        // 软件ID
	public static final String CODE_TYPE_CLICK = "9800";  // 点选验证码类型
	public static final String CODE_TYPE_COMMON = "2004";
	public static final String LEN_MIN = "";            // 最小位数（点选验证码通常不需要）

	/**
	 * 字符串MD5加密
	 * @param s 原始字符串
	 * @return  加密后字符串
	 */
	public final static String MD5(String s) {
		char hexDigits[] = { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
				'A', 'B', 'C', 'D', 'E', 'F' };
		try {
			byte[] btInput = s.getBytes();
			MessageDigest mdInst = MessageDigest.getInstance("MD5");
			mdInst.update(btInput);
			byte[] md = mdInst.digest();
			int j = md.length;
			char str[] = new char[j * 2];
			int k = 0;
			for (int i = 0; i < j; i++) {
				byte byte0 = md[i];
				str[k++] = hexDigits[byte0 >>> 4 & 0xf];
				str[k++] = hexDigits[byte0 & 0xf];
			}
			return new String(str);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * 通用POST方法
	 * @param url 		请求URL
	 * @param param 	请求参数，如：username=test&password=1
	 * @return			response
	 * @throws IOException
	 */
	public static String httpRequestData(String url, String param)
			throws IOException {
		URL u;
		HttpURLConnection con = null;
		OutputStreamWriter osw;
		StringBuffer buffer = new StringBuffer();

		u = new URL(url);
		con = (HttpURLConnection) u.openConnection();
		con.setRequestMethod("POST");
		con.setDoOutput(true);
		con.setDoInput(true);
		con.setRequestProperty("Content-Type",
				"application/x-www-form-urlencoded");

		osw = new OutputStreamWriter(con.getOutputStream(), "UTF-8");
		osw.write(param);
		osw.flush();
		osw.close();

		BufferedReader br = new BufferedReader(new InputStreamReader(con
				.getInputStream(), "UTF-8"));
		String temp;
		while ((temp = br.readLine()) != null) {
			buffer.append(temp);
			buffer.append("\n");
		}

		return buffer.toString();
	}

	/**
	 * 识别图片_按图片base64字符串 请提前参考base64注意事项 http://www.chaojiying.com/api-46.html
	 * @param username		用户名
	 * @param password		密码
	 * @param softid		软件ID
	 * @param codetype		图片类型
	 * @param len_min		最小位数
	 * @param file_base64	图片base64字符串
	 * @param str_debug		调试字符串（用于9801类型，8a指令格式：{8a:查,找,的,字/8a}）
	 * @return
	 * @throws IOException
	 */
	public static String PostPic_base64(String username, String password, String softid, String codetype, String len_min, String file_base64, String str_debug) {

		// URL编码
		try {
			file_base64 = URLEncoder.encode(file_base64,"UTF-8");
		} catch (Exception e) {
			return "";
		}

		// 构建参数
		String param;
		if (str_debug != null && !str_debug.trim().isEmpty()) {
			// 如果有 str_debug 参数，需要URL编码
			try {
				String encodedStrDebug = URLEncoder.encode(str_debug, "UTF-8");
				param = String.format("user=%s&pass=%s&softid=%s&codetype=%s&len_min=%s&file_base64=%s&str_debug=%s",
						username, password, softid, codetype, len_min, file_base64, encodedStrDebug);
			} catch (Exception e) {
				return "";
			}
		} else {
			param = String.format("user=%s&pass=%s&softid=%s&codetype=%s&len_min=%s&file_base64=%s",
					username, password, softid, codetype, len_min, file_base64);
		}

		String result;
		try {
			result = ChaoJiYing.httpRequestData(
					"http://upload.chaojiying.net/Upload/Processing.php", param);
		} catch (IOException e) {
			result = "未知问题";
		}
		return result;
	}

	/**
	 * 识别图片_按图片base64字符串（不带str_debug参数，兼容旧版本）
	 */
	public static String PostPic_base64(String username, String password, String softid, String codetype, String len_min, String file_base64) {
		return PostPic_base64(username, password, softid, codetype, len_min, file_base64, null);
	}

	/**
	 * 构建9801类型的8a指令字符串
	 * 格式：{8a:查,找,的,字/8a}
	 * 注意：大括号、冒号、逗号、斜杠、8a 都必须是半角字符
	 * @param words 要查找的文字，例如："查找的字" 会转换为 "查,找,的,字"
	 * @return 8a指令字符串，例如：{8a:查,找,的,字/8a}
	 */
	public static String build8aInstruction(String words) {
		if (words == null || words.trim().isEmpty()) {
			throw new IllegalArgumentException("要查找的文字不能为空");
		}
		// 将每个字符用逗号分隔，例如："查找的字" -> "查,找,的,字"
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < words.length(); i++) {
			if (i > 0) {
				sb.append(",");
			}
			sb.append(words.charAt(i));
		}
		// 构建8a指令：{8a:查,找,的,字/8a}（注意都是半角字符）
		return "{8a:" + sb.toString() + "/8a}";
	}

	/**
	 * 使用默认配置识别点选验证码（base64格式）
	 * @param file_base64	图片base64字符串（不含 data:image/png;base64, 前缀）
	 * @param wordsToFind	要查找的文字（用于9801类型，例如："查找的字"）
	 * @return 超级鹰返回的JSON字符串
	 */
	public static String solveClickCaptchaBase64(String file_base64, String wordsToFind) {
		String strDebug = null;
		if (wordsToFind != null && !wordsToFind.trim().isEmpty()) {
			// 构建8a指令
			strDebug = build8aInstruction(wordsToFind);
		}
		return PostPic_base64(USERNAME, PASSWORD, SOFT_ID, CODE_TYPE_CLICK, LEN_MIN, file_base64, strDebug);
	}

	/**
	 * 使用默认配置识别点选验证码（base64格式，不带8a指令，使用9800类型）
	 * @param file_base64	图片base64字符串（不含 data:image/png;base64, 前缀）
	 * @return 超级鹰返回的JSON字符串
	 */
	public static String solveClickCaptchaBase64(String file_base64) {
		// 使用9800类型，不使用8a指令
		return PostPic_base64(USERNAME, PASSWORD, SOFT_ID, CODE_TYPE_CLICK, LEN_MIN, file_base64, null);
	}

	public static String solveClickCaptchaBase64Common(String file_base64) {
		// 使用9800类型，不使用8a指令
		return PostPic_base64(USERNAME, PASSWORD, SOFT_ID, CODE_TYPE_COMMON, LEN_MIN, file_base64, null);
	}

	/**
	 * 识别提示图片中的文字和坐标（使用9800类型）
	 * @param file_base64	提示图片的base64字符串（不含前缀）
	 * @return 识别出的文字，例如："查找的字"
	 * @throws RuntimeException 如果识别失败
	 */
	public static String recognizeTextFromImage(String file_base64) {
		// 使用9800类型识别提示图片中的文字
		String resultJson = solveClickCaptchaBase64(file_base64);

		// 解析JSON结果
		ObjectMapper mapper = new ObjectMapper();
		JsonNode root;
		try {
			root = mapper.readTree(resultJson);
		} catch (Exception e) {
			throw new RuntimeException("解析超级鹰返回的JSON失败: " + e.getMessage() + ", 返回内容: " + resultJson, e);
		}

		// 检查错误码
		int errNo = root.path("err_no").asInt(-1);
		if (errNo != 0) {
			String errStr = root.path("err_str").asText("未知错误");
			throw new RuntimeException("超级鹰文字识别失败: err_no=" + errNo + ", err_str=" + errStr);
		}

		// 获取识别结果：pic_str 包含识别的文字和坐标
		// 对于9800类型识别文字，pic_str 格式可能是 "文字内容|x1,y1|x2,y2|..."
		// 或者直接返回文字内容
		String picStr = root.path("pic_str").asText();
		if (picStr == null || picStr.trim().isEmpty()) {
			throw new RuntimeException("超级鹰返回的文字识别结果为空");
		}

		// 提取文字部分（如果有坐标，用|分隔，第一部分是文字）
		String[] parts = picStr.split("\\|");
		String text = parts[0].trim();

		if (text.isEmpty()) {
			throw new RuntimeException("无法从识别结果中提取文字, 原始数据: " + picStr);
		}

		return text;
	}

	/**
	 * 识别提示图片中的文字，并按X轴坐标排序返回（使用9800类型）
	 * @param file_base64	提示图片的base64字符串（不含前缀）
	 * @return 按X轴坐标排序后的文字字符列表（通常为4个字）
	 * @throws RuntimeException 如果识别失败
	 */
	public static List<Character> recognizeTextWithCoordinates(String file_base64) {
		// 使用9800类型识别提示图片中的文字
		String resultJson = solveClickCaptchaBase64(file_base64);

		// 解析JSON结果
		ObjectMapper mapper = new ObjectMapper();
		JsonNode root;
		try {
			root = mapper.readTree(resultJson);
		} catch (Exception e) {
			throw new RuntimeException("解析超级鹰返回的JSON失败: " + e.getMessage() + ", 返回内容: " + resultJson, e);
		}

		// 检查错误码
		int errNo = root.path("err_no").asInt(-1);
		if (errNo != 0) {
			String errStr = root.path("err_str").asText("未知错误");
			throw new RuntimeException("超级鹰文字识别失败: err_no=" + errNo + ", err_str=" + errStr);
		}

		// 获取识别结果：pic_str 格式为 "字符1,x1,y1|字符2,x2,y2|..."
		// 例如：星,512,59|齍,505,272|荷,412,250|寿,51,275|泉,427,152|溪,401,61
		String picStr = root.path("pic_str").asText();
		if (picStr == null || picStr.trim().isEmpty()) {
			throw new RuntimeException("超级鹰返回的文字识别结果为空");
		}

		// 解析字符和坐标：每个部分格式为 "字符,x坐标,y坐标"
		List<Object[]> charWithCoords = new ArrayList<>(); // [字符, x坐标, y坐标]
		String[] parts = picStr.split("\\|");
		for (String part : parts) {
			String[] items = part.split(",");
			if (items.length >= 3) {
				try {
					String character = items[0].trim();
					int x = Integer.parseInt(items[1].trim());
					int y = Integer.parseInt(items[2].trim());
					if (character.length() > 0) {
						charWithCoords.add(new Object[]{character.charAt(0), x, y});
					}
				} catch (NumberFormatException e) {
					// 跳过格式不正确的数据
				}
			}
		}

		if (charWithCoords.isEmpty()) {
			throw new RuntimeException("无法从识别结果中解析出字符和坐标, 原始数据: " + picStr);
		}

		// 按X轴坐标排序
		charWithCoords.sort((a, b) -> Integer.compare((Integer) a[1], (Integer) b[1]));

		// 提取排序后的字符列表
		List<Character> sortedChars = new ArrayList<>();
		for (Object[] item : charWithCoords) {
			sortedChars.add((Character) item[0]);
		}

		return sortedChars;
	}

	/**
	 * 识别点选验证码并返回坐标列表（相对图片左上角的坐标，使用9800类型）
	 * @param file_base64	图片base64字符串（不含前缀）
	 * @param wordsToFind	要查找的文字（例如："查找的字"，用于按顺序匹配坐标）
	 * @return 坐标列表，每个元素为 [x, y]，坐标是相对于图片左上角的，按照文字顺序排列
	 * @throws RuntimeException 如果识别失败或解析失败
	 */
	public static List<int[]> getClickCaptchaCoordinates(String file_base64, String wordsToFind) {
		// 使用9800类型识别验证码（不使用8a指令）
		String resultJson = solveClickCaptchaBase64(file_base64);

		// 解析JSON结果
		ObjectMapper mapper = new ObjectMapper();
		JsonNode root;
		try {
			root = mapper.readTree(resultJson);
		} catch (Exception e) {
			throw new RuntimeException("解析超级鹰返回的JSON失败: " + e.getMessage() + ", 返回内容: " + resultJson, e);
		}

		// 检查错误码
		int errNo = root.path("err_no").asInt(-1);
		if (errNo != 0) {
			String errStr = root.path("err_str").asText("未知错误");
			throw new RuntimeException("超级鹰验证码识别失败: err_no=" + errNo + ", err_str=" + errStr);
		}

		// 获取识别结果：pic_str 格式可能是 "字符1,x1,y1|字符2,x2,y2|..." 或 "x1,y1|x2,y2|..."
		String picStr = root.path("pic_str").asText();
		if (picStr == null || picStr.trim().isEmpty()) {
			throw new RuntimeException("超级鹰返回的坐标字符串为空");
		}

		// 解析坐标字符串
		List<int[]> points = new ArrayList<>();
		String[] pairs = picStr.split("\\|");
		for (String pair : pairs) {
			String[] items = pair.split(",");
			if (items.length >= 3) {
				// 格式为 "字符,x坐标,y坐标"
				try {
					int x = Integer.parseInt(items[1].trim());
					int y = Integer.parseInt(items[2].trim());
					points.add(new int[]{x, y});
				} catch (NumberFormatException e) {
					// 跳过格式不正确的坐标
				}
			} else if (items.length == 2) {
				// 格式为 "x坐标,y坐标"
				try {
					int x = Integer.parseInt(items[0].trim());
					int y = Integer.parseInt(items[1].trim());
					points.add(new int[]{x, y});
				} catch (NumberFormatException e) {
					// 跳过格式不正确的坐标
				}
			}
		}

		if (points.isEmpty()) {
			throw new RuntimeException("超级鹰返回的坐标解析后为空, 原始数据: " + picStr);
		}

		return points;
	}

	/**
	 * 识别点选验证码并返回坐标列表（相对图片左上角的坐标，不带8a指令）
	 * @param file_base64	图片base64字符串（不含 data:image/png;base64, 前缀）
	 * @return 坐标列表，每个元素为 [x, y]，坐标是相对于图片左上角的
	 * @throws RuntimeException 如果识别失败或解析失败
	 */
	public static List<int[]> getClickCaptchaCoordinates(String file_base64) {
		return getClickCaptchaCoordinates(file_base64, null);
	}

	/**
	 * 识别点选验证码（封装方法，接收两个图片）
	 * @param tipImageBase64	提示图片的base64字符串（不含前缀，PNG格式，显示"请依次点击"的文字）
	 * @param selectImageBase64	选择图片的base64字符串（不含前缀，JPEG格式，包含需要点击的文字）
	 * @return 按X轴坐标排序后的坐标列表，每个元素为 [x, y]，坐标是相对于选择图片左上角的
	 * @throws RuntimeException 如果识别失败或解析失败
	 */
	public static List<int[]> solveClickCaptchaWithTwoImages(String tipImageBase64, String selectImageBase64) {
		// 第一步：识别提示图片，获取按X轴排序的文字
		List<Character> sortedChars = recognizeTextWithCoordinates(tipImageBase64);
		String wordsToFind = sortedChars.stream()
				.map(String::valueOf)
				.collect(java.util.stream.Collectors.joining());
		System.out.println("识别到的需要点击的文字（按X轴排序）: " + wordsToFind);

		// 第二步：识别选择图片中这些文字的坐标
		String resultJson = solveClickCaptchaBase64(selectImageBase64);

		// 解析JSON结果
		ObjectMapper mapper = new ObjectMapper();
		JsonNode root;
		try {
			root = mapper.readTree(resultJson);
		} catch (Exception e) {
			throw new RuntimeException("解析超级鹰返回的JSON失败: " + e.getMessage() + ", 返回内容: " + resultJson, e);
		}

		// 检查错误码
		int errNo = root.path("err_no").asInt(-1);
		if (errNo != 0) {
			String errStr = root.path("err_str").asText("未知错误");
			throw new RuntimeException("超级鹰验证码识别失败: err_no=" + errNo + ", err_str=" + errStr);
		}

		// 获取识别结果：pic_str 格式为 "字符1,x1,y1|字符2,x2,y2|..."
		// 例如：星,512,59|齍,505,272|荷,412,250|寿,51,275|泉,427,152|溪,401,61
		String picStr = root.path("pic_str").asText();
		if (picStr == null || picStr.trim().isEmpty()) {
			throw new RuntimeException("超级鹰返回的坐标字符串为空");
		}

		// 解析选择图片中的字符和坐标，建立字符到坐标的映射
		java.util.Map<Character, int[]> charToCoordMap = new java.util.HashMap<>();
		String[] pairs = picStr.split("\\|");
		for (String pair : pairs) {
			String[] items = pair.split(",");
			if (items.length >= 3) {
				// 格式为 "字符,x坐标,y坐标"
				try {
					String character = items[0].trim();
					int x = Integer.parseInt(items[1].trim());
					int y = Integer.parseInt(items[2].trim());
					if (character.length() > 0) {
						charToCoordMap.put(character.charAt(0), new int[]{x, y});
					}
				} catch (NumberFormatException e) {
					// 跳过格式不正确的坐标
				}
			}
		}

		if (charToCoordMap.isEmpty()) {
			throw new RuntimeException("超级鹰返回的坐标解析后为空, 原始数据: " + picStr);
		}

		// 第三步：根据提示图片排序后的文字顺序，从选择图片的结果中匹配对应的坐标
		List<int[]> orderedPoints = new ArrayList<>();
		for (Character ch : sortedChars) {
			int[] coord = charToCoordMap.get(ch);
			if (coord != null) {
				orderedPoints.add(coord);
			} else {
				System.out.println("警告：在选择图片中未找到字符 '" + ch + "' 的坐标");
			}
		}

		if (orderedPoints.isEmpty()) {
			throw new RuntimeException("无法根据提示图片的文字顺序匹配到坐标");
		}

		return orderedPoints;
	}
}
