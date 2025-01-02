package top.sealight;

import java.io.*;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SocketServer {
    private final ServerSocket serverSocket;
    private final ExecutorService threadPool;
    // 使用 ConcurrentHashMap 存储所有连接的客户端，key 为“客户端地址”，value 为对应的输出流
    private static final Map<String, BufferedWriter> CLIENT_WRITERS = new ConcurrentHashMap<>();
    private volatile boolean isRunning = true;

    /**
     * 构造方法：创建 ServerSocket 并绑定指定端口，同时初始化线程池
     */
    public SocketServer(int port, int maxClients) {
        try {
            serverSocket = new ServerSocket(port);
            threadPool = Executors.newFixedThreadPool(maxClients);
            System.out.println("服务器已启动，正在监听端口: " + port);
            startConsoleThread();
        } catch (IOException e) {
            System.err.println("无法绑定到端口 " + port + "，请检查端口是否被占用: " + e.getMessage());
            System.exit(-1); // 出错后退出程序
            throw new RuntimeException(e); // 仅为保证编译通过，一般不会执行到这里
        }
    }

    /**
     * 启动服务器，循环监听客户端连接
     */
    public void start() {
        while (isRunning) {
            try {
                Socket clientSocket = serverSocket.accept();
                if (!isRunning) {
                    clientSocket.close();
                    break;
                }
                System.out.println("有客户端连接: " + clientSocket.getRemoteSocketAddress());
                threadPool.execute(new ClientHandler(clientSocket));
            } catch (IOException e) {
                if (isRunning) {
                    System.err.println("接收客户端连接时出现错误: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 开启服务端控制台输入处理线程，接收并执行命令
     */
    private void startConsoleThread() {
        new Thread(() -> {
            try (BufferedReader consoleReader = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                printHelp();
                String line;
                while ((line = consoleReader.readLine()) != null) {
                    handleConsoleCommand(line.trim());
                }
            } catch (IOException e) {
                System.err.println("服务器控制台输入处理异常: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 打印可用命令的帮助信息
     */
    private void printHelp() {
        System.out.println("""
                
                可用命令:
                1. list - 显示所有在线客户端
                2. send <客户端ID> <消息> - 将消息发送给指定客户端
                3. all <消息内容> - 将消息发送给所有客户端
                4. kick <客户端ID> - 断开指定客户端的连接
                5. shutdown - 关闭服务器
                6. sendfile <客户端ID> <文件路径> - 向指定客户端发送文件
                7. help - 显示此帮助信息
                """);
    }

    /**
     * 处理控制台命令
     */
    private void handleConsoleCommand(String command) {
        if (command.isEmpty()) {
            return;
        }
        switch (getCommandKey(command)) {
            case "help" -> printHelp();
            case "list" -> listClients();
            case "shutdown" -> shutdown();
            case "all" -> {
                String message = command.substring(4).trim();
                if (!message.isEmpty()) {
                    broadcastMessage("[广播] " + message);
                    System.out.println("已发送广播消息");
                } else {
                    System.out.println("错误: 消息内容不能为空");
                }
            }
            case "send" -> handleSendCommand(command);
            case "kick" -> handleKickCommand(command);
            case "sendfile" -> handleSendFileCommand(command);
            default -> System.out.println("未知命令。输入 'help' 查看可用命令。");
        }
    }

    /**
     * 根据命令行前缀判断具体操作
     */
    private String getCommandKey(String command) {
        if (command.equalsIgnoreCase("help")) {
            return "help";
        } else if (command.equalsIgnoreCase("list")) {
            return "list";
        } else if (command.equalsIgnoreCase("shutdown")) {
            return "shutdown";
        } else if (command.startsWith("all ")) {
            return "all";
        } else if (command.startsWith("send ")) {
            return "send";
        } else if (command.startsWith("kick ")) {
            return "kick";
        } else if (command.startsWith("sendfile ")) {
            return "sendfile";
        }
        return "";
    }

    /**
     * 针对 "send" 命令的处理
     */
    private void handleSendCommand(String command) {
        String[] parts = command.substring(5).trim().split(" ", 2);
        if (parts.length != 2 || parts[1].isEmpty()) {
            System.out.println("错误: 命令格式不正确。 使用方式： send <客户端ID> <消息>");
            return;
        }
        String clientId = parts[0];
        String message = parts[1];
        sendToClientById(clientId, message);
    }

    /**
     * 针对 "kick" 命令的处理
     */
    private void handleKickCommand(String command) {
        String clientId = command.substring(5).trim();
        kickClient(clientId);
    }

    /**
     * 针对 "sendfile"的处理
     */
    private void handleSendFileCommand(String command) {
        String[] parts = command.substring(8).trim().split(" ", 2);
        if (parts.length != 2 || parts[1].isEmpty()) {
            System.out.println("错误: 命令格式不正确。使用方式： sendfile <客户端ID> <文件路径>");
            return;
        }
        String clientId = parts[0];
        String filePath = parts[1];
        sendFileToClientById(clientId, filePath);
    }

    //通过客户端ID发送文件
    private void sendFileToClientById(String clientId, String filePath) {
        int id;
        try {
            id = Integer.parseInt(clientId);
        } catch (NumberFormatException e) {
            System.err.println("错误: 客户端ID必须是数字");
            return;
        }
        if (id <= 0 || id > CLIENT_WRITERS.size()) {
            System.out.println("错误: 无效的客户端ID。使用 'list' 命令查看当前在线客户端。");
            return;
        }
        String targetAddress = (String) CLIENT_WRITERS.keySet().toArray()[id - 1];
        // 使用临时的ServerSocket获取随机可用端口
        try (ServerSocket fileServer = new ServerSocket(0)) { // 0表示随机可用端口
            int filePort = fileServer.getLocalPort();
        
            // 先发送文件传输信息给客户端
            BufferedWriter writer = CLIENT_WRITERS.get(targetAddress);
            if (writer != null) {
                writer.write("FILE_TRANSFER_PORT:" + filePort);
                writer.newLine();
                writer.flush();
            }

            // 等待客户端连接
            Socket fileSocket = fileServer.accept();
        
            // 发送文件
            File file = new File(filePath);
            try (BufferedInputStream fileInput = new BufferedInputStream(new FileInputStream(file));
                 DataOutputStream dataOut = new DataOutputStream(fileSocket.getOutputStream())) {

                dataOut.writeUTF(file.getName());
                dataOut.writeLong(file.length());

                byte[] buffer = new byte[4096];
                int len;
                while ((len = fileInput.read(buffer)) != -1) {
                    dataOut.write(buffer, 0, len);
                }
                dataOut.flush();
                System.out.println("文件已发送给客户端 " + targetAddress);
            }
        } catch (IOException e) {
            System.err.println("发送文件时出错: " + e.getMessage());
        }
    }

    /**
     * 关闭服务器，通知所有客户端并清理资源
     */
    private void shutdown() {
        System.out.println("正在关闭服务器...");
        isRunning = false;
        broadcastMessage("SERVER_COMMAND_DISCONNECT:服务器即将关闭");

        for (BufferedWriter writer : CLIENT_WRITERS.values()) {
            try {
                writer.close();
            } catch (IOException e) {
                // 忽略强制关闭异常
            }
        }
        CLIENT_WRITERS.clear();

        threadPool.shutdown();
        try {
            if (!threadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            System.err.println("关闭服务器 Socket 发生错误: " + e.getMessage());
        }

        System.out.println("服务器已关闭");
        System.exit(0);
    }

    /**
     * 分发消息给所有客户端
     */
    private void broadcastMessage(String message) {
        for (Map.Entry<String, BufferedWriter> entry : CLIENT_WRITERS.entrySet()) {
            try {
                BufferedWriter writer = entry.getValue();
                writer.write(message);
                writer.newLine();
                writer.flush();
            } catch (IOException e) {
                System.err.println("向客户端 " + entry.getKey() + " 发送消息失败: " + e.getMessage());
            }
        }
    }

    /**
     * 列出所有在线客户端
     */
    private static void listClients() {
        if (CLIENT_WRITERS.isEmpty()) {
            System.out.println("当前没有客户端连接");
            return;
        }
        System.out.println("\n当前在线客户端列表: ");
        int id = 1;
        for (String clientAddress : CLIENT_WRITERS.keySet()) {
            System.out.printf("%d. %s%n", id++, clientAddress);
        }
        System.out.println();
    }

    /**
     * 通过客户端ID发送消息
     */
    private void sendToClientById(String clientId, String message) {
        int id;
        try {
            id = Integer.parseInt(clientId);
        } catch (NumberFormatException e) {
            System.err.println("错误: 客户端ID必须是数字");
            return;
        }
        if (id <= 0 || id > CLIENT_WRITERS.size()) {
            System.out.println("错误: 无效的客户端ID。使用 'list' 命令查看当前在线客户端。");
            return;
        }
        String targetAddress = (String) CLIENT_WRITERS.keySet().toArray()[id - 1];
        sendToClient(targetAddress, message);
    }

    /**
     * 将消息发送给指定客户端
     */
    private void sendToClient(String clientAddress, String message) {
        BufferedWriter writer = CLIENT_WRITERS.get(clientAddress);
        if (writer != null) {
            try {
                writer.write(message);
                writer.newLine();
                writer.flush();
                System.out.println("消息已发送至 " + clientAddress);
            } catch (IOException e) {
                System.err.println("向客户端 " + clientAddress + " 发送消息失败: " + e.getMessage());
            }
        } else {
            System.out.println("客户端 " + clientAddress + " 不存在或已断开连接");
        }
    }

    /**
     * 踢出指定客户端
     */
    private void kickClient(String clientId) {
        int id;
        try {
            id = Integer.parseInt(clientId);
        } catch (NumberFormatException e) {
            System.err.println("错误: 客户端ID必须为数字");
            return;
        }
        if (id <= 0 || id > CLIENT_WRITERS.size()) {
            System.out.println("错误: 无效的客户端ID。使用 'list' 命令查看当前在线客户端。");
            return;
        }
        // 获取对应ID的客户端地址
        String targetAddress = (String) CLIENT_WRITERS.keySet().toArray()[id - 1];
        BufferedWriter writer = CLIENT_WRITERS.get(targetAddress);
        if (writer != null) {
            try {
                writer.write("SERVER_COMMAND_DISCONNECT:你已被服务器断开连接");
                writer.newLine();
                writer.flush();
                CLIENT_WRITERS.remove(targetAddress);
                System.out.println("已断开客户端 " + id + " <" + targetAddress + "> 的连接");
                listClients();
            } catch (IOException e) {
                System.err.println("断开客户端连接时发生错误: " + e.getMessage());
            }
        }
    }

    /**
     * 客户端处理器，用于处理每个客户端的通信与资源清理
     */
    private static class ClientHandler implements Runnable {
        private static final String MSG_FIN = "MSG_FIN";  //用来识别client消息结束标识
        private final Socket socket;
        private final String clientAddress;

        public ClientHandler(Socket socket) {
            this.socket = socket;
            this.clientAddress = socket.getRemoteSocketAddress().toString();
        }

        @Override
        public void run() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 BufferedWriter writer = new BufferedWriter(
                         new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
            ) {
                // 将客户端输出流添加到 Map
                CLIENT_WRITERS.put(clientAddress, writer);
                // 发送欢迎消息
                writer.write("欢迎连接到服务器! 您的地址是: " + clientAddress);
                writer.newLine();
                writer.flush();

                String line;

                StringBuilder msg =new StringBuilder();
                // 接收并打印客户端的消息
                while ((line = reader.readLine()) != null) {
                    if (line.endsWith(MSG_FIN)) {
                        // 添加当前行（不包含结束标记）
                        if (line.length() >= MSG_FIN.length()) {
                            msg.append(line, 0, line.length() - MSG_FIN.length());
                        }
                        // 打印完整消息
                        System.out.println(msg.toString());
                        msg.setLength(0);
                    } else {
                        System.out.println();
                        System.out.println("来自"+clientAddress+"的消息: ");
                        msg.append(line).append("\n");
                    }
                }
            } catch (IOException e) {
                // 如果是“Socket closed”异常，可忽略或做简单提示
                if (e.getMessage() != null && e.getMessage().contains("Socket closed")) {
                    System.out.println("客户端<" + clientAddress + "> 已主动断开连接");
                } else {
                    System.err.println("客户端通信异常: " + e.getMessage());
                }
            } finally {
                // 移除客户端并关闭 Socket
                CLIENT_WRITERS.remove(clientAddress);
                try {
                    socket.close();
                } catch (IOException e) {
                    System.err.println("关闭客户端 Socket 时出现异常: " + e.getMessage());
                }
                System.out.println("客户端<" + clientAddress + "> 已断开连接");
            }
        }
    }

    /**
     * 主方法入口：用于启动服务器
     */
    public static void main(String[] args) {
        int port = 12345;  // 服务器监听端口
        int maxClients = 10;  // 最大客户端连接数
        SocketServer server = new SocketServer(port, maxClients);
        server.start();
    }
}