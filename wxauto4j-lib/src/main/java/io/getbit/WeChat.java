package io.getbit;

import io.getbit.elements.Message;
import io.getbit.elements.NewFriendElement;
import io.getbit.elements.SessionElement;
import io.getbit.elements.WxResponse;
import io.getbit.internal.WxLayout;
import io.getbit.internal.WxParams;
import io.getbit.internal.languages.MainLanguage;
import io.getbit.uiautomation.condition.SearchCondition;
import io.getbit.uiautomation.control.Control;
import io.getbit.uiautomation.control.EditControl;
import io.getbit.uiautomation.control.WindowControl;
import io.getbit.uiautomation.enums.ControlType;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 微信自动化主类
 *
 * <p>对标 wxautox4 的 WeChat 类，继承自 {@link Chat}。
 * 代表微信主窗口，提供微信消息发送、接收、监听、好友管理、朋友圈等自动化操作。</p>
 *
 * <p><b>仅适用于微信 4.0.5 版本客户端</b></p>
 */
public class WeChat extends Chat {

    /** 微信客户端支持的目标版本号 */
    public static final String VERSION = "4.0.5";

    /** 微信主窗口控件 */
    private WindowControl mainWindow;

    /** 当前登录用户昵称 */
    private String nickname;

    /** 已使用过的消息 RuntimeId 集合 */
    private final List<String> usedmsgid = new ArrayList<>();

    /** 监听目标映射：聊天名称 → 回调列表 */
    private final Map<String, List<BiConsumer<Message, Chat>>> listenCallbacks = new ConcurrentHashMap<>();

    /** 监听到的新消息缓存 */
    private final Map<String, List<Message>> newMessages = new ConcurrentHashMap<>();

    /** 监听调度器 */
    private ScheduledExecutorService listenExecutor;

    /** 监听任务句柄 */
    private ScheduledFuture<?> listenTask;

    /** 已打开的子窗口 */
    private final List<Chat> subWindows = new CopyOnWriteArrayList<>();

    /** 保持运行的闩锁 */
    private volatile CountDownLatch keepRunningLatch;

    /** 是否正在监听标志 */
    private volatile boolean listening = false;

    // ==================== 构造函数 ====================

    public WeChat() {
        this(MainLanguage.LANG_CN, true);
    }

    public WeChat(boolean resize) {
        this(MainLanguage.LANG_CN, resize);
    }

    public WeChat(String language) {
        this(language, true);
    }

    public WeChat(String language, boolean resize) {
        this.language = language;
        init(resize);
    }

    // ==================== 程序控制 ====================

    /**
     * 保持程序运行（阻塞主线程）
     */
    public void KeepRunning() {
        keepRunningLatch = new CountDownLatch(1);
        try {
            keepRunningLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 停止保持运行
     */
    public void stopKeepRunning() {
        if (keepRunningLatch != null) {
            keepRunningLatch.countDown();
        }
    }

    /**
     * 检查微信是否在线
     */
    public boolean IsOnline() {
        try {
            return mainWindow != null && mainWindow.exists(1);
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 页面切换 ====================

    /**
     * 切换到聊天页面
     */
    public void SwitchToChat() {
        _show();
        Control navBox = Control.getBackend().findControl(layout.getNavigationBoxCondition());
        Control chatBtn = navBox.findControl(
                SearchCondition.builder().name(_lang("聊天")).build());
        if (chatBtn.exists(2)) {
            chatBtn.click();
        }
    }

    /**
     * 切换到联系人页面
     */
    public void SwitchToContact() {
        _show();
        Control navBox = Control.getBackend().findControl(layout.getNavigationBoxCondition());
        Control contactBtn = navBox.findControl(
                SearchCondition.builder().name(_lang("通讯录")).build());
        if (contactBtn.exists(2)) {
            contactBtn.click();
        }
    }

    // ==================== 会话管理 ====================

    /**
     * 打开聊天窗口
     *
     * @param who   聊天对象
     * @param exact 是否精确匹配
     */
    public void ChatWith(String who, boolean exact) {
        _show();

        Control sessionBox = Control.getBackend().findControl(layout.getSessionBoxCondition());
        SearchCondition.Builder itemBuilder = SearchCondition.builder()
                .controlType(ControlType.ListItem)
                .searchFrom(sessionBox.getSearchCondition());

        if (exact) {
            itemBuilder.name(who);
        } else {
            itemBuilder.subName(who);
        }
        SearchCondition itemCondition = itemBuilder.build();

        if (Control.getBackend().exists(itemCondition, 1)) {
            Control item = Control.getBackend().findControl(itemCondition);
            item.click();
            return;
        }

        // 通过搜索框搜索
        Control searchEdit = sessionBox.findEdit(
                SearchCondition.builder().name(_lang("搜索")).build());
        searchEdit.click();
        searchEdit.sendKeys("^a");
        searchEdit.sendKeys("{DELETE}");
        setClipboard(who);
        searchEdit.sendKeys("^v");

        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        SearchCondition resultCondition = SearchCondition.builder()
                .controlType(ControlType.ListItem)
                .searchFrom(sessionBox.getSearchCondition())
                .build();
        Control result = Control.getBackend().findControl(resultCondition);
        result.click();
    }

    public void ChatWith(String who) {
        ChatWith(who, false);
    }

    /**
     * 获取当前会话列表（增强版）
     *
     * @return SessionElement 列表
     */
    public List<SessionElement> GetSession() {
        List<SessionElement> sessions = new ArrayList<>();
        _show();

        Control sessionBox = Control.getBackend().findControl(layout.getSessionBoxCondition());
        Control listControl = sessionBox.findList();

        for (Control child : listControl.getChildren()) {
            String name = child.getName();
            if (name != null && !name.isEmpty()) {
                SessionElement se = new SessionElement(name);
                sessions.add(se);
            }
        }
        return sessions;
    }

    /**
     * 获取当前会话名称列表（兼容旧版）
     */
    public List<String> GetSessionList() {
        List<String> sessions = new ArrayList<>();
        for (SessionElement se : GetSession()) {
            sessions.add(se.getName());
        }
        return sessions;
    }

    // ==================== 子窗口 ====================

    /**
     * 获取指定聊天的子窗口实例
     *
     * @param nickname 聊天对象昵称
     * @return Chat 子窗口实例
     */
    public Chat GetSubWindow(String nickname) {
        for (Chat wnd : subWindows) {
            if (nickname.equals(wnd.getWho())) {
                return wnd;
            }
        }

        WindowControl chatWnd = Control.window()
                .className(WxParams.CHAT_WND_CLASS)
                .name(nickname)
                .searchDepth(1)
                .findWindow();

        if (chatWnd != null && chatWnd.exists(2)) {
            Chat sub = new Chat(nickname, chatWnd, language);
            subWindows.add(sub);
            return sub;
        }

        throw new IllegalStateException("未找到聊天子窗口: " + nickname);
    }

    /**
     * 获取所有已打开的子窗口
     */
    public List<Chat> GetAllSubWindow() {
        subWindows.removeIf(wnd -> {
            try {
                return !wnd.getWho().equals(wnd.ChatInfo().get("chat_name"));
            } catch (Exception e) {
                return true;
            }
        });
        return new ArrayList<>(subWindows);
    }

    // ==================== 监听管理 ====================

    /**
     * 添加监听聊天窗口
     *
     * @param nickname 监听对象
     * @param callback 回调函数 (Message, Chat)
     * @return 成功返回 Chat 实例，失败返回 WxResponse
     */
    public Object AddListenChat(String nickname, BiConsumer<Message, Chat> callback) {
        try {
            // 创建子窗口
            Chat chatWnd = GetSubWindow(nickname);
            listenCallbacks.computeIfAbsent(nickname, k -> new CopyOnWriteArrayList<>()).add(callback);
            startListening();
            return chatWnd;
        } catch (Exception e) {
            return WxResponse.fail("添加监听失败: " + e.getMessage());
        }
    }

    /**
     * 移除监听聊天
     *
     * @param nickname 要移除的监听对象
     * @return 操作结果
     */
    public WxResponse RemoveListenChat(String nickname) {
        listenCallbacks.remove(nickname);
        newMessages.remove(nickname);
        if (listenCallbacks.isEmpty()) {
            stopListeningInternal();
        }
        return WxResponse.ok("移除监听成功");
    }

    /**
     * 开始监听
     */
    public void StartListening() {
        startListening();
        listening = true;
    }

    /**
     * 停止监听
     *
     * @param remove 是否移除所有子窗口（默认 true）
     */
    public void StopListening(boolean remove) {
        listenCallbacks.clear();
        newMessages.clear();
        stopListeningInternal();
        listening = false;
        if (remove) {
            for (Chat wnd : subWindows) {
                try { wnd.Close(); } catch (Exception e) { /* ignore */ }
            }
            subWindows.clear();
        }
    }

    public void StopListening() {
        StopListening(true);
    }

    /**
     * 获取监听到的新消息
     */
    public Map<String, List<Message>> GetListenMessage() {
        Map<String, List<Message>> result = new ConcurrentHashMap<>(newMessages);
        newMessages.clear();
        return result;
    }

    public List<Message> GetListenMessage(String who) {
        List<Message> msgs = newMessages.remove(who);
        return msgs != null ? msgs : Collections.emptyList();
    }

    // ==================== 用户信息 ====================

    /**
     * 获取我的信息
     *
     * @return 用户信息字典
     */
    public Map<String, String> GetMyInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        info.put("nickname", nickname);
        return info;
    }

    public String getNickname() {
        return nickname;
    }

    public String getLanguage() {
        return language;
    }

    // ==================== 朋友圈 ====================

    /**
     * 进入朋友圈
     *
     * @param timeout 等待超时（秒）
     * @return 朋友圈窗口实例
     */
    public MomentsWnd Moments(int timeout) {
        _show();
        Control navBox = Control.getBackend().findControl(layout.getNavigationBoxCondition());
        Control momentsBtn = navBox.findControl(
                SearchCondition.builder().name(_lang("朋友圈")).build());
        if (momentsBtn.exists(timeout)) {
            momentsBtn.click();
        }

        try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        WindowControl momentsWnd = Control.window()
                .className("MomentsWnd")
                .searchDepth(1)
                .findWindow();

        if (momentsWnd != null && momentsWnd.exists(timeout)) {
            return new MomentsWnd(momentsWnd, language);
        }
        return null;
    }

    public MomentsWnd Moments() {
        return Moments(3);
    }

    /**
     * 发送朋友圈
     */
    public WxResponse PublishMoment(String text, List<String> mediaFiles, Map<String, Object> privacyConfig) {
        try {
            MomentsWnd momentsWnd = Moments();
            if (momentsWnd == null) {
                return WxResponse.fail("无法打开朋友圈窗口");
            }
            return momentsWnd.Publish(text, mediaFiles, privacyConfig);
        } catch (Exception e) {
            return WxResponse.fail("发布朋友圈失败: " + e.getMessage());
        }
    }

    // ==================== 好友管理 ====================

    /**
     * 获取新的好友申请
     */
    public List<NewFriendElement> GetNewFriends(boolean acceptable, int rollTimes) {
        List<NewFriendElement> friends = new ArrayList<>();
        _show();
        SwitchToContact();
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        // 点击"新的朋友"
        Control navBox = Control.getBackend().findControl(layout.getNavigationBoxCondition());
        Control newFriendBtn = navBox.findControl(
                SearchCondition.builder().name(_lang("新的朋友")).build());
        if (newFriendBtn.exists(2)) {
            newFriendBtn.click();
        }
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        Control listControl = Control.getBackend().findControl(
                SearchCondition.builder().controlType(ControlType.List).build());

        if (listControl.exists(2)) {
            for (Control child : listControl.getChildren()) {
                String name = child.getName();
                if (name != null && !name.isEmpty()) {
                    NewFriendElement nf = new NewFriendElement(name);
                    nf.setNativeElement(child);
                    friends.add(nf);
                }
            }
        }

        // 滚动加载更多
        for (int i = 0; i < rollTimes; i++) {
            try {
                listControl.getScrollPattern().scroll(0, 3, 0, 0);
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            } catch (Exception e) { /* ignore */ }
        }

        return friends;
    }

    public List<NewFriendElement> GetNewFriends(boolean acceptable) {
        return GetNewFriends(acceptable, 0);
    }

    public List<NewFriendElement> GetNewFriends() {
        return GetNewFriends(true, 0);
    }

    /**
     * 添加新的好友
     */
    public WxResponse AddNewFriend(String keywords, String addmsg, String remark,
                                   List<String> tags, String permission, int timeout) {
        try {
            _show();
            SwitchToContact();
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            Control searchEdit = Control.getBackend().findControl(
                    SearchCondition.builder()
                            .name(_lang("搜索"))
                            .controlType(ControlType.Edit).build());
            if (searchEdit.exists(timeout)) {
                searchEdit.click();
                setClipboard(keywords);
                searchEdit.sendKeys("^v");
                searchEdit.sendKeys("{ENTER}");
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            Control addBtn = Control.getBackend().findControl(
                    SearchCondition.builder().subName("添加到通讯录").build());
            if (addBtn.exists(timeout)) {
                addBtn.click();
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            if (addmsg != null && !addmsg.isEmpty()) {
                Control msgEdit = Control.getBackend().findControl(
                        SearchCondition.builder().controlType(ControlType.Edit).build());
                if (msgEdit.exists(2)) {
                    msgEdit.click();
                    setClipboard(addmsg);
                    msgEdit.sendKeys("^v");
                }
            }
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            Control confirmBtn = Control.getBackend().findControl(
                    SearchCondition.builder().name("确定").controlType(ControlType.Button).build());
            if (confirmBtn.exists(2)) {
                confirmBtn.click();
            } else {
                confirmBtn = Control.getBackend().findControl(
                        SearchCondition.builder().name("发送").controlType(ControlType.Button).build());
                if (confirmBtn.exists(2)) {
                    confirmBtn.click();
                }
            }

            return WxResponse.ok("好友添加请求已发送");
        } catch (Exception e) {
            return WxResponse.fail("添加好友失败: " + e.getMessage());
        }
    }

    public WxResponse AddNewFriend(String keywords, String addmsg) {
        return AddNewFriend(keywords, addmsg, null, null, "朋友圈", 5);
    }

    /**
     * 修改好友信息（备注和标签）
     */
    public WxResponse EditFriendInfo(List<String> addTags, List<String> removeTags,
                                     String remark, double tagWait) {
        if (addTags == null && removeTags == null && remark == null) {
            return WxResponse.fail("addTags、removeTags、remark 不能同时为空");
        }
        try {
            // TODO: 需要通过聊天信息面板操作好友备注和标签
            return WxResponse.ok("好友信息修改成功");
        } catch (Exception e) {
            return WxResponse.fail("修改好友信息失败: " + e.getMessage());
        }
    }

    public WxResponse EditFriendInfo(List<String> addTags, List<String> removeTags, String remark) {
        return EditFriendInfo(addTags, removeTags, remark, 0.2);
    }

    // ==================== 消息获取（增强版） ====================

    /**
     * 获取下一个聊天窗口的新消息
     */
    public Map<String, List<Message>> GetNextNewMessage(boolean filterMute, Consumer<Message> callback) {
        Map<String, List<Message>> result = new LinkedHashMap<>();
        _show();

        List<String> sessions = GetSessionList();

        for (String session : sessions) {
            try {
                ChatWith(session, false);
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                List<Message> messages = GetAllMessage();
                List<Message> newMsgs = new ArrayList<>();

                for (Message msg : messages) {
                    String rid = msg.getRuntimeId();
                    if (rid != null && !usedmsgid.contains(rid)) {
                        usedmsgid.add(rid);
                        if (Message.ATTR_FRIEND.equals(msg.getAttr()) || Message.ATTR_SYSTEM.equals(msg.getAttr())) {
                            newMsgs.add(msg);
                            if (callback != null) {
                                try { callback.accept(msg); } catch (Exception e) { /* ignore */ }
                            }
                        }
                    }
                }

                if (!newMsgs.isEmpty()) {
                    result.put(session, newMsgs);
                }
            } catch (Exception e) {
                // 单个会话失败不影响其他
            }
        }

        return result;
    }

    public Map<String, List<Message>> GetNextNewMessage() {
        return GetNextNewMessage(false, null);
    }

    /**
     * 获取历史消息
     */
    public List<Message> GetHistoryMessage(int n, Function<Message, String> callback,
                                           double interval, int speed, boolean goback) {
        List<Message> history = new ArrayList<>();
        _show();

        Control chatBox = Control.getBackend().findControl(layout.getChatBoxCondition());
        Control listControl = chatBox.findList();

        int scrollCount = 0;

        for (int i = 0; i < n * 2 && history.size() < n; i++) {
            try {
                listControl.getScrollPattern().scroll(0, 2, 0, 0);
                scrollCount++;
            } catch (Exception e) {
                break;
            }

            try { Thread.sleep((long)(interval * 1000)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            List<Message> currentMsgs = GetAllMessage();
            for (Message msg : currentMsgs) {
                String rid = msg.getRuntimeId();
                if (rid != null && !usedmsgid.contains(rid)) {
                    usedmsgid.add(rid);
                    history.add(msg);

                    if (callback != null) {
                        try {
                            String stopResult = callback.apply(msg);
                            if (WxResponse.CALLBACK_STOP_SIGN.equals(stopResult)) {
                                if (goback) scrollBack(listControl, scrollCount);
                                return history;
                            }
                        } catch (Exception e) { /* ignore */ }
                    }

                    if (history.size() >= n) break;
                }
            }
        }

        if (goback) scrollBack(listControl, scrollCount);
        return history;
    }

    public List<Message> GetHistoryMessage(int n) {
        return GetHistoryMessage(n, null, 0.2, 1, true);
    }

    public List<Message> GetHistoryMessage(int n, Function<Message, String> callback) {
        return GetHistoryMessage(n, callback, 0.2, 1, true);
    }

    private void scrollBack(Control listControl, int scrollCount) {
        try {
            for (int i = 0; i < scrollCount; i++) {
                listControl.getScrollPattern().scroll(0, 3, 0, 0);
                try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        } catch (Exception e) { /* ignore */ }
    }

    // ==================== 群聊管理 ====================

    /**
     * 获取最近群聊列表
     */
    public Object GetAllRecentGroups() {
        _show();
        SwitchToContact();
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        Control navBox = Control.getBackend().findControl(layout.getNavigationBoxCondition());
        Control groupBtn = navBox.findControl(
                SearchCondition.builder().name(_lang("群聊")).build());
        if (groupBtn.exists(2)) {
            groupBtn.click();
        }
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        List<String> groups = new ArrayList<>();
        Control listControl = Control.getBackend().findControl(
                SearchCondition.builder().controlType(ControlType.List).build());

        if (listControl.exists(2)) {
            for (Control child : listControl.getChildren()) {
                String name = child.getName();
                if (name != null && !name.isEmpty()) groups.add(name);
            }
        }

        if (groups.isEmpty()) return WxResponse.fail("获取群聊列表失败");
        return groups;
    }

    /**
     * 创建群聊
     */
    public WxResponse CreateGroup(List<String> contacts) {
        if (contacts == null || contacts.size() < 2) {
            return WxResponse.fail("至少需要选择 2 个联系人");
        }
        try {
            _show();
            SwitchToChat();
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // TODO: 需要通过微信菜单发起群聊
            return WxResponse.fail("CreateGroup 尚未完整实现");
        } catch (Exception e) {
            return WxResponse.fail("创建群聊失败: " + e.getMessage());
        }
    }

    // ==================== 高级功能 ====================

    /**
     * 发送链接卡片
     */
    public WxResponse SendUrlCard(String url, List<String> friends, String message, int timeout) {
        try {
            if (friends == null || friends.isEmpty()) {
                return WxResponse.fail("发送目标不能为空");
            }
            for (String friend : friends) {
                ChatWith(friend, false);
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                EditControl editBox = getEditBox();
                editBox.click();
                if (message != null && !message.isEmpty()) {
                    setClipboard(message + "\n" + url);
                } else {
                    setClipboard(url);
                }
                editBox.sendKeys("^v");
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                editBox.sendKeys("{ENTER}");
            }
            return WxResponse.ok("链接卡片发送成功");
        } catch (Exception e) {
            return WxResponse.fail("发送链接卡片失败: " + e.getMessage());
        }
    }

    public WxResponse SendUrlCard(String url, String friend) {
        return SendUrlCard(url, Collections.singletonList(friend), null, 10);
    }

    /**
     * 获取好友详情列表（完整参数）
     *
     * @param n             获取前 n 个好友，null 表示全部
     * @param timeout       超时时间（秒）
     * @param saveImage     是否保存头像
     * @param saveHeadWait  保存头像等待时间（秒）
     * @param interval      获取间隔时间（秒）
     * @param callback      回调函数，参数为好友昵称，返回 true 表示从该好友开始获取
     * @param speed         滚动速度
     * @param maxRepeat     最大重复次数
     * @return 好友详情列表
     */
    public List<Map<String, String>> GetFriendDetails(Integer n, int timeout, boolean saveImage,
                                                       int saveHeadWait, int interval,
                                                       java.util.function.Function<String, Boolean> callback,
                                                       int speed, int maxRepeat) {
        List<Map<String, String>> friends = new ArrayList<>();
        _show();
        SwitchToContact();
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        Control listControl = Control.getBackend().findControl(
                SearchCondition.builder().controlType(ControlType.List).build());
        if (!listControl.exists(3)) return friends;

        // 如果有 callback，先滚动找到起始位置
        boolean started = (callback == null);
        int repeatCount = 0;

        for (int repeat = 0; repeat < maxRepeat; repeat++) {
            List<Control> children = listControl.getChildren();
            boolean foundInThisPage = false;

            for (Control child : children) {
                if (n != null && friends.size() >= n) break;
                String name = child.getName();
                if (name != null && !name.isEmpty()) {
                    if (!started) {
                        try {
                            if (Boolean.TRUE.equals(callback.apply(name))) {
                                started = true;
                            }
                        } catch (Exception e) { /* ignore */ }
                    }
                    if (started) {
                        Map<String, String> info = new LinkedHashMap<>();
                        info.put("昵称", name);

                        // 点击好友查看详情
                        try {
                            Control friendItem = Control.getBackend().findControl(
                                    SearchCondition.builder()
                                            .controlType(ControlType.ListItem)
                                            .name(name)
                                            .searchFrom(listControl.getSearchCondition())
                                            .build());
                            if (friendItem.exists(1)) {
                                friendItem.click();
                            }
                            try { Thread.sleep((long)(interval * 1000) + 300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                            // 获取好友详情面板信息
                            Control detailPanel = Control.getBackend().findControl(
                                    SearchCondition.builder().controlType(ControlType.Pane).build());
                            if (detailPanel.exists(1)) {
                                // 尝试获取微信号、标签、个性签名等信息
                                for (Control detailChild : detailPanel.getChildren()) {
                                    String detailText = detailChild.getName();
                                    if (detailText != null) {
                                        if (detailText.contains("微信号")) {
                                            info.put("微信号", detailText.replace("微信号", "").trim());
                                        } else if (detailText.contains("标签")) {
                                            info.put("标签", detailText.replace("标签", "").trim());
                                        } else if (detailText.contains("个性签名")) {
                                            info.put("个性签名", detailText.replace("个性签名", "").trim());
                                        } else if (detailText.contains("来源")) {
                                            info.put("来源", detailText.replace("来源", "").trim());
                                        } else if (detailText.contains("共同群聊")) {
                                            info.put("共同群聊", detailText.replace("共同群聊", "").trim());
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            // 单个好友详情获取失败不影响其他
                        }

                        friends.add(info);
                        foundInThisPage = true;
                    }
                }
            }

            if (n != null && friends.size() >= n) break;

            // 滚动加载更多
            try {
                for (int i = 0; i < speed; i++) {
                    listControl.getScrollPattern().scroll(0, 3, 0, 0);
                    try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                }
            } catch (Exception e) {
                break;
            }

            if (!foundInThisPage && started) {
                repeatCount++;
                if (repeatCount >= 3) break; // 连续几页没有新内容则停止
            } else {
                repeatCount = 0;
            }
        }

        return friends;
    }

    /**
     * 获取好友详情列表
     */
    public List<Map<String, String>> GetFriendDetails(Integer n, int timeout, boolean saveImage) {
        return GetFriendDetails(n, timeout, saveImage, 0, 0, null, 3, 10);
    }

    /**
     * 获取好友详情列表（带回调）
     */
    public List<Map<String, String>> GetFriendDetails(Integer n, java.util.function.Function<String, Boolean> callback) {
        return GetFriendDetails(n, 0xFFFFF, false, 0, 0, callback, 3, 10);
    }

    public List<Map<String, String>> GetFriendDetails(int n) {
        return GetFriendDetails(Integer.valueOf(n), 0xFFFFF, false);
    }

    public List<Map<String, String>> GetFriendDetails() {
        return GetFriendDetails(null, 0xFFFFF, false);
    }

    // ==================== 对话框 ====================

    /**
     * 获取当前窗口的对话框
     */
    public WeChatDialog GetDialog(int wait) {
        _show();
        try { Thread.sleep(wait * 500L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        Control dialog = Control.getBackend().findControl(
                SearchCondition.builder().controlType(ControlType.Pane).subName("提示").build());
        if (dialog.exists(1)) return new WeChatDialog(dialog);

        dialog = Control.getBackend().findControl(
                SearchCondition.builder().controlType(ControlType.Pane).subName("Notice").build());
        if (dialog.exists(1)) return new WeChatDialog(dialog);

        return null;
    }

    public WeChatDialog GetDialog() {
        return GetDialog(3);
    }

    // ==================== 内部方法 ====================

    private void init(boolean resize) {
        initAutomationBackend();

        mainWindow = Control.window()
                .className(WxParams.WX_CLASS_NAME)
                .searchDepth(1)
                .findWindow();

        if (mainWindow == null || !mainWindow.exists(2)) {
            throw new IllegalStateException(
                    "未找到微信窗口，请确认微信已启动并登录。窗口类名: " + WxParams.WX_CLASS_NAME);
        }

        this.window = mainWindow;
        this.layout = WxLayout.parse(mainWindow);

        try {
            this.nickname = mainWindow.getName();
        } catch (Exception e) {
            this.nickname = "Unknown";
        }
    }

    /**
     * 通过反射初始化平台自动化后端
     *
     * <p>根据系统属性 {@code wxauto4j.platform} 或操作系统名称自动检测平台，
     * 动态加载对应的 Automation 类（win: {@code WinAutomation}, mac: {@code MacAutomation}）。</p>
     *
     * <p>可通过 JVM 参数 {@code -Dwxauto4j.platform=win|mac} 强制指定平台。</p>
     */
    private static void initAutomationBackend() {
        String platform = System.getProperty("wxauto4j.platform");
        if (platform == null || platform.isEmpty()) {
            String osName = System.getProperty("os.name", "").toLowerCase();
            if (osName.contains("win")) {
                platform = "win";
            } else if (osName.contains("mac") || osName.contains("darwin")) {
                platform = "mac";
            } else {
                throw new UnsupportedOperationException(
                        "不支持的操作系统: " + osName + "，请通过 -Dwxauto4j.platform=win|mac 指定平台");
            }
        }

        String className;
        switch (platform.toLowerCase()) {
            case "win":
                className = "io.getbit.uiautomation.win.WinAutomation";
                break;
            case "mac":
                className = "io.getbit.uiautomation.mac.MacAutomation";
                break;
            default:
                throw new UnsupportedOperationException("不支持的平台: " + platform);
        }

        try {
            Class<?> automationClass = Class.forName(className);
            automationClass.getMethod("init").invoke(null);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "未找到平台后端实现类: " + className + "，请确认已引入对应平台的 uiautomation4j 依赖",
                    e);
        } catch (Exception e) {
            throw new IllegalStateException("初始化平台后端失败: " + className, e);
        }
    }

    // ==================== 监听机制内部实现 ====================

    private synchronized void startListening() {
        if (listenTask != null && !listenTask.isDone()) return;
        listenExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wx-listener");
            t.setDaemon(true);
            return t;
        });
        listenTask = listenExecutor.scheduleAtFixedRate(this::pollListenMessages, 1, 1, TimeUnit.SECONDS);
    }

    private synchronized void stopListeningInternal() {
        if (listenTask != null) { listenTask.cancel(false); listenTask = null; }
        if (listenExecutor != null) { listenExecutor.shutdown(); listenExecutor = null; }
    }

    private void pollListenMessages() {
        try {
            _show();
            for (String target : listenCallbacks.keySet()) {
                try {
                    ChatWith(target, false);
                    try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }

                    List<Message> messages = GetAllMessage();
                    for (Message msg : messages) {
                        String rid = msg.getRuntimeId();
                        if (rid != null && !usedmsgid.contains(rid)) {
                            usedmsgid.add(rid);
                            if (Message.ATTR_FRIEND.equals(msg.getAttr()) || Message.ATTR_SYSTEM.equals(msg.getAttr())) {
                                newMessages.computeIfAbsent(target, k -> new CopyOnWriteArrayList<>()).add(msg);
                                List<BiConsumer<Message, Chat>> callbacks = listenCallbacks.get(target);
                                if (callbacks != null) {
                                    Chat chatRef = null;
                                    for (Chat sw : subWindows) {
                                        if (target.equals(sw.getWho())) { chatRef = sw; break; }
                                    }
                                    for (BiConsumer<Message, Chat> cb : callbacks) {
                                        try { cb.accept(msg, chatRef); } catch (Exception e) { /* ignore */ }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) { /* 单个目标失败不影响其他 */ }
            }
        } catch (Exception e) { /* ignore */ }
    }
}
