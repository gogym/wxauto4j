package io.getbit.app;

/**
 * 应用程序启动器（非 JavaFX Application 子类）
 *
 * <p>作为 fat jar 和 jpackage 的入口点，避免 JavaFX
 * 模块系统对 Application 子类作为 main 入口的限制。</p>
 */
public class Launcher {

    public static void main(String[] args) {
        WxAutoApp.main(args);
    }
}
