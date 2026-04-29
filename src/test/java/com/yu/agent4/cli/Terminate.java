package com.yu.agent4.cli;


import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;

public class Terminate {

//    public static void main(String[] args) throws IOException {
//
//        // 1. 创建终端（system=true 绑定系统终端）
//        Terminal terminal=TerminalBuilder.builder()
//                .system(true)
//                .build();
//
//        // 2. 创建行读取器
//        LineReader reader= LineReaderBuilder.builder()
//                .terminal(terminal)
//                .build();
//
//        // 3. 循环读取输入（提示符>）
//        String line;
//        while ((line=reader.readLine("> ")) != null) {
//            if ("exit".equalsIgnoreCase(line)) {
//                break; // 输入exit退出
//            }
//            terminal.writer().println("你输入了：" + line);
//            terminal.flush(); // 强制刷新输出
//        }
//        terminal.writer().println("再见！");
//        terminal.close(); // 关闭终端
//    }
}
