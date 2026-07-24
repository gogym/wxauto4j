package io.getbit.app;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/**
 * wxauto4j JavaFX 应用程序
 *
 * <p>提供微信自动化 GUI 界面，支持：</p>
 * <ul>
 *   <li>连接/断开微信</li>
 *   <li>机器人启停（AI 智能回复、关键词、转发等）</li>
 *   <li>多面板管理（监听、群组、Prompt、定时任务等）</li>
 *   <li>实时日志查看</li>
 * </ul>
 */
public class WxAutoApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("main.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, 1100, 750);
        scene.getStylesheets().add(getClass().getResource("styles.css").toExternalForm());

        primaryStage.setTitle("wxauto4j - 微信自动化机器人");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(900);
        primaryStage.setMinHeight(600);
        primaryStage.show();
    }

    @Override
    public void stop() {
        // 应用退出时的清理工作
        System.exit(0);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
