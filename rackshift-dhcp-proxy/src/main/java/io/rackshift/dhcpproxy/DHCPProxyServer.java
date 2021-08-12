package io.rackshift.dhcpproxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;

public class DHCPProxyServer {
    // DHCP proxy port
    private static int port = 4011;

    DHCPProxyServer(int port) {
        DHCPProxyServer.port = port;
    }

    public void run() {
        //接收连接线程池
        EventLoopGroup g1 = new NioEventLoopGroup();
        //管理输入输出线程池
        EventLoopGroup g2 = new NioEventLoopGroup();
        try {
            Bootstrap b = new Bootstrap();
            b.group(g1).channel(NioDatagramChannel.class).handler(new DHCPPocketHandler()).option(ChannelOption.SO_BROADCAST, true);
            ChannelFuture f = b.bind(port).sync();
            System.out.println("Server listening on:" + port);
            f.channel().closeFuture().sync();
        } catch (Exception e) {
            g1.shutdownGracefully();
            g2.shutdownGracefully();
        }
    }

    public static void main(String[] args) {
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        new DHCPProxyServer(port).run();
    }

}
