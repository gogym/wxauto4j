package io.getbit;

import io.getbit.elements.WxResponse;
import io.getbit.internal.WxLayout;
import io.getbit.internal.WxParams;
import io.getbit.internal.languages.MainLanguage;
import io.getbit.uiautomation.condition.SearchCondition;
import io.getbit.uiautomation.control.Control;
import io.getbit.uiautomation.control.WindowControl;
import io.getbit.uiautomation.enums.ControlType;
import io.getbit.uiautomation.win.com.IUIAutomationElement;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 朋友圈窗口封装
 *
 * <p>对标 wxautox4 的 MomentsWnd 类，提供朋友圈的获取、刷新、关闭和发布操作。</p>
 */
public class MomentsWnd {

    /** 朋友圈窗口控件 */
    private final WindowControl window;

    /** 语言 */
    private final String language;

    public MomentsWnd(WindowControl window, String language) {
        this.window = window;
        this.language = language;
    }

    /**
     * 获取朋友圈窗口控件
     */
    public WindowControl getWindow() {
        return window;
    }

    /**
     * 获取朋友圈内容
     *
     * @return 朋友圈内容列表
     */
    public List<Moment> GetMoments() {
        return GetMoments(false, 3, 1);
    }

    /**
     * 获取朋友圈内容
     *
     * @param nextPage 是否翻页后再获取
     * @param speed1   翻页滚动速度
     * @param speed2   最后滚动速度
     * @return 朋友圈内容列表
     */
    public List<Moment> GetMoments(boolean nextPage, int speed1, int speed2) {
        List<Moment> moments = new ArrayList<>();
        try {
            if (nextPage) {
                // 向下滚动加载更多
                Control listControl = Control.getBackend().findControl(
                        SearchCondition.builder()
                                .controlType(ControlType.List)
                                .searchFrom(window.getSearchCondition())
                                .build());
                if (listControl.exists(2)) {
                    for (int i = 0; i < speed1; i++) {
                        listControl.getScrollPattern().scroll(0, 3, 0, 0);
                        try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                    for (int i = 0; i < speed2; i++) {
                        listControl.getScrollPattern().scroll(0, 3, 0, 0);
                        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                }
            }

            // 解析朋友圈内容
            Control listControl = Control.getBackend().findControl(
                    SearchCondition.builder()
                            .controlType(ControlType.List)
                            .searchFrom(window.getSearchCondition())
                            .build());

            if (listControl.exists(2)) {
                Object listNative = listControl.getNativeElement();
                if (listNative instanceof IUIAutomationElement) {
                    IUIAutomationElement listElement = (IUIAutomationElement) listNative;
                    IUIAutomationElement child = listElement.getFirstChild();
                    while (child != null) {
                        String name = child.getName();
                        if (name != null && !name.isEmpty()) {
                            Moment m = new Moment(name, child);
                            moments.add(m);
                        }
                        child = child.getNextSibling();
                    }
                }
            }
        } catch (Exception e) {
            // 忽略
        }
        return moments;
    }

    /**
     * 刷新朋友圈内容
     */
    public void Refresh() {
        try {
            window.click();
            Control content = Control.getBackend().findControl(
                    SearchCondition.builder()
                            .controlType(ControlType.List)
                            .searchFrom(window.getSearchCondition())
                            .build());
            if (content.exists(2)) {
                content.getScrollPattern().scroll(0, 2, 0, 0); // UP
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 关闭朋友圈窗口
     */
    public void Close() {
        try {
            window.close();
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 发表朋友圈
     *
     * @param text          朋友圈文字内容
     * @param mediaFiles    图片/视频文件路径列表
     * @param privacyConfig 隐私配置（可为 null 表示公开）
     * @return 操作结果
     */
    public WxResponse Publish(String text, List<String> mediaFiles, Map<String, Object> privacyConfig) {
        try {
            // 点击发布按钮（相机图标）
            Control cameraBtn = Control.getBackend().findControl(
                    SearchCondition.builder()
                            .controlType(ControlType.Button)
                            .searchFrom(window.getSearchCondition())
                            .build());
            if (cameraBtn.exists(3)) {
                cameraBtn.click();
            }
            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // 添加媒体文件（先选择图片再编辑文字，4.1版本逻辑）
            if (mediaFiles != null && !mediaFiles.isEmpty()) {
                List<File> files = new ArrayList<>();
                for (String path : mediaFiles) {
                    File f = new File(path);
                    if (f.exists()) files.add(f);
                }
                if (!files.isEmpty()) {
                    // 通过剪贴板粘贴文件
                    java.awt.datatransfer.Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                    final List<File> finalFiles = files;
                    java.awt.datatransfer.Transferable transferable = new java.awt.datatransfer.Transferable() {
                        @Override public java.awt.datatransfer.DataFlavor[] getTransferDataFlavors() {
                            return new java.awt.datatransfer.DataFlavor[]{java.awt.datatransfer.DataFlavor.javaFileListFlavor};
                        }
                        @Override public boolean isDataFlavorSupported(java.awt.datatransfer.DataFlavor flavor) {
                            return java.awt.datatransfer.DataFlavor.javaFileListFlavor.equals(flavor);
                        }
                        @Override public Object getTransferData(java.awt.datatransfer.DataFlavor flavor) {
                            return finalFiles;
                        }
                    };
                    clipboard.setContents(transferable, null);

                    // 粘贴到发布界面
                    Control editArea = Control.getBackend().findControl(
                            SearchCondition.builder().controlType(ControlType.Edit).build());
                    if (editArea.exists(3)) {
                        editArea.click();
                        editArea.sendKeys("^v");
                        try { Thread.sleep(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    }
                }
            }

            // 输入文字
            if (text != null && !text.isEmpty()) {
                Control editBox = Control.getBackend().findControl(
                        SearchCondition.builder().controlType(ControlType.Edit).build());
                if (editBox.exists(2)) {
                    editBox.click();
                    java.awt.datatransfer.Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
                    clipboard.setContents(new StringSelection(text), null);
                    editBox.sendKeys("^v");
                }
            }

            // TODO: 处理隐私配置 privacyConfig

            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // 点击发送按钮
            Control sendBtn = Control.getBackend().findControl(
                    SearchCondition.builder()
                            .name("发送")
                            .controlType(ControlType.Button)
                            .build());
            if (sendBtn.exists(2)) {
                sendBtn.click();
            } else {
                sendBtn = Control.getBackend().findControl(
                        SearchCondition.builder()
                                .name("Send")
                                .controlType(ControlType.Button)
                                .build());
                if (sendBtn.exists(2)) {
                    sendBtn.click();
                }
            }

            return WxResponse.ok("朋友圈发布成功");
        } catch (Exception e) {
            return WxResponse.fail("朋友圈发布失败: " + e.getMessage());
        }
    }

    // ==================== 朋友圈内容对象 ====================

    /**
     * 朋友圈内容对象
     */
    public static class Moment {
        private final String content;
        private final IUIAutomationElement nativeElement;

        public Moment(String content, IUIAutomationElement nativeElement) {
            this.content = content;
            this.nativeElement = nativeElement;
        }

        /**
         * 获取朋友圈文字内容
         */
        public String getContent() {
            return content;
        }

        /**
         * 点赞/取消点赞
         *
         * @param like true=点赞，false=取消赞
         */
        public void Like(boolean like) {
            // TODO: 通过 nativeElement 操作点赞按钮
        }

        public void Like() {
            Like(true);
        }

        /**
         * 评论朋友圈
         *
         * @param text 评论内容
         */
        public void Comment(String text) {
            // TODO: 通过 nativeElement 操作评论
        }

        @Override
        public String toString() {
            return "Moment{content='" + (content != null && content.length() > 50
                    ? content.substring(0, 50) + "..." : content) + "'}";
        }
    }
}
