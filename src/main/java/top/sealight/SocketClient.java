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
            System.out.println("已连接到服务器: "+severIP+":"+port);
            //启动一个线程专门负责接收来自服务器的消息并打印到本地
            Thread receiveThread = new Thread(
                    ()->{
                        try{
                            String response;
                            while ((response = socketReader.readLine())!=null){
                                System.out.println("Server: "+response);
                            }
                        }catch(IOException e){
                            System.err.println("接收服务器时出现异常: "+e.getMessage());
                        }
                    }
            );
            receiveThread.start();

            //发送消息给服务器，直到检测到两次连按回车（空行）表示输入结束
            System.out.println("请输入要发送给服务器的消息，连续两次回车结束输入：");
            String line;
            int emptyLineCount = 0; //记录连续空行数

            while(true){
                line = consoleReader.readLine();
                if(line == null){
                    break; //处理控制台输入关闭等情况
                }
                if(line.equals(DOUBLE_NEWLINE_MARKER)){
                    emptyLineCount++;
                    if(emptyLineCount == 2){
                        System.out.println("输入结束，即将断开连接...");
                        break;
                    }
                    continue;
                }else {
                    emptyLineCount =0;
                }


                //将输入发送给服务器
                socketWriter.write(line);
                socketWriter.newLine();
                socketWriter.flush();
            }
            //关闭输出流，让服务端 readline() 返回 null
            socket.shutdownOutput();
            //等待接受进程结束
            receiveThread.join();
        } catch (IOException e) {
            System.err.println("无法连接到服务器 "+severIP+":"+port + ",请检查地址是否正确或网络是否通畅");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("客户端被中断： "+e.getMessage());
        }
    }
}
