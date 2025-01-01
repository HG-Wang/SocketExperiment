package top.sealight;


import java.io.*;
import java.net.ServerSocket;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class SocketServer {
    private ServerSocket serverSocket;
    private ExecutorService threadPoll;
    //使用 ConcurrentHashMap 存储所有连接的客户端，key 为客户端地址，value为对应的输出流
    private static Map<String,BufferedWriter> clientWriters = new ConcurrentHashMap<>();
    private volatile boolean isRunning = true;

    //控制台输入处理线程
    private void startConsoleThread(){
        new Thread(
                ()->{
                    try(BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {
                       printHelp();

                        String line;
                        while((line = consoleReader.readLine())!=null){
                            handleConsoleCommand(line.trim());

                        }

                    } catch (IOException e) {
                        System.err.println("服务器控制台输入处理异常: "+e.getMessage());
                    }
                }
        ).start();

    }

    private void printHelp(){
        System.out.println("\n可用命令:");
        System.out.println("1. list - 显示所有在线客户端");
        System.out.println("2. send <客户端ID> <消息> - 将消息发送给指定客户端");
        System.out.println("3  all <消息内容> - 将消息发送给所有客户端");
        System.out.println("4. kick <客户端ID> - 断开指定客户端的连接");
        System.out.println("5. shutdown - 关闭服务器");
        System.out.println("6. help - 显示此帮助信息\n");
    }

    // 处理控制台命令
    private void handleConsoleCommand(String command) {
        if (command.isEmpty()) {
            return;
        }
        // 处理 help 命令
        else if (command.equalsIgnoreCase("help")) {
            printHelp();
            return;
        }
        // 处理 list 命令
        else if (command.equalsIgnoreCase("list")) {
            listClients();
            return;
        }
        // 处理 shutdown 命令
        else if (command.equalsIgnoreCase("shutdown")) {
            shutdown();
            return;
        }
        // 处理 all 命令
        else if (command.startsWith("all ")) {
            String message = command.substring(4).trim();
            if (!message.isEmpty()) {
                broadcastMessage("[广播] " + message);
                System.out.println("已发送广播消息");
            } else {
                System.out.println("错误: 消息内容不能为空");
            }
        }
        // 处理 send 命令
        else if (command.startsWith("send ")) {
            String[] parts = command.substring(5).trim().split(" ", 2);
            if (parts.length != 2 || parts[1].isEmpty()) {
                System.out.println("错误: 命令格式不正确。 使用方式： send <客户端ID> <消息>");
                return;
            }
            String clientId = parts[0];
            String message = parts[1];
            sendToClientById(clientId, message);
            return;
        }
        //处理 kick 命令
        else if (command.startsWith("kick ")) {
            String clientId = command.substring(5).trim();
            kickClient(clientId);
        }
        // 未知命令
        else {
            System.out.println("未知命令。 输入‘help’ 查看可用命令。");
        }
    }

    //关闭服务器
    private void shutdown(){
        System.out.println("正在关闭服务器...");
        isRunning = false; //设置运行标志为 false;
        //向所有客户端发送关闭消息
        broadcastMessage("SEVER_COMMAND_DISCONNECT:服务器即将关闭");
        //关闭所有客户端连接
        for (BufferedWriter writer:clientWriters.values()){
            try{
                writer.close();
            } catch (IOException e) {
                //强制关闭,不予处理关闭异常
            }
        }
        clientWriters.clear();  //清空客户端列表
        threadPoll.shutdown();  //关闭线程池
        try{
            //等待所有任务完成
            if(!threadPoll.awaitTermination(5, TimeUnit.SECONDS)){
                threadPoll.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPoll.shutdownNow();
        }

        //关闭服务器 Socket
        try{
            if(serverSocket != null && !serverSocket.isClosed()){
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("关闭服务器 Socket 发生错误: "+e.getMessage());
        }

        System.out.println("服务器已关闭");
        System.exit(0);
    }

    //踢出指定客户端
    private void kickClient(String clientId){

    }

    private static void listClients(){
        if(clientWriters.isEmpty()){
            System.out.println("当前没有客户端连接");
            return;
        }

        System.out.println("\n当前在线服务端列表: ");
        int id = 1;
        for(String clientAddress : clientWriters.keySet()){
            System.out.printf("%d. %s%n",id++,clientAddress);
        }
        System.out.println();
    }

    //通过客户端ID发送消息
    private void sendToClientById(String clientId,String message){
        try{
            int id = Integer.parseInt(clientId);
            if(id <= 0 || id > clientWriters.size()){
                System.out.println("错误: 无效的客户端ID。使用 ‘list’ 命令查看当前在线客户端。");
                return;
            }
            //获取对应ID的客户端地址
            String targetAddress = (String) clientWriters.keySet().toArray()[id-1];
            sendToClient(targetAddress,message);
        } catch (NumberFormatException e) {
            System.err.println("错误: 客户端ID必须是数字");
        }
    }

    //广播消息给所有客户端
    private void broadcastMessage(String message){
        for(Map.Entry<String,BufferedWriter> entry : clientWriters.entrySet()) {
            try {
                BufferedWriter writer = entry.getValue();
                writer.write(message);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                System.err.println("向客户端 "+entry.getKey()+" 发送消息失败: "+e.getMessage());
            }
        }
    }

    //发送消息给指定客户端
    private void sendToClient(String clientAddress,String message){
        BufferedWriter writer = clientWriters.get(clientAddress);
        if(writer!=null){
            try{
                writer.write(message);
                writer.newLine();
                writer.flush();
                System.out.println("消息已发送至 "+clientAddress);
            }catch (IOException e){
                System.err.println("向客户端 "+clientAddress+" 发送消息失败: "+e.getMessage());
            }
        }else {
            System.out.println("客户端 "+clientAddress +" 不存在或已断开连接");
        }
    }

    SocketServer(int port ,int maxClients){
        try{
            //创建serverSocket的同时绑定到制定端口
            serverSocket = new ServerSocket(port);
            //初始化线程池
            threadPoll = Executors.newFixedThreadPool(maxClients);
            System.out.println("服务器已启动,正在监听端口: "+port);

            //启动服务端控制台输入处理线程
            startConsoleThread();
        } catch (IOException e) {
            System.err.println("无法绑定到端口 "+port+",请检查端口是否被占用");
            //出错后退出程序
            System.exit(-1);
        }
    }

    public void start(){
        while (isRunning){
            try{
                //等待客户端连接
                Socket clientSocket = serverSocket.accept();
                if(!isRunning){
                    clientSocket.close();
                    break;
                }
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
        private String clientAddress;

        public ClientHandler(Socket socket){
            this.socket = socket;
            this.clientAddress = socket.getRemoteSocketAddress().toString();
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
                //将客户端的 writer 添加到 map 中
                clientWriters.put(clientAddress,writer);

                //发送欢迎消息
                writer.write("欢迎连接到服务器! 您的地址是: "+clientAddress);
                writer.newLine();
                writer.flush();

                String line;
                //接收并打印客户端消息
                while((line = reader.readLine())!= null){
                    System.out.println("客户端< "+socket.getRemoteSocketAddress() +"> 消息：" +line);
                }
            }catch(IOException e) {
                System.err.println("客户端通信异常: "+e.getMessage());
            }finally {
                //清理资源
                clientWriters.remove(clientAddress);
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

