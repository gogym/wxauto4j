package io.getbit;

import io.getbit.internal.WxLayout;
import io.getbit.uiautomation.condition.SearchCondition;
import io.getbit.uiautomation.control.Control;
import io.getbit.uiautomation.control.EditControl;
import io.getbit.uiautomation.enums.ControlType;

/**
 * 微信对话框封装
 *
 * <p>对标 wxautox4 的 WeChatDialog 类，表示弹出的对话框（如确认框、提示框等）。</p>
 */
public class WeChatDialog {

    /** 对话框窗口控件 */
    private final Control dialogControl;

    /** 对话框标题 */
    private String title;

    /** 对话框内容 */
    private String content;

    WeChatDialog(Control dialogControl) {
        this.dialogControl = dialogControl;
        try {
            this.title = dialogControl.getName();
        } catch (Exception e) {
            this.title = "";
        }
    }

    /**
     * 获取对话框标题
     */
    public String getTitle() {
        return title;
    }

    /**
     * 获取对话框内容
     */
    public String getContent() {
        return content;
    }

    /**
     * 点击确认按钮
     */
    public void confirm() {
        try {
            Control btn = dialogControl.findButton(
                    SearchCondition.builder().name("确定").build());
            if (btn.exists(1)) {
                btn.click();
                return;
            }
            // 英文版本
            btn = dialogControl.findButton(
                    SearchCondition.builder().name("OK").build());
            if (btn.exists(1)) {
                btn.click();
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 点击取消按钮
     */
    public void cancel() {
        try {
            Control btn = dialogControl.findButton(
                    SearchCondition.builder().name("取消").build());
            if (btn.exists(1)) {
                btn.click();
                return;
            }
            btn = dialogControl.findButton(
                    SearchCondition.builder().name("Cancel").build());
            if (btn.exists(1)) {
                btn.click();
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 关闭对话框
     */
    public void close() {
        try {
            // 尝试按 Escape 关闭
            dialogControl.sendKeys("{ESCAPE}");
        } catch (Exception e) {
            // 忽略
        }
    }
}
