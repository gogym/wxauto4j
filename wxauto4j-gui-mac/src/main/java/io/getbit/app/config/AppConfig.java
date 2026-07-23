package io.getbit.app.config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全局应用配置
 *
 * <p>对标 SiverWXbot_plus 的 config.json，包含所有配置项。
 * 配置存储在 {@code ~/.wxauto4j/config.json}。</p>
 */
public class AppConfig {

    // ==================== 微信数据库密钥 ====================

    /** 微信 SQLCipher raw key（64字符 hex），通过 Frida 提取得到 */
    private String wxRawKey = "";

    /** 微信数据目录（xwechat_files），用于定位加密数据库 */
    private String wxDataDir = System.getProperty("user.home")
            + "/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files";

    /** 解密后数据库输出目录 */
    private String wxDecryptedDbDir = "/tmp/wx_decrypted_java";

    // ==================== AI 接口 ====================

    /** AI 接口配置列表 */
    private List<ApiConfig> apiConfigs = new ArrayList<>();

    /** 当前使用的接口索引（0-based） */
    private int apiIndex = 0;

    // ==================== 管理员 ====================

    /** 管理员昵称，可发送管理命令 */
    private String admin = "文件传输助手";

    // ==================== 监听模式 ====================

    /** false=白名单模式，true=黑名单（全局）模式 */
    private boolean allListenSwitch = false;

    /** 全局监听模式下是否过滤免打扰会话 */
    private boolean allListenFilterMute = true;

    /** 私聊只监听不 AI 回复 */
    private boolean chatListenOnly = false;

    /** 白名单/黑名单用户列表 */
    private List<String> listenList = new ArrayList<>();

    // ==================== 群组 ====================

    /** 监听的群聊列表 */
    private List<String> group = new ArrayList<>();

    /** 群组专属接口映射 {"群名": 接口索引} */
    private Map<String, Integer> groupApiMap = new HashMap<>();

    /** 群组专属 Prompt 映射 {"群名": "Prompt文件名"} */
    private Map<String, String> groupPromptMap = new HashMap<>();

    /** 群聊监听/回复总开关 */
    private boolean groupSwitch = false;

    /** 群聊只监听不 AI 回复 */
    private boolean groupListenOnly = false;

    /** 是否仅在被 @ 时回复群消息 */
    private boolean groupReplyAt = false;

    /** 群聊回复时是否 @ 发言人 */
    private boolean groupReplyAtMsg = true;

    /** 群聊回复时是否引用原消息 */
    private boolean groupReplyQuote = false;

    /** 是否开启群新人欢迎语 */
    private boolean groupWelcome = false;

    /** 欢迎语触发概率（0.0-1.0） */
    private double groupWelcomeRandom = 1.0;

    /** 群新人欢迎语内容 */
    private String groupWelcomeMsg = "欢迎新朋友！";

    // ==================== 私聊专属 ====================

    /** 私聊用户专属 Prompt 映射 {"用户昵称": "Prompt文件名"} */
    private Map<String, String> chatPromptMap = new HashMap<>();

    /** 私聊用户专属接口映射 {"用户昵称": 接口索引} */
    private Map<String, Integer> chatApiMap = new HashMap<>();

    // ==================== Prompt ====================

    /** 全局默认 Prompt 文件名（不含 .md） */
    private String defaultPrompt = "默认";

    // ==================== 关键词 ====================

    /** 私聊关键词回复开关 */
    private boolean chatKeywordSwitch = false;

    /** 群聊关键词回复开关 */
    private boolean groupKeywordSwitch = false;

    /** 群聊关键词回复是否仅在被 @ 时触发 */
    private boolean groupKeywordAtOnly = false;

    /** 关键词→回复内容映射 */
    private Map<String, String> keywordDict = new HashMap<>();

    // ==================== 自定义转发 ====================

    /** 自定义转发总开关 */
    private boolean customForwardSwitch = false;

    /** 自定义转发规则列表 */
    private List<ForwardRule> customForwardList = new ArrayList<>();

    // ==================== 定时消息 ====================

    /** 定时消息开关 */
    private boolean scheduledMsgSwitch = false;

    /** 定时消息任务列表 */
    private List<ScheduledMsg> scheduledMsgList = new ArrayList<>();

    /** 随机定时消息开关 */
    private boolean randomMsgSwitch = false;

    /** 随机定时消息任务列表 */
    private List<RandomMsg> randomMsgList = new ArrayList<>();

    // ==================== 朋友圈 ====================

    /** 定时朋友圈开关 */
    private boolean scheduledMomentsSwitch = false;

    /** 定时朋友圈任务列表 */
    private List<ScheduledMoment> scheduledMomentsList = new ArrayList<>();

    /** 随机朋友圈点赞开关 */
    private boolean momentsLikeSwitch = false;

    /** 随机点赞最小间隔（分钟） */
    private int momentsLikeMin = 60;

    /** 随机点赞最大间隔（分钟） */
    private int momentsLikeMax = 120;

    /** 随机定时朋友圈开关 */
    private boolean randomMomentsSwitch = false;

    /** 随机定时朋友圈任务列表 */
    private List<ScheduledMoment> randomMomentsList = new ArrayList<>();

    // ==================== 每日启停 ====================

    /** 是否开启每日定时启停机器人 */
    private boolean everydayStartStopBotSwitch = false;

    /** 每日自动启动时间（HH:MM） */
    private String everydayStartBotTime = "08:00";

    /** 每日自动停止时间（HH:MM） */
    private String everydayStopBotTime = "23:00";

    // ==================== 新好友管理 ====================

    /** 是否自动通过新好友请求 */
    private boolean newFriendSwitch = false;

    /** 通过新好友后是否自动打招呼 */
    private boolean newFriendReplySwitch = false;

    /** 打招呼消息列表（文字或图片绝对路径） */
    private List<String> newFriendMsg = new ArrayList<>();

    /** 检查新好友请求的最小间隔（秒） */
    private int newFriendCheckMin = 60;

    /** 检查新好友请求的最大间隔（秒） */
    private int newFriendCheckMax = 300;

    /** 通过好友后设置备注时是否使用对方昵称作为主体 */
    private boolean newFriendRemarkUseNickname = true;

    /** 备注前缀 */
    private String newFriendRemarkPrefix = "";

    /** 备注前缀后是否追加时间戳 */
    private boolean newFriendRemarkPrefixTimestamp = false;

    /** 备注后缀 */
    private String newFriendRemarkSuffix = "_机器人备注";

    /** 备注后缀后是否追加时间戳 */
    private boolean newFriendRemarkSuffixTimestamp = false;

    /** 通过好友后自动设置的标签列表 */
    private List<String> newFriendTags = new ArrayList<>();

    // ==================== 对话记忆 ====================

    /** 是否开启对话记忆 */
    private boolean memorySwitch = true;

    /** 单窗口最多存储的消息条数 */
    private int memoryMaxCount = 3000;

    /** AI 请求时带入的历史消息条数 */
    private int memoryContextCount = 1000;

    // ==================== 回复延迟 ====================

    /** 是否启用发送延迟（模拟人工操作） */
    private boolean replyDelaySwitch = true;

    /** 发送延迟最小秒数 */
    private int replyDelayMin = 1;

    /** 发送延迟最大秒数 */
    private int replyDelayMax = 5;

    // ==================== AI 回复清理 ====================

    /** 是否清理模型回复中的  思考过程 */
    private boolean cleanAiReplySwitch = true;

    // ==================== 图片识别 ====================

    /** 私聊图片识别开关 */
    private boolean chatImageRecognitionSwitch = false;

    /** 私聊图片识别使用的接口索引 */
    private int chatImageRecognitionApi = 0;

    /** 群组图片识别开关 */
    private boolean groupImageRecognitionSwitch = false;

    /** 群组图片识别使用的接口索引 */
    private int groupImageRecognitionApi = 0;

    // ==================== 接口错误回复 ====================

    /** 调用 AI 接口失败时发送的固定回复内容 */
    private String apiErrorReply = "在忙，我稍后回复您";

    /** 接口失败固定回复是否对同一用户只发送一次 */
    private boolean apiErrorReplyOnce = false;

    // ==================== 拆分多条回复 ====================

    /** 私聊拆分多条回复开关 */
    private boolean chatSplitReplySwitch = false;

    /** 私聊拆分回复单条最大字数 */
    private int chatSplitMaxChars = 100;

    /** 私聊拆分回复最多条数 */
    private int chatSplitMaxCount = 4;

    /** 群聊拆分多条回复开关 */
    private boolean groupSplitReplySwitch = false;

    /** 群聊拆分回复单条最大字数 */
    private int groupSplitMaxChars = 100;

    /** 群聊拆分回复最多条数 */
    private int groupSplitMaxCount = 4;

    // ==================== Getter / Setter ====================

    public String getWxRawKey() { return wxRawKey; }
    public void setWxRawKey(String wxRawKey) { this.wxRawKey = wxRawKey; }

    public String getWxDataDir() { return wxDataDir; }
    public void setWxDataDir(String wxDataDir) { this.wxDataDir = wxDataDir; }

    public String getWxDecryptedDbDir() { return wxDecryptedDbDir; }
    public void setWxDecryptedDbDir(String wxDecryptedDbDir) { this.wxDecryptedDbDir = wxDecryptedDbDir; }

    public List<ApiConfig> getApiConfigs() { return apiConfigs; }
    public void setApiConfigs(List<ApiConfig> apiConfigs) { this.apiConfigs = apiConfigs; }

    public int getApiIndex() { return apiIndex; }
    public void setApiIndex(int apiIndex) { this.apiIndex = apiIndex; }

    public String getAdmin() { return admin; }
    public void setAdmin(String admin) { this.admin = admin; }

    public boolean isAllListenSwitch() { return allListenSwitch; }
    public void setAllListenSwitch(boolean allListenSwitch) { this.allListenSwitch = allListenSwitch; }

    public boolean isAllListenFilterMute() { return allListenFilterMute; }
    public void setAllListenFilterMute(boolean allListenFilterMute) { this.allListenFilterMute = allListenFilterMute; }

    public boolean isChatListenOnly() { return chatListenOnly; }
    public void setChatListenOnly(boolean chatListenOnly) { this.chatListenOnly = chatListenOnly; }

    public List<String> getListenList() { return listenList; }
    public void setListenList(List<String> listenList) { this.listenList = listenList; }

    public List<String> getGroup() { return group; }
    public void setGroup(List<String> group) { this.group = group; }

    public Map<String, Integer> getGroupApiMap() { return groupApiMap; }
    public void setGroupApiMap(Map<String, Integer> groupApiMap) { this.groupApiMap = groupApiMap; }

    public Map<String, String> getGroupPromptMap() { return groupPromptMap; }
    public void setGroupPromptMap(Map<String, String> groupPromptMap) { this.groupPromptMap = groupPromptMap; }

    public boolean isGroupSwitch() { return groupSwitch; }
    public void setGroupSwitch(boolean groupSwitch) { this.groupSwitch = groupSwitch; }

    public boolean isGroupListenOnly() { return groupListenOnly; }
    public void setGroupListenOnly(boolean groupListenOnly) { this.groupListenOnly = groupListenOnly; }

    public boolean isGroupReplyAt() { return groupReplyAt; }
    public void setGroupReplyAt(boolean groupReplyAt) { this.groupReplyAt = groupReplyAt; }

    public boolean isGroupReplyAtMsg() { return groupReplyAtMsg; }
    public void setGroupReplyAtMsg(boolean groupReplyAtMsg) { this.groupReplyAtMsg = groupReplyAtMsg; }

    public boolean isGroupReplyQuote() { return groupReplyQuote; }
    public void setGroupReplyQuote(boolean groupReplyQuote) { this.groupReplyQuote = groupReplyQuote; }

    public boolean isGroupWelcome() { return groupWelcome; }
    public void setGroupWelcome(boolean groupWelcome) { this.groupWelcome = groupWelcome; }

    public double getGroupWelcomeRandom() { return groupWelcomeRandom; }
    public void setGroupWelcomeRandom(double groupWelcomeRandom) { this.groupWelcomeRandom = groupWelcomeRandom; }

    public String getGroupWelcomeMsg() { return groupWelcomeMsg; }
    public void setGroupWelcomeMsg(String groupWelcomeMsg) { this.groupWelcomeMsg = groupWelcomeMsg; }

    public Map<String, String> getChatPromptMap() { return chatPromptMap; }
    public void setChatPromptMap(Map<String, String> chatPromptMap) { this.chatPromptMap = chatPromptMap; }

    public Map<String, Integer> getChatApiMap() { return chatApiMap; }
    public void setChatApiMap(Map<String, Integer> chatApiMap) { this.chatApiMap = chatApiMap; }

    public String getDefaultPrompt() { return defaultPrompt; }
    public void setDefaultPrompt(String defaultPrompt) { this.defaultPrompt = defaultPrompt; }

    public boolean isChatKeywordSwitch() { return chatKeywordSwitch; }
    public void setChatKeywordSwitch(boolean chatKeywordSwitch) { this.chatKeywordSwitch = chatKeywordSwitch; }

    public boolean isGroupKeywordSwitch() { return groupKeywordSwitch; }
    public void setGroupKeywordSwitch(boolean groupKeywordSwitch) { this.groupKeywordSwitch = groupKeywordSwitch; }

    public boolean isGroupKeywordAtOnly() { return groupKeywordAtOnly; }
    public void setGroupKeywordAtOnly(boolean groupKeywordAtOnly) { this.groupKeywordAtOnly = groupKeywordAtOnly; }

    public Map<String, String> getKeywordDict() { return keywordDict; }
    public void setKeywordDict(Map<String, String> keywordDict) { this.keywordDict = keywordDict; }

    public boolean isCustomForwardSwitch() { return customForwardSwitch; }
    public void setCustomForwardSwitch(boolean customForwardSwitch) { this.customForwardSwitch = customForwardSwitch; }

    public List<ForwardRule> getCustomForwardList() { return customForwardList; }
    public void setCustomForwardList(List<ForwardRule> customForwardList) { this.customForwardList = customForwardList; }

    public boolean isScheduledMsgSwitch() { return scheduledMsgSwitch; }
    public void setScheduledMsgSwitch(boolean scheduledMsgSwitch) { this.scheduledMsgSwitch = scheduledMsgSwitch; }

    public List<ScheduledMsg> getScheduledMsgList() { return scheduledMsgList; }
    public void setScheduledMsgList(List<ScheduledMsg> scheduledMsgList) { this.scheduledMsgList = scheduledMsgList; }

    public boolean isRandomMsgSwitch() { return randomMsgSwitch; }
    public void setRandomMsgSwitch(boolean randomMsgSwitch) { this.randomMsgSwitch = randomMsgSwitch; }

    public List<RandomMsg> getRandomMsgList() { return randomMsgList; }
    public void setRandomMsgList(List<RandomMsg> randomMsgList) { this.randomMsgList = randomMsgList; }

    public boolean isScheduledMomentsSwitch() { return scheduledMomentsSwitch; }
    public void setScheduledMomentsSwitch(boolean scheduledMomentsSwitch) { this.scheduledMomentsSwitch = scheduledMomentsSwitch; }

    public List<ScheduledMoment> getScheduledMomentsList() { return scheduledMomentsList; }
    public void setScheduledMomentsList(List<ScheduledMoment> scheduledMomentsList) { this.scheduledMomentsList = scheduledMomentsList; }

    public boolean isMomentsLikeSwitch() { return momentsLikeSwitch; }
    public void setMomentsLikeSwitch(boolean momentsLikeSwitch) { this.momentsLikeSwitch = momentsLikeSwitch; }

    public int getMomentsLikeMin() { return momentsLikeMin; }
    public void setMomentsLikeMin(int momentsLikeMin) { this.momentsLikeMin = momentsLikeMin; }

    public int getMomentsLikeMax() { return momentsLikeMax; }
    public void setMomentsLikeMax(int momentsLikeMax) { this.momentsLikeMax = momentsLikeMax; }

    public boolean isRandomMomentsSwitch() { return randomMomentsSwitch; }
    public void setRandomMomentsSwitch(boolean randomMomentsSwitch) { this.randomMomentsSwitch = randomMomentsSwitch; }

    public List<ScheduledMoment> getRandomMomentsList() { return randomMomentsList; }
    public void setRandomMomentsList(List<ScheduledMoment> randomMomentsList) { this.randomMomentsList = randomMomentsList; }

    public boolean isEverydayStartStopBotSwitch() { return everydayStartStopBotSwitch; }
    public void setEverydayStartStopBotSwitch(boolean everydayStartStopBotSwitch) { this.everydayStartStopBotSwitch = everydayStartStopBotSwitch; }

    public String getEverydayStartBotTime() { return everydayStartBotTime; }
    public void setEverydayStartBotTime(String everydayStartBotTime) { this.everydayStartBotTime = everydayStartBotTime; }

    public String getEverydayStopBotTime() { return everydayStopBotTime; }
    public void setEverydayStopBotTime(String everydayStopBotTime) { this.everydayStopBotTime = everydayStopBotTime; }

    public boolean isNewFriendSwitch() { return newFriendSwitch; }
    public void setNewFriendSwitch(boolean newFriendSwitch) { this.newFriendSwitch = newFriendSwitch; }

    public boolean isNewFriendReplySwitch() { return newFriendReplySwitch; }
    public void setNewFriendReplySwitch(boolean newFriendReplySwitch) { this.newFriendReplySwitch = newFriendReplySwitch; }

    public List<String> getNewFriendMsg() { return newFriendMsg; }
    public void setNewFriendMsg(List<String> newFriendMsg) { this.newFriendMsg = newFriendMsg; }

    public int getNewFriendCheckMin() { return newFriendCheckMin; }
    public void setNewFriendCheckMin(int newFriendCheckMin) { this.newFriendCheckMin = newFriendCheckMin; }

    public int getNewFriendCheckMax() { return newFriendCheckMax; }
    public void setNewFriendCheckMax(int newFriendCheckMax) { this.newFriendCheckMax = newFriendCheckMax; }

    public boolean isNewFriendRemarkUseNickname() { return newFriendRemarkUseNickname; }
    public void setNewFriendRemarkUseNickname(boolean newFriendRemarkUseNickname) { this.newFriendRemarkUseNickname = newFriendRemarkUseNickname; }

    public String getNewFriendRemarkPrefix() { return newFriendRemarkPrefix; }
    public void setNewFriendRemarkPrefix(String newFriendRemarkPrefix) { this.newFriendRemarkPrefix = newFriendRemarkPrefix; }

    public boolean isNewFriendRemarkPrefixTimestamp() { return newFriendRemarkPrefixTimestamp; }
    public void setNewFriendRemarkPrefixTimestamp(boolean newFriendRemarkPrefixTimestamp) { this.newFriendRemarkPrefixTimestamp = newFriendRemarkPrefixTimestamp; }

    public String getNewFriendRemarkSuffix() { return newFriendRemarkSuffix; }
    public void setNewFriendRemarkSuffix(String newFriendRemarkSuffix) { this.newFriendRemarkSuffix = newFriendRemarkSuffix; }

    public boolean isNewFriendRemarkSuffixTimestamp() { return newFriendRemarkSuffixTimestamp; }
    public void setNewFriendRemarkSuffixTimestamp(boolean newFriendRemarkSuffixTimestamp) { this.newFriendRemarkSuffixTimestamp = newFriendRemarkSuffixTimestamp; }

    public List<String> getNewFriendTags() { return newFriendTags; }
    public void setNewFriendTags(List<String> newFriendTags) { this.newFriendTags = newFriendTags; }

    public boolean isMemorySwitch() { return memorySwitch; }
    public void setMemorySwitch(boolean memorySwitch) { this.memorySwitch = memorySwitch; }

    public int getMemoryMaxCount() { return memoryMaxCount; }
    public void setMemoryMaxCount(int memoryMaxCount) { this.memoryMaxCount = memoryMaxCount; }

    public int getMemoryContextCount() { return memoryContextCount; }
    public void setMemoryContextCount(int memoryContextCount) { this.memoryContextCount = memoryContextCount; }

    public boolean isReplyDelaySwitch() { return replyDelaySwitch; }
    public void setReplyDelaySwitch(boolean replyDelaySwitch) { this.replyDelaySwitch = replyDelaySwitch; }

    public int getReplyDelayMin() { return replyDelayMin; }
    public void setReplyDelayMin(int replyDelayMin) { this.replyDelayMin = replyDelayMin; }

    public int getReplyDelayMax() { return replyDelayMax; }
    public void setReplyDelayMax(int replyDelayMax) { this.replyDelayMax = replyDelayMax; }

    public boolean isCleanAiReplySwitch() { return cleanAiReplySwitch; }
    public void setCleanAiReplySwitch(boolean cleanAiReplySwitch) { this.cleanAiReplySwitch = cleanAiReplySwitch; }

    public boolean isChatImageRecognitionSwitch() { return chatImageRecognitionSwitch; }
    public void setChatImageRecognitionSwitch(boolean chatImageRecognitionSwitch) { this.chatImageRecognitionSwitch = chatImageRecognitionSwitch; }

    public int getChatImageRecognitionApi() { return chatImageRecognitionApi; }
    public void setChatImageRecognitionApi(int chatImageRecognitionApi) { this.chatImageRecognitionApi = chatImageRecognitionApi; }

    public boolean isGroupImageRecognitionSwitch() { return groupImageRecognitionSwitch; }
    public void setGroupImageRecognitionSwitch(boolean groupImageRecognitionSwitch) { this.groupImageRecognitionSwitch = groupImageRecognitionSwitch; }

    public int getGroupImageRecognitionApi() { return groupImageRecognitionApi; }
    public void setGroupImageRecognitionApi(int groupImageRecognitionApi) { this.groupImageRecognitionApi = groupImageRecognitionApi; }

    public String getApiErrorReply() { return apiErrorReply; }
    public void setApiErrorReply(String apiErrorReply) { this.apiErrorReply = apiErrorReply; }

    public boolean isApiErrorReplyOnce() { return apiErrorReplyOnce; }
    public void setApiErrorReplyOnce(boolean apiErrorReplyOnce) { this.apiErrorReplyOnce = apiErrorReplyOnce; }

    public boolean isChatSplitReplySwitch() { return chatSplitReplySwitch; }
    public void setChatSplitReplySwitch(boolean chatSplitReplySwitch) { this.chatSplitReplySwitch = chatSplitReplySwitch; }

    public int getChatSplitMaxChars() { return chatSplitMaxChars; }
    public void setChatSplitMaxChars(int chatSplitMaxChars) { this.chatSplitMaxChars = chatSplitMaxChars; }

    public int getChatSplitMaxCount() { return chatSplitMaxCount; }
    public void setChatSplitMaxCount(int chatSplitMaxCount) { this.chatSplitMaxCount = chatSplitMaxCount; }

    public boolean isGroupSplitReplySwitch() { return groupSplitReplySwitch; }
    public void setGroupSplitReplySwitch(boolean groupSplitReplySwitch) { this.groupSplitReplySwitch = groupSplitReplySwitch; }

    public int getGroupSplitMaxChars() { return groupSplitMaxChars; }
    public void setGroupSplitMaxChars(int groupSplitMaxChars) { this.groupSplitMaxChars = groupSplitMaxChars; }

    public int getGroupSplitMaxCount() { return groupSplitMaxCount; }
    public void setGroupSplitMaxCount(int groupSplitMaxCount) { this.groupSplitMaxCount = groupSplitMaxCount; }
}
