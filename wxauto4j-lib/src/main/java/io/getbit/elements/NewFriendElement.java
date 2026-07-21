package io.getbit.elements;

/**
 * 好友申请元素
 *
 * <p>对标 wxautox4 的 NewFriendElement，表示一条好友申请。</p>
 */
public class NewFriendElement {

    /** 申请人名 */
    private final String name;

    /** 申请留言 */
    private String msg;

    /** 申请内容（完整文本） */
    private String content;

    /** 是否可以接受（尚未接受） */
    private boolean acceptable;

    /** 是否已接受 */
    private boolean accepted;

    /** 底层元素引用（用于 accept 操作） */
    private Object nativeElement;

    public NewFriendElement(String name) {
        this.name = name;
        this.acceptable = true;
    }

    public NewFriendElement(String name, String msg) {
        this.name = name;
        this.msg = msg;
        this.acceptable = true;
    }

    public String getName() {
        return name;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isAcceptable() {
        return acceptable;
    }

    public void setAcceptable(boolean acceptable) {
        this.acceptable = acceptable;
    }

    public boolean isAccepted() {
        return accepted;
    }

    public void setAccepted(boolean accepted) {
        this.accepted = accepted;
    }

    public Object getNativeElement() {
        return nativeElement;
    }

    public void setNativeElement(Object nativeElement) {
        this.nativeElement = nativeElement;
    }

    /**
     * 接受好友请求
     *
     * @param remark 备注名
     * @param tags   标签
     * @return 是否成功
     */
    public boolean accept(String remark, String... tags) {
        // TODO: 需要通过 nativeElement 操作微信 UI 来接受好友请求
        this.accepted = true;
        this.acceptable = false;
        return true;
    }

    @Override
    public String toString() {
        return "NewFriend{name='" + name + "', msg='" + msg + "', acceptable=" + acceptable + "}";
    }
}
