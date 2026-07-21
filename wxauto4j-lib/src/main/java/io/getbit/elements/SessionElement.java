package io.getbit.elements;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话列表元素
 *
 * <p>对标 wxautox4 的 SessionElement，表示会话列表中的一个会话项。</p>
 */
public class SessionElement {

    /** 会话名 */
    private final String name;

    /** 时间 */
    private String time;

    /** 消息内容预览 */
    private String content;

    /** 是否消息免打扰 */
    private boolean ismute;

    /** 是否有新消息 */
    private boolean isnew;

    /** 新消息数量 */
    private int newCount;

    /** 底层元素引用 */
    private Object nativeElement;

    public SessionElement(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isIsmute() {
        return ismute;
    }

    public void setIsmute(boolean ismute) {
        this.ismute = ismute;
    }

    public boolean isIsnew() {
        return isnew;
    }

    public void setIsnew(boolean isnew) {
        this.isnew = isnew;
    }

    public int getNewCount() {
        return newCount;
    }

    public void setNewCount(int newCount) {
        this.newCount = newCount;
    }

    public Object getNativeElement() {
        return nativeElement;
    }

    public void setNativeElement(Object nativeElement) {
        this.nativeElement = nativeElement;
    }

    /**
     * 点击会话（切换到该聊天）
     */
    public void click() {
        // TODO: 通过 nativeElement 点击会话项
    }

    /**
     * 双击会话（独立为子窗口）
     */
    public void doubleClick() {
        // TODO: 通过 nativeElement 双击会话项
    }

    /**
     * 获取会话信息字典
     */
    public Map<String, Object> getInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", name);
        if (time != null) info.put("time", time);
        if (content != null) info.put("content", content);
        info.put("ismute", ismute);
        info.put("isnew", isnew);
        info.put("new_count", newCount);
        return info;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Session{name='").append(name).append('\'');
        if (time != null) sb.append(", time='").append(time).append('\'');
        if (content != null) sb.append(", content='").append(content).append('\'');
        if (ismute) sb.append(", mute");
        if (isnew) sb.append(", new=").append(newCount);
        sb.append('}');
        return sb.toString();
    }
}
