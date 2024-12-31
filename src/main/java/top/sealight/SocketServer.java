package top.sealight;


import java.io.*;
import java.net.ServerSocket;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
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
            //try-with-resources
            try(
                //获取输入流,用以接收客户端消息
                BufferedReader reader = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.UTF_8));
                //获取输出流,用以向客户端发送消息
                BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                        socket.getOutputStream(),StandardCharsets.UTF_8));
            ) {
                String line;
                //接收并打印客户端消息
                while((line = reader.readLine())!= null){
                    System.out.println("客户端< "+socket.getRemoteSocketAddress() +"> 消息：" +line);

                    //将消息回显给客户端,实现双工通信
                    writer.write("服务器已收到: "+line);
                    writer.newLine();
                    writer.flush();
                }
            }catch(IOException e) {
                System.err.println("客户端通信异常: "+e.getMessage());
            }finally {
                try{
                    socket.close();
                } catch (IOException e) {
                    System.err.println("关闭客户端Socket时出现异常: "+e.getMessage());
                }
                System.out.println("客户端< "+socket.getRemoteSocketAddress() +"> 已断开连接");
            }
        }

        public static void main(String[] args) {
            int port = 12345;  //服务器监听端口
            int maxClient = 10;  //最大客户端连接个数
            //启动服务器
            SocketServer server = new SocketServer(port,maxClient);
            server.start();

        }
    }


}

