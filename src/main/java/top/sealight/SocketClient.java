package top.sealight;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class SocketClient {
    private static final String SERVER_COMMAND_DISCONNECT = "SERVER_COMMAND_DISCONNECT:";
    private static final String DEFAULT_SERVER_IP = "127.0.0.1";
    private static final int DEFAULT_PORT = 12345;
    private static final int FILE_TRANSFER_PORT = 12346;
    private static final String EXIT_COMMAND = "exit";

    // 使用原子布尔值来安全地控制客户端状态
    private final AtomicBoolean isRunning = new AtomicBoolean(true);

    private final String serverIP;
    private final int port;
    private Socket socket;

    public SocketClient(String serverIP, int port) {
        this.serverIP = serverIP != null ? serverIP : DEFAULT_SERVER_IP;
        this.port = port > 0 ? port : DEFAULT_PORT;
    }

    public void start() {
        try {
            connectToServer();
            startMessageHandling();
            startFileReceiverServer(); //接收文件的服务端口
        } catch (IOException e) {
            System.err.printf("连接服务器失败 %s:%d - %s%n", serverIP, port, e.getMessage());
        }
    }

    //接收文件
    private void startFileReceiverServer() {
        Thread fileServerThread = new Thread(
                ()->{
                    try (ServerSocket fileServer = new ServerSocket(FILE_TRANSFER_PORT)){
                        while (isRunning.get()){
                            try(
                                Socket fileSocket = fileServer.accept();
                                DataInputStream dataIn = new DataInputStream(fileSocket.getInputStream())
                            ){
                                String fileName = dataIn.readUTF();
                                long fileSize = dataIn.readLong();
                                File savedFile = new File("received_"+fileName);
                                try (BufferedOutputStream fileOut = new BufferedOutputStream(new FileOutputStream(savedFile))){
                                    byte[] buffer = new byte[4096];
                                    long bytesRead = 0;
                                    int read;
                                    while (bytesRead < fileSize && (read = dataIn.read(buffer)) != -1){
                                        fileOut.write(buffer,0,read);
                                        bytesRead += read;
                                    }
                                    fileOut.flush();
                                    System.out.println("已接收文件: "+savedFile.getAbsoluteFile());
                                }catch (IOException e){
                                    if(isRunning.get()){
                                        System.err.println("接收文件时出现错误: "+e.getMessage());
                                    }
                                }
                            }
                        }
                    } catch (IOException e) {
                        if(isRunning.get()){
                            System.err.println("文件接收端口启动失败: "+e.getMessage());
                        }
                    }
                }
        );
        fileServerThread.setDaemon(true);
        fileServerThread.start();
    }

    private void connectToServer() throws IOException {
        socket = new Socket(serverIP, port);
        System.out.printf("已连接到服务器: %s:%d%n", serverIP, port);
    }

    private void startMessageHandling() {
        try (BufferedReader consoleReader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             BufferedReader socketReader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter socketWriter = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            // 启动接收消息的线程
            Thread receiveThread = startReceiveThread(socketReader);

            // 处理发送消息
            handleUserInput(consoleReader, socketWriter);

            // 清理资源
            cleanup(receiveThread);

        } catch (IOException | InterruptedException e) {
            handleError(e);
        }
    }

    private Thread startReceiveThread(BufferedReader socketReader) {
        Thread receiveThread = new Thread(() -> {
            try {
                receiveMessages(socketReader);
            } catch (IOException e) {
                if (isRunning.get()) {
                    System.err.println("接收服务器消息时出现异常: " + e.getMessage());
                }
            }
        });
        receiveThread.setDaemon(true); // 设置为守护线程
        receiveThread.start();
        return receiveThread;
    }

    private void receiveMessages(BufferedReader socketReader) throws IOException {
        String response;
        while (isRunning.get() && (response = socketReader.readLine()) != null) {
            if (response.startsWith(SERVER_COMMAND_DISCONNECT)) {
                handleServerDisconnect(response);
                break;
            }
            System.out.println("Server: " + response);
        }
    }

    private void handleServerDisconnect(String response) {
        String message = response.substring(SERVER_COMMAND_DISCONNECT.length());
        System.out.println("服务器通知: " + message);
        shutdown();
    }

    private void handleUserInput(BufferedReader consoleReader, BufferedWriter socketWriter) throws IOException {
        System.out.println("请输入要发送给服务器的消息，连续两次回车发送，输入exit退出：");
        MessageBuilder messageBuilder = new MessageBuilder();

        while (isRunning.get()) {
            String line = consoleReader.readLine();
            if (line == null || EXIT_COMMAND.equalsIgnoreCase(line.trim())) {
                break;
            }

            if (messageBuilder.appendLine(line)) {
                String message = messageBuilder.getMessage();
                if (!message.isEmpty()) {
                    sendMessage(socketWriter, message);
                }
                messageBuilder.reset();
                System.out.println("消息已发送，请继续输入（连续两次回车发送，输入exit退出）：");
            }
        }
    }

    private void sendMessage(BufferedWriter writer, String message) throws IOException {
        writer.write(message);
        writer.newLine();
        writer.flush();
    }

    private void cleanup(Thread receiveThread) throws IOException, InterruptedException {
        shutdown();
        if (socket != null && !socket.isClosed()) {
            socket.shutdownOutput();
        }
        receiveThread.join(5000); // 等待接收线程最多5秒
    }

    private void shutdown() {
        isRunning.set(false);
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("关闭socket时出现异常: " + e.getMessage());
        }
    }

    private void handleError(Exception e) {
        if (e instanceof SocketException && !isRunning.get()) {
            // 正常关闭导致的异常，忽略
            return;
        }
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            System.err.println("客户端被中断：" + e.getMessage());
        } else {
            System.err.println("客户端运行异常：" + e.getMessage());
        }
    }

    // 内部类用于处理消息构建
    private static class MessageBuilder {
        private final StringBuilder content = new StringBuilder();
        private int emptyLineCount = 0;

        public boolean appendLine(String line) {
            if (line.trim().isEmpty()) {
                emptyLineCount++;
                return emptyLineCount == 1;
            }

            emptyLineCount = 0;
            if (content.length() > 0) {
                content.append("\n");
            }
            content.append(line);
            return false;
        }

        public String getMessage() {
            return content.toString().trim();
        }

        public void reset() {
            content.setLength(0);
            emptyLineCount = 0;
        }
    }

    public static void main(String[] args) {
        SocketClient client = new SocketClient(DEFAULT_SERVER_IP, DEFAULT_PORT);
        client.start();
    }
}