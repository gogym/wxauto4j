package io.getbit.internal;

import java.util.Arrays;
import java.util.List;

/**
 * 微信相关参数和常量定义
 *
 * <p>对标 wxautox4 的 WxParam 类，提供可配置的全局参数。</p>
 * <p>在获取 WeChat 实例前，可通过修改静态属性来调整默认行为。</p>
 */
public class WxParams {

    // ==================== 窗口类名常量 ====================

    /** 微信主窗口类名 */
    public static final String WX_CLASS_NAME = "WeChatMainWndForPC";

    /** 独立聊天窗口类名 */
    public static final String CHAT_WND_CLASS = "ChatWnd";

    // ==================== 消息控件高度阈值 ====================

    /**
     * 系统消息控件高度（像素）
     * <p>用于判断消息类型：系统通知消息（如"你已添加了xxx为好友"）</p>
     */
    public static final int SYS_TEXT_HEIGHT = 34;

    /**
     * 时间分隔控件高度（像素）
     * <p>用于判断消息类型：时间分隔线（如"昨天 12:30"）</p>
     */
    public static final int TIME_TEXT_HEIGHT = 34;

    /**
     * 撤回消息控件高度（像素）
     * <p>用于判断消息类型：撤回提示（如"xxx撤回了一条消息"）</p>
     */
    public static final int RECALL_TEXT_HEIGHT = 34;

    // ==================== 可配置参数 ====================

    /**
     * 语言设置
     * <p>可选值：cn（简体中文）、cn_t（繁体中文）、en（英文）</p>
     */
    public static String LANGUAGE = "cn";

    /** 是否启用日志文件 */
    public static boolean ENABLE_FILE_LOGGER = true;

    /** 下载文件/图片默认保存路径 */
    public static String DEFAULT_SAVE_PATH = "./wxautox";

    /**
     * 是否启用消息哈希值用于辅助判断消息
     * <p>开启后会稍微影响性能</p>
     */
    public static boolean MESSAGE_HASH = false;

    /**
     * 头像到消息 X 偏移量（像素）
     * <p>用于消息定位、点击消息等操作</p>
     */
    public static int DEFAULT_MESSAGE_XBIAS = 51;

    /**
     * 头像到消息 Y 偏移量（像素）
     * <p>用于消息定位、点击消息等操作</p>
     */
    public static int DEFAULT_MESSAGE_YBIAS = 30;

    /**
     * 是否强制重新自动获取 X 偏移量
     * <p>如果设置为 true，则每次启动都会重新获取</p>
     */
    public static boolean FORCE_MESSAGE_XBIAS = false;

    /** 监听消息时间间隔（秒） */
    public static int LISTEN_INTERVAL = 1;

    /** 监听执行器线程池大小 */
    public static int LISTENER_EXECUTOR_WORKERS = 4;

    /** 搜索聊天对象超时时间（秒） */
    public static int SEARCH_CHAT_TIMEOUT = 2;

    /** 微信笔记加载超时时间（秒） */
    public static int NOTE_LOAD_TIMEOUT = 30;

    /** 发送文件超时时间（秒） */
    public static int SEND_FILE_TIMEOUT = 10;

    /**
     * 监听窗口尺寸
     * <p>由于 4.x 版本客户端 UI 机制是显示的部分才注册 UIA 控件，
     * 所以尽可能拉大窗口显示更多消息来提高判断容错</p>
     */
    public static int CHAT_WINDOW_WIDTH = 800;
    public static int CHAT_WINDOW_HEIGHT = 6000;

    /**
     * 输入内容相似度阈值
     * <p>用于判断输入框中的内容是否是要发送的内容，避免发送错误内容。
     * 因为存在特殊符号转码问题，可能编辑框内容无法 100% 与实际传入的字符串相等，
     * 所以达到相似度即通过校验，才触发发送</p>
     */
    public static double SEND_CONTENT_RATIO = 0.9;

    /** GetNextNewMessage 方法最大获取数量 */
    public static int GET_NEXT_MAX_QUANTITY = 30;

    /** GetNextNewMessage 方法最长获取时间（秒） */
    public static int GET_NEXT_MAX_RUNTIME = 10;

    /** 特殊聊天会话名称列表 */
    public static List<String> SPECIAL_SESSION_NAME = Arrays.asList(
            "公众号", "折叠的聊天", "QQ邮箱提醒", "服务号"
    );

    /** 回调函数结束标识 */
    public static final String CALLBACK_STOP_SIGN = "stop";

    /** @成员输入间隔时间（秒） */
    public static double INPUT_AT_INTERVAL = 0.5;

    /**
     * 默认聊天表情列表
     * <p>包含常用的微信表情符号，可在发送消息时使用</p>
     */
    public static List<String> DEFAULT_STICKERS = Arrays.asList(
            // 基础表情
            "[微笑]", "[撇嘴]", "[色]", "[发呆]", "[得意]", "[流泪]", "[害羞]", "[闭嘴]", "[睡]", "[大哭]",
            "[尴尬]", "[发怒]", "[调皮]", "[呲牙]", "[惊讶]", "[难过]", "[囧]", "[抓狂]", "[吐]", "[偷笑]",
            "[愉快]", "[白眼]", "[傲慢]", "[困]", "[惊恐]", "[憨笑]", "[悠闲]", "[咒骂]", "[疑问]", "[嘘]",
            "[晕]", "[衰]", "[骷髅]", "[敲打]", "[再见]", "[擦汗]", "[抠鼻]", "[鼓掌]", "[坏笑]", "[右哼哼]",
            // 表情和手势
            "[鄙视]", "[委屈]", "[快哭了]", "[阴险]", "[亲亲]", "[可怜]", "[笑脸]", "[生病]", "[脸红]", "[破涕为笑]",
            "[恐惧]", "[失望]", "[无语]", "[嘿哈]", "[捂脸]", "[奸笑]", "[机智]", "[皱眉]", "[耶]", "[吃瓜]",
            "[加油]", "[汗]", "[天啊]", "[Emm]", "[社会社会]", "[旺柴]", "[好的]", "[打脸]", "[哇]", "[翻白眼]",
            // 符号和物品
            "[666]", "[让我看看]", "[叹气]", "[苦涩]", "[裂开]", "[嘴唇]", "[爱心]", "[心碎]", "[拥抱]", "[强]",
            "[弱]", "[握手]", "[胜利]", "[抱拳]", "[勾引]", "[拳头]", "[OK]", "[合十]", "[啤酒]", "[咖啡]",
            // 庆祝和节日
            "[蛋糕]", "[玫瑰]", "[凋谢]", "[菜刀]", "[炸弹]", "[便便]", "[月亮]", "[太阳]", "[庆祝]", "[礼物]",
            "[红包]", "[發]", "[福]", "[烟花]", "[爆竹]", "[猪头]", "[跳跳]", "[发抖]", "[转圈]", "[天啊]",
            // 其他
            "[强]", "[汗]", "[握手]"
    );

    private WxParams() {
        // 工具类，禁止实例化
    }
}
