package service;

import org.junit.Test;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @Author shuaifeng
 * @Since 2019/9/3
 */
public class TestNIOBuffer {

    @Test
    public void testBuffer() {
        int bufferSize = 10;
        int bufferBound = 20;
        int[] intArr = new int[10];

        IntBuffer buffer = IntBuffer.allocate(bufferSize);

        for (int index = 0; index < bufferSize; index++) {
            int currentRandomInt = new SecureRandom().nextInt(bufferBound);
            buffer.put(currentRandomInt);
        }

        buffer.flip();

        while (buffer.hasRemaining()) {
            int currentInt = buffer.get();
            System.out.println(currentInt);
        }
    }

    @Test
    public void testFileChannel() throws IOException {
        FileInputStream fileInputStream = new FileInputStream("pom.xml");
        FileChannel fileChannel = fileInputStream.getChannel();

        ByteBuffer byteBuffer = ByteBuffer.allocate(1024);
        fileChannel.read(byteBuffer);
        byteBuffer.flip();

        StringBuilder sb = new StringBuilder(1024);
        while (byteBuffer.hasRemaining()) {
            sb.append((char)byteBuffer.get());
        }
        System.out.println(sb.toString());
    }

    // 测试读取并写入新文件
    @Test
    public void testReadAndWriteNewFileByChannel() throws IOException {
        long startTime = System.currentTimeMillis();
        FileInputStream fileInputStream = new FileInputStream("pom.xml");
        FileOutputStream fileOutputStream = new FileOutputStream("pom_new.xml");

        FileChannel inputChannel = fileInputStream.getChannel();
        FileChannel outputChannel = fileOutputStream.getChannel();


        ByteBuffer buffer = ByteBuffer.allocate(1024);
        while (-1 != inputChannel.read(buffer)) {
            buffer.flip();
            outputChannel.write(buffer);
            // 注意这里调用clear 才是正确的姿势
            buffer.clear();
        }

        inputChannel.close();
        fileInputStream.close();
        outputChannel.close();
        fileOutputStream.close();

        long endTime = System.currentTimeMillis();

        System.out.println("消耗时间: " + (endTime - startTime));
    }

    // 创建一个只读buffer
    @Test
    public void testReadOnlyBuffer() {
        String str = "hello world!";
        ByteBuffer buffer = ByteBuffer.wrap(str.getBytes()).asReadOnlyBuffer();
        buffer.put((byte)1);
    }

    // 测试Scatter、Gatter
    // 当前程序有点问题：每次SocketChannel 的read 方法，其实不一定能将所有的buffer 写满，for 循环的位置不对
    @Test
    public void testServerSocket() throws IOException {
        ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
        SocketAddress socketAddress = new InetSocketAddress(8088);
        serverSocketChannel.bind(socketAddress);

        SocketChannel socketChannel = serverSocketChannel.accept();

        // 构建文件输出channel
        FileOutputStream fileOutputStream = new FileOutputStream("http.txt");
        FileChannel fileChannel = fileOutputStream.getChannel();

        // 从Channel 中，通过多个buffer 读取内容，并将读取到的内容写入到一个文件中
        ByteBuffer byteBuffer1 = ByteBuffer.allocate(5);
        ByteBuffer byteBuffer2 = ByteBuffer.allocate(10);
        ByteBuffer byteBuffer3 = ByteBuffer.allocate(15);
        long totalBufferSize = 30;

        ByteBuffer[] bufferArr = new ByteBuffer[] {byteBuffer1, byteBuffer2, byteBuffer3};

        // 专门用于返回请求响应的buffer
        ByteBuffer socketReplyBuffer = ByteBuffer.allocate(128);

        // 外层要有一个for 循环，确保端口一直被监听
        while (true) {
            long totalReadSize = 0;
            while (totalReadSize < totalBufferSize) {
                // 如果没有请求过来，这里读取不到数据就会一直阻塞
                long currentReadSize = socketChannel.read(bufferArr);
                totalReadSize += currentReadSize;

                System.out.println("本次读取后所有的 buffer 加起来的大小为: " + totalReadSize);
            }
            // 一轮读取完成之后，将buffer 中的所有内容写入到新的文件中
            for (ByteBuffer currentBuffer : bufferArr) {
                currentBuffer.flip();
            }
            fileChannel.write(bufferArr);
            for (ByteBuffer currentBuffer : bufferArr) {
                currentBuffer.clear();
            }
            System.out.println("本次将网络请求内容写入文件完成！");

            // 这里将一个请求返回回去，否则客户端会一直等待服务端的回应
            socketReplyBuffer.clear();
            socketReplyBuffer.put(String.format("Return reply. Current time: %d", System.currentTimeMillis()).getBytes());
            socketReplyBuffer.flip();
            socketChannel.write(socketReplyBuffer);
        }
    }

        // 测试代码：开启5个socket 连接，用一个selector 统一进行处理
    // 这个示例肯定是需要用event 事件来唤醒的
    // 扩展：不同的端口用不同的buffer 来读取数据，基本就把一个客户端给实现了
    @Test
    public void testMultiConnection() throws Exception {
        int[] ports = new int[] {8081, 8082, 8083, 8084, 8085};

        Selector selector = Selector.open();

        for (int port : ports) {
            ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
            ServerSocket socket = serverSocketChannel.socket();
            SocketAddress socketAddress = new InetSocketAddress(port);
            socket.bind(socketAddress);

            ServerSocketChannel channel = socket.getChannel();
            // 注意一定需要设置
            channel.configureBlocking(false);
            channel.register(selector, SelectionKey.OP_ACCEPT);
        }

        // 初始化ByteBuffer
        ByteBuffer byteBuffer = ByteBuffer.allocate(16);

        FileOutputStream fileOutputStream = new FileOutputStream("selector.txt");
        FileChannel fileChannel = fileOutputStream.getChannel();

        // 监听selector 队列的事件
        while (true) {
            int selectKeyNums = selector.select();
            System.out.println(String.format("当前需要处理的key数量为: %d", selectKeyNums));

            Set<SelectionKey> selectKeySet = selector.selectedKeys();
            Iterator<SelectionKey> iter = selectKeySet.iterator();

            while (iter.hasNext()) {
                SelectionKey currentKey = iter.next();

                if (currentKey.isAcceptable()) {
                    // 处理开始连接事件，并重新插入一个新的读事件
                    ServerSocketChannel serverSocketChannel = (ServerSocketChannel) currentKey.channel();
                    SocketChannel socketChannel = serverSocketChannel.accept();
                    socketChannel.configureBlocking(false);
                    Socket socket = socketChannel.socket();

                   System.out.println(String.format("当前处理的事件为: %s; 处理的socket 对象为: %s; 端口为: %s",
                           "Accept", socket.toString(), socket.getLocalPort()));

                    socketChannel.register(selector, SelectionKey.OP_READ);
                }
                else if (currentKey.isReadable()) {
                    SocketChannel channel = (SocketChannel) currentKey.channel();
                    Socket socket = channel.socket();

                    System.out.println(String.format("当前处理的事件为: %s; 处理的socket 对象为: %s; 端口为: %s",
                            "Read", socket.toString(), socket.getLocalPort()));

                    byteBuffer.clear();
                    channel.read(byteBuffer);

                    channel.configureBlocking(false);
                    channel.register(selector, SelectionKey.OP_WRITE);
                }
                else if (currentKey.isWritable()) {
                    SocketChannel channel = (SocketChannel) currentKey.channel();
                    Socket socket = channel.socket();

                    System.out.println(String.format("当前处理的事件为: %s; 处理的socket 对象为: %s; 端口为: %s",
                            "Write", socket.toString(), socket.getLocalPort()));

                    byteBuffer.flip();
                    fileChannel.write(byteBuffer);

                    channel.configureBlocking(false);
                    channel.register(selector, SelectionKey.OP_READ);
                }
                iter.remove();
            }
        }
    }
}