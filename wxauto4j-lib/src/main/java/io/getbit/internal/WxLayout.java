package io.getbit.internal;

import io.getbit.uiautomation.condition.SearchCondition;
import io.getbit.uiautomation.control.Control;
import io.getbit.uiautomation.enums.ControlType;

/**
 * 微信窗口布局解析
 *
 * <p>负责解析微信主窗口的三大核心区域，为后续控件定位提供基础。</p>
 *
 * <pre>
 * 微信窗口布局简图：
 * _______________
 * |■|———|    -□×|
 * | |———|       |
 * |A| B |   C   |   A=NavigationBox（导航栏）
 * | |———|———————|   B=SessionBox（会话列表）
 * |=|———|       |   C=ChatBox（聊天区域）
 * ———————————————
 * </pre>
 *
 * <p>每个区域通过 {@link SearchCondition} 描述定位方式，
 * 在实际使用时通过 Backend 查找具体控件实例。</p>
 */
public class WxLayout {

    /** 导航栏（A区）搜索条件 - 包含聊天/通讯录/收藏等导航按钮 */
    private final SearchCondition navigationBoxCondition;

    /** 会话列表（B区）搜索条件 - 包含会话列表和搜索框 */
    private final SearchCondition sessionBoxCondition;

    /** 聊天区域（C区）搜索条件 - 包含消息列表和输入框 */
    private final SearchCondition chatBoxCondition;

    /** 聊天输入框搜索条件（位于 ChatBox 内） */
    private final SearchCondition editBoxCondition;

    private WxLayout(SearchCondition nav, SearchCondition session,
                     SearchCondition chat, SearchCondition edit) {
        this.navigationBoxCondition = nav;
        this.sessionBoxCondition = session;
        this.chatBoxCondition = chat;
        this.editBoxCondition = edit;
    }

    /**
     * 解析微信主窗口布局
     *
     * <p>从已定位的微信主窗口出发，解析出三大区域的搜索条件。
     * 微信 4.0.5 的窗口结构为：MainWindow → Pane → Pane → [NavigationBox, SessionBox, ChatBox]</p>
     *
     * @param mainWindow 已定位的微信主窗口控件
     * @return 解析完成的 WxLayout 实例
     */
    public static WxLayout parse(Control mainWindow) {
        // 微信窗口层级：Window → Pane(index=0) → Pane(index=0) → [Nav, Session, Chat]
        // 第一层 Pane
        SearchCondition mainPane = SearchCondition.builder()
                .controlType(ControlType.Pane)
                .searchFrom(mainWindow.getSearchCondition())
                .foundIndex(1)
                .build();

        // 第二层 Pane
        SearchCondition innerPane = SearchCondition.builder()
                .controlType(ControlType.Pane)
                .searchFrom(mainPane)
                .foundIndex(1)
                .build();

        // NavigationBox: 第1个子 Pane（左侧导航栏）
        SearchCondition navBox = SearchCondition.builder()
                .controlType(ControlType.Pane)
                .searchFrom(innerPane)
                .foundIndex(1)
                .build();

        // SessionBox: 第2个子 Pane（中间会话列表）
        SearchCondition sessBox = SearchCondition.builder()
                .controlType(ControlType.Pane)
                .searchFrom(innerPane)
                .foundIndex(2)
                .build();

        // ChatBox: 第3个子 Pane（右侧聊天区域）
        SearchCondition chatBox = SearchCondition.builder()
                .controlType(ControlType.Pane)
                .searchFrom(innerPane)
                .foundIndex(3)
                .build();

        // 聊天输入框: 位于 ChatBox 内的 EditControl
        SearchCondition editBox = SearchCondition.builder()
                .controlType(ControlType.Edit)
                .searchFrom(chatBox)
                .build();

        return new WxLayout(navBox, sessBox, chatBox, editBox);
    }

    /**
     * 获取导航栏搜索条件
     */
    public SearchCondition getNavigationBoxCondition() {
        return navigationBoxCondition;
    }

    /**
     * 获取会话列表搜索条件
     */
    public SearchCondition getSessionBoxCondition() {
        return sessionBoxCondition;
    }

    /**
     * 获取聊天区域搜索条件
     */
    public SearchCondition getChatBoxCondition() {
        return chatBoxCondition;
    }

    /**
     * 获取聊天输入框搜索条件
     */
    public SearchCondition getEditBoxCondition() {
        return editBoxCondition;
    }
}
