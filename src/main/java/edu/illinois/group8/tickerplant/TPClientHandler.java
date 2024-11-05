package edu.illinois.group8.tickerplant;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.Channel;
import io.netty.util.ReferenceCountUtil;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TPClientHandler extends ChannelInboundHandlerAdapter {
    private final Map<String, Channel> clients;

    public TPClientHandler() {
        clients = new ConcurrentHashMap<>();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        clients.put(ctx.channel().id().asLongText(), ctx.channel());
        System.out.println("Client connected: " + ctx.channel().id().asLongText());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        clients.remove(ctx.channel().id().asLongText());
        System.out.println("Client disconnected: " + ctx.channel().id().asLongText());
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof TextWebSocketFrame) {
            TextWebSocketFrame frame = (TextWebSocketFrame) msg;
            String message = frame.text();
            
            System.out.println("Received message from client: " + message);
        } else {
            System.out.println("Received unknown message type: " + msg.getClass().getName());
        }
    }

    public void sendDataToClients(String message) {
        for (Channel client : clients.values()) {
            client.writeAndFlush(new TextWebSocketFrame("Message from tickerplant: " + message));
        }
    }
}