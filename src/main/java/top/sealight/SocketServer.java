package top.sealight;


import java.io.IOException;
import java.net.ServerSocket;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class SocketServer {
    private ServerSocket serverSocket;
    private ExecutorService threadPoll;

    SocketServer(int port ,int maxClients){
        try{
            //创建serverSocket的同时绑定到制定端口
            serverSocket = new ServerSocket(port);
            //初始化线程池
            threadPoll = Executors.newFixedThreadPool(maxClients);
            System.out.println("服务器已启动,正在监听端口: "+port);
        } catch (IOException e) {
            System.err.println("无法绑定到端口 "+port+",请检查端口是否被占用");
            //出错后退出程序
            System.exit(-1);
        }
    }


}

