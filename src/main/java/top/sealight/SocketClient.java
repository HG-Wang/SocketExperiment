package top.sealight;

import java.io.*;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

public class SocketClient {
    //用于控制读取输入时,空行作为结束标识
    private static final String DOUBLE_NEWLINE_MARKER = "";

    public static void main(String[] args){
        String severIP = "127.0.0.1";   //默认服务器IP
        int port = 12345;               //默认服务器端口

        try(
                Socket socket = new Socket(severIP,port);
                BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                BufferedReader socketReader = new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                BufferedWriter socketWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.UTF_8));
        ) {
            //与服务端通讯实现

        } catch (IOException e) {
            System.err.println("无法连接到服务器 "+severIP+":"+port + ",请检查地址是否正确或网络是否通畅");
        }
    }
}
