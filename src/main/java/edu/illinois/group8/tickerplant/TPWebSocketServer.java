package edu.illinois.group8.tickerplant;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.bootstrap.ServerBootstrap;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;

public class TPWebSocketServer implements Runnable {
    private final int port;
    private final TPClientHandler clientHandler;

    public TPWebSocketServer(int port, TPClientHandler clientHandler) {
        this.port = port;
        this.clientHandler = clientHandler;
    }

    @Override
    public void run() {
        EventLoopGroup bossGroup = new NioEventLoopGroup();
        EventLoopGroup workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel ch) throws Exception {
                    ch.pipeline().addLast(new HttpServerCodec());
                    ch.pipeline().addLast(new HttpObjectAggregator(65536));
                    ch.pipeline().addLast(new ChunkedWriteHandler());
                    ch.pipeline().addLast(new WebSocketServerProtocolHandler("/websocket"));
                    ch.pipeline().addLast(clientHandler);
                }
            });
    
            try {
                ChannelFuture f = b.bind(port).sync();
                System.out.println("WebSocket Server started on port: " + port);
                f.channel().closeFuture().sync();
            } catch (Exception e) {
                System.err.println("exception caught");
            }
        } finally {
            workerGroup.shutdownGracefully();
            bossGroup.shutdownGracefully();
        }
    }
}