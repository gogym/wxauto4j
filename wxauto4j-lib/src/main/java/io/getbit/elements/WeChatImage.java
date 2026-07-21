package io.getbit.elements;

import io.getbit.internal.WxParams;
import io.getbit.uiautomation.condition.SearchCondition;
import io.getbit.uiautomation.control.Control;
import io.getbit.uiautomation.control.WindowControl;
import io.getbit.uiautomation.enums.ControlType;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 微信图片/视频窗口类
 *
 * <p>对标 wxautox4 的 WeChatImage 类，用于处理微信图片或视频窗口的各种操作。</p>
 */
public class WeChatImage {

    /** 图片/视频窗口控件 */
    private final WindowControl window;

    /**
     * 构造函数
     *
     * @param window 图片/视频窗口控件
     */
    public WeChatImage(WindowControl window) {
        this.window = window;
    }

    /**
     * 获取窗口控件
     */
    public WindowControl getWindow() {
        return window;
    }

    /**
     * 保存图片/视频
     *
     * @param dirPath 保存目录路径（null 使用默认路径 {@link WxParams#DEFAULT_SAVE_PATH}）
     * @param timeout 保存超时时间（秒）
     * @return 保存的文件路径，失败返回 null
     */
    public Path save(String dirPath, int timeout) {
        try {
            if (dirPath == null || dirPath.isEmpty()) {
                dirPath = WxParams.DEFAULT_SAVE_PATH;
            }

            // 确保保存目录存在
            File dir = new File(dirPath);
            if (!dir.exists()) {
                dir.mkdirs();
            }

            // 点击保存按钮或使用右键菜单保存
            // 通过快捷键 Ctrl+S 触发保存
            window.sendKeys("^s");

            try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // 在保存对话框中设置路径
            Control fileNameEdit = Control.getBackend().findControl(
                    SearchCondition.builder()
                            .controlType(ControlType.Edit)
                            .build());

            if (fileNameEdit.exists(timeout)) {
                // 生成文件名
                String fileName = "wxauto_" + System.currentTimeMillis();
                String fullPath = dirPath + File.separator + fileName;

                // 输入完整路径
                fileNameEdit.click();
                fileNameEdit.sendKeys("^a");
                fileNameEdit.sendKeys("{DELETE}");

                // 使用剪贴板输入路径（避免编码问题）
                java.awt.datatransfer.Clipboard clipboard = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
                clipboard.setContents(new java.awt.datatransfer.StringSelection(fullPath), null);
                fileNameEdit.sendKeys("^v");

                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                // 点击保存按钮
                Control saveBtn = Control.getBackend().findControl(
                        SearchCondition.builder()
                                .name("保存")
                                .controlType(ControlType.Button)
                                .build());
                if (saveBtn.exists(2)) {
                    saveBtn.click();
                } else {
                    // 英文界面
                    saveBtn = Control.getBackend().findControl(
                            SearchCondition.builder()
                                    .name("Save")
                                    .controlType(ControlType.Button)
                                    .build());
                    if (saveBtn.exists(2)) {
                        saveBtn.click();
                    }
                }

                try { Thread.sleep(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

                // 返回保存的文件路径（尝试查找实际保存的文件）
                File savedFile = findSavedFile(dir, fileName);
                if (savedFile != null) {
                    return savedFile.toPath();
                }
                return Paths.get(fullPath);
            }

            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public Path save(String dirPath) {
        return save(dirPath, 10);
    }

    public Path save() {
        return save(null, 10);
    }

    /**
     * 关闭图片/视频窗口
     */
    public void close() {
        try {
            window.close();
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 查找保存的文件
     */
    private File findSavedFile(File dir, String fileNamePrefix) {
        File[] files = dir.listFiles((d, name) -> name.startsWith(fileNamePrefix));
        if (files != null && files.length > 0) {
            return files[0];
        }
        // 如果找不到精确匹配，查找最近修改的文件
        File[] allFiles = dir.listFiles();
        if (allFiles != null) {
            File newest = null;
            long newestTime = 0;
            for (File f : allFiles) {
                if (f.lastModified() > newestTime) {
                    newestTime = f.lastModified();
                    newest = f;
                }
            }
            return newest;
        }
        return null;
    }
}
