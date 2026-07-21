package io.getbit.internal.languages;

import java.util.HashMap;
import java.util.Map;

/**
 * 微信 UI 多语言映射表
 *
 * <p>提供微信客户端 UI 文本的中英双语映射。
 * 用于在不同语言版本的微信客户端中定位 UI 控件。</p>
 *
 * <p>支持的语言：</p>
 * <ul>
 *   <li>{@code cn} - 简体中文</li>
 *   <li>{@code en} - 英文</li>
 * </ul>
 */
public class MainLanguage {

    /** 语言标识：简体中文 */
    public static final String LANG_CN = "cn";
    /** 语言标识：英文 */
    public static final String LANG_EN = "en";

    /**
     * 主界面语言映射
     * <p>Key 为内部标识（始终使用中文），Value 为各语言对应的 UI 文本</p>
     */
    private static final Map<String, Map<String, String>> MAIN_LANGUAGE = new HashMap<>();

    /**
     * 警告信息语言映射
     */
    private static final Map<String, Map<String, String>> WARNING_LANGUAGE = new HashMap<>();

    static {
        initMainLanguage();
        initWarningLanguage();
    }

    private static void initMainLanguage() {
        // 导航栏
        put("聊天", LANG_CN, "聊天");
        put("聊天", LANG_EN, "Chats");

        put("通讯录", LANG_CN, "通讯录");
        put("通讯录", LANG_EN, "Contacts");

        put("收藏", LANG_CN, "收藏");
        put("收藏", LANG_EN, "Favorites");

        put("聊天文件", LANG_CN, "聊天文件");
        put("聊天文件", LANG_EN, "Files");

        put("朋友圈", LANG_CN, "朋友圈");
        put("朋友圈", LANG_EN, "Moments");

        // 搜索
        put("搜索", LANG_CN, "搜索");
        put("搜索", LANG_EN, "Search");

        // 联系人相关
        put("新的朋友", LANG_CN, "新的朋友");
        put("新的朋友", LANG_EN, "New Friends");

        put("群聊", LANG_CN, "群聊");
        put("群聊", LANG_EN, "Group Chats");

        put("标签", LANG_CN, "标签");
        put("标签", LANG_EN, "Tags");

        put("公众号", LANG_CN, "公众号");
        put("公众号", LANG_EN, "Official Accounts");

        // 消息类型
        put("图片", LANG_CN, "图片");
        put("图片", LANG_EN, "Image");

        put("文件", LANG_CN, "文件");
        put("文件", LANG_EN, "File");

        put("语音", LANG_CN, "语音");
        put("语音", LANG_EN, "Voice");

        put("视频", LANG_CN, "视频");
        put("视频", LANG_EN, "Video");

        // 聊天操作
        put("发送", LANG_CN, "发送");
        put("发送", LANG_EN, "Send");

        put("表情", LANG_CN, "表情");
        put("表情", LANG_EN, "Stickers");

        put("截图", LANG_CN, "截图");
        put("截图", LANG_EN, "Screenshot");

        put("文件传输助手", LANG_CN, "文件传输助手");
        put("文件传输助手", LANG_EN, "File Transfer");

        // 聊天信息
        put("聊天信息", LANG_CN, "聊天信息");
        put("聊天信息", LANG_EN, "Chat Info");
    }

    private static void initWarningLanguage() {
        putWarning("版本不一致", LANG_CN, "微信版本不一致，当前版本: {}，支持版本: {}");
        putWarning("版本不一致", LANG_EN, "WeChat version mismatch, current: {}, supported: {}");
    }

    private static void put(String key, String lang, String value) {
        MAIN_LANGUAGE.computeIfAbsent(key, k -> new HashMap<>()).put(lang, value);
    }

    private static void putWarning(String key, String lang, String value) {
        WARNING_LANGUAGE.computeIfAbsent(key, k -> new HashMap<>()).put(lang, value);
    }

    /**
     * 获取主界面文本的本地化翻译
     *
     * @param key      内部标识（中文）
     * @param language 目标语言（"cn" 或 "en"）
     * @return 本地化文本，如果未找到则返回 key 本身
     */
    public static String get(String key, String language) {
        Map<String, String> langMap = MAIN_LANGUAGE.get(key);
        if (langMap == null) {
            return key;
        }
        return langMap.getOrDefault(language, key);
    }

    /**
     * 获取警告文本的本地化翻译
     *
     * @param key      内部标识（中文）
     * @param language 目标语言（"cn" 或 "en"）
     * @return 本地化文本，如果未找到则返回 key 本身
     */
    public static String getWarning(String key, String language) {
        Map<String, String> langMap = WARNING_LANGUAGE.get(key);
        if (langMap == null) {
            return key;
        }
        return langMap.getOrDefault(language, key);
    }

    private MainLanguage() {
        // 工具类，禁止实例化
    }
}
