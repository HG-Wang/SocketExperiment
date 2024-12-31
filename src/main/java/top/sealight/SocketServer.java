package top.sealight;


import java.io.IOException;
import java.net.ServerSocket;

import java.net.Socket;
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

    public void start(){
        while (true){
            try{
                //等待客户端连接
                Socket clientSocket = serverSocket.accept();
                System.out.println("有客户端连接: " + clientSocket.getRemoteSocketAddress());
                //将客户端的处理逻辑交给线程池
                threadPoll.execute(new ClientHandler(clientSocket));
            } catch (IOException e) {
                System.err.println("接收客户端连接时出现差错");
            }
        }
    }

    //用以处理客户端消息
    private static class ClientHandler implements Runnable{
        private Socket socket;

        public ClientHandler(Socket socket){
            this.socket = socket;
        }

        @Override
        public void run() {

        }
    }


}

