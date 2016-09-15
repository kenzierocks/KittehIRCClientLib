/*
 * * Copyright (C) 2013-2016 Matt Baxter http://kitteh.org
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
 * BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
 * ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
 * CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.kitteh.irc.client.library.implementation;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.ScheduledFuture;
import org.kitteh.irc.client.library.event.client.ClientConnectionClosedEvent;
import org.kitteh.irc.client.library.event.user.DCCConnectedEvent;
import org.kitteh.irc.client.library.event.user.DCCConnectionClosedEvent;
import org.kitteh.irc.client.library.event.user.DCCFailedEvent;
import org.kitteh.irc.client.library.event.user.DCCSocketBoundEvent;
import org.kitteh.irc.client.library.exception.KittehConnectionException;
import org.kitteh.irc.client.library.implementation.ActorProvider.IRCDCCExchange;
import org.kitteh.irc.client.library.util.QueueProcessingThread;
import org.kitteh.irc.client.library.util.Sanity;
import org.kitteh.irc.client.library.util.ToStringer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

final class NettyManager {
    private static final int MAX_LINE_LENGTH = 2048;

    static final class ClientConnection {
        private final InternalClient client;
        private final Channel channel;
        private final Queue<String> queue = new ConcurrentLinkedQueue<>();
        private boolean reconnect = true;
        private ScheduledFuture<?> scheduledSending;
        private ScheduledFuture<?> scheduledPing;
        private final Object scheduledSendingLock = new Object();
        private final Object immediateSendingLock = new Object();
        private boolean immediateSendingReady = false;
        private final QueueProcessingThread<String> immediateSending;

        private ClientConnection(@Nonnull final InternalClient client, @Nonnull ChannelFuture channelFuture) {
            this.client = client;
            this.channel = channelFuture.channel();

            this.immediateSending = new QueueProcessingThread<String>("Kitteh IRC Client Immediate Sending Queue (" + client.getName() + ')') {
                @Override
                protected void processElement(String message) {
                    synchronized (ClientConnection.this.immediateSendingLock) {
                        if (!ClientConnection.this.immediateSendingReady) {
                            try {
                                ClientConnection.this.immediateSendingLock.wait();
                            } catch (InterruptedException e) {
                                return;
                            }
                        }
                        ClientConnection.this.channel.writeAndFlush(message);
                    }
                }
            };

            channelFuture.addListener(future -> {
                if (future.isSuccess()) {
                    this.buildOurFutureTogether();
                    synchronized (ClientConnection.this.immediateSendingLock) {
                        this.immediateSendingReady = true;
                        this.immediateSendingLock.notify();
                    }
                } else {
                    this.client.getExceptionListener().queue(new KittehConnectionException(future.cause(), false));
                    this.scheduleReconnect();
                    removeClientConnection(ClientConnection.this, ClientConnection.this.reconnect);
                }
            });
        }

        private void buildOurFutureTogether() {
            // Outbound - Processed in pipeline back to front.
            this.channel.pipeline().addFirst("[OUTPUT] Output listener", new MessageToMessageEncoder<String>() {

                @Override
                protected void encode(ChannelHandlerContext ctx, String msg, List<Object> out) throws Exception {
                    ClientConnection.this.client.getOutputListener().queue(msg);
                    out.add(msg);
                }
            });
            this.channel.pipeline().addFirst("[OUTPUT] Add line breaks", new MessageToMessageEncoder<String>() {

                @Override
                protected void encode(ChannelHandlerContext ctx, String msg, List<Object> out) throws Exception {
                    out.add(msg + "\r\n");
                }
            });
            this.channel.pipeline().addFirst("[OUTPUT] String encoder", new StringEncoder(CharsetUtil.UTF_8));

            // Handle timeout
            this.channel.pipeline().addLast("[INPUT] Idle state handler", new IdleStateHandler(250, 0, 0));
            this.channel.pipeline().addLast("[INPUT] Catch idle", new ChannelDuplexHandler() {

                @Override
                public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
                    if (evt instanceof IdleStateEvent) {
                        IdleStateEvent e = (IdleStateEvent) evt;
                        if ((e.state() == IdleState.READER_IDLE) && e.isFirst()) {
                            ClientConnection.this.shutdown("Reconnecting...", true);
                        }
                    }
                }
            });

            // Inbound
            this.channel.pipeline().addLast("[INPUT] Line splitter", new DelimiterBasedFrameDecoder(MAX_LINE_LENGTH, Unpooled.wrappedBuffer(new byte[]{(byte) '\r', (byte) '\n'})));
            this.channel.pipeline().addLast("[INPUT] String decoder", new StringDecoder(CharsetUtil.UTF_8));
            this.channel.pipeline().addLast("[INPUT] Send to client", new SimpleChannelInboundHandler<String>() {

                @Override
                protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
                    if (msg == null) {
                        return;
                    }
                    ClientConnection.this.client.getInputListener().queue(msg);
                    ClientConnection.this.client.processLine(msg);
                }
            });

            // SSL
            if (this.client.getConfig().getNotNull(Config.SSL)) {
                try {
                    File keyCertChainFile = this.client.getConfig().get(Config.SSL_KEY_CERT_CHAIN);
                    File keyFile = this.client.getConfig().get(Config.SSL_KEY);
                    String keyPassword = this.client.getConfig().get(Config.SSL_KEY_PASSWORD);
                    TrustManagerFactory factory = this.client.getConfig().get(Config.SSL_TRUST_MANAGER_FACTORY);
                    if (factory == null) {
                        factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                        factory.init((KeyStore) null);
                    }
                    SslContext sslContext = SslContextBuilder.forClient().trustManager(factory).keyManager(keyCertChainFile, keyFile, keyPassword).build();
                    this.channel.pipeline().addFirst(sslContext.newHandler(this.channel.alloc()));
                } catch (SSLException | NoSuchAlgorithmException | KeyStoreException e) {
                    this.client.getExceptionListener().queue(new KittehConnectionException(e, true));
                    return;
                }
            }

            // Exception handling
            this.channel.pipeline().addLast("[INPUT] Exception handler", new ChannelInboundHandlerAdapter() {

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                    if (cause instanceof Exception) {
                        ClientConnection.this.handleException((Exception) cause);
                    }
                }
            });
            this.channel.pipeline().addFirst("[OUTPUT] Exception handler", new ChannelOutboundHandlerAdapter() {

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                    if (cause instanceof Exception) {
                        ClientConnection.this.handleException((Exception) cause);
                    }
                }
            });

            // Clean up on disconnect
            this.channel.closeFuture().addListener(futureListener -> {
                if (ClientConnection.this.reconnect) {
                    this.scheduleReconnect();
                }
                this.immediateSending.interrupt();
                ClientConnection.this.client.getEventManager().callEvent(new ClientConnectionClosedEvent(ClientConnection.this.client, ClientConnection.this.reconnect));
                removeClientConnection(ClientConnection.this, ClientConnection.this.reconnect);
            });
        }

        private void scheduleReconnect() {
            ClientConnection.this.channel.eventLoop().schedule(ClientConnection.this.client::connect, 5, TimeUnit.SECONDS);
        }

        void sendMessage(@Nonnull String message, boolean priority) {
            this.sendMessage(message, priority, false);
        }

        void sendMessage(@Nonnull String message, boolean priority, boolean avoidDuplicates) {
            if (priority) {
                this.immediateSending.queue(message);
            } else if (!avoidDuplicates || !this.queue.contains(message)) {
                this.queue.add(message);
            }
        }

        void shutdown(@Nullable String message) {
            this.shutdown(message, false);
        }

        void startSending() {
            this.schedule(true);
        }

        void updateScheduling() {
            this.schedule(false);
        }

        private void handleException(Exception thrown) {
            this.client.getExceptionListener().queue(thrown);
            if (thrown instanceof IOException) {
                this.shutdown("IO Error. Reconnecting...", true);
            }
        }

        private void schedule(boolean force) {
            synchronized (this.scheduledSendingLock) {
                if (!force && (this.scheduledSending == null)) {
                    return;
                }
                long delay = 0;
                if (this.scheduledSending != null) {
                    delay = this.scheduledSending.getDelay(TimeUnit.MILLISECONDS); // Negligible added delay processing this
                    this.scheduledSending.cancel(false);
                }
                if (this.scheduledPing != null) {
                    this.scheduledPing.cancel(false);
                }
                this.scheduledSending = this.channel.eventLoop().scheduleAtFixedRate(() -> {
                    String message = ClientConnection.this.queue.poll();
                    if (message != null) {
                        ClientConnection.this.channel.writeAndFlush(message);
                    }
                }, delay, this.client.getMessageDelay(), TimeUnit.MILLISECONDS);
                this.scheduledPing = this.channel.eventLoop().scheduleWithFixedDelay(this.client::ping, 60, 60, TimeUnit.SECONDS);
            }
        }

        private void shutdown(@Nullable String message, boolean reconnect) {
            this.reconnect = reconnect;

            this.sendMessage("QUIT" + ((message != null) ? (" :" + message) : ""), true);
            this.channel.close();
        }

        @Nonnull
        @Override
        public String toString() {
            return new ToStringer(this).add("client", this.client).toString();
        }
    }

    static class DCCConnection extends ChannelInitializer<SocketChannel> {

        private final IRCDCCExchange exchange;
        private final InternalClient client;
        // Only allow one connection per DCC. Weird, I know.
        private boolean oneConnection;

        private DCCConnection(IRCDCCExchange ex, InternalClient client) {
            this.exchange = ex;
            this.client = client;
        }

        @Override
        public void initChannel(SocketChannel channel) throws Exception {
            if (this.oneConnection) {
                channel.close();
                return;
            }
            this.oneConnection = true;
            this.exchange.setNettyChannel(channel);
            dccConnections.computeIfAbsent(this.client, c -> new ArrayList<>()).add(channel);

            // Outbound - Processed in pipeline back to front.
            channel.pipeline().addFirst("[OUTPUT] Output listener", new MessageToMessageEncoder<String>() {
                @Override
                protected void encode(ChannelHandlerContext ctx, String msg, List<Object> out) throws Exception {
                    DCCConnection.this.client.getOutputListener().queue(msg);
                    out.add(msg);
                }
            });
            channel.pipeline().addFirst("[OUTPUT] Add line breaks", new MessageToMessageEncoder<String>() {
                @Override
                protected void encode(ChannelHandlerContext ctx, String msg, List<Object> out) throws Exception {
                    out.add(msg + "\r\n");
                }
            });
            channel.pipeline().addFirst("[OUTPUT] String encoder", new StringEncoder(CharsetUtil.UTF_8));

            // Inbound & exceptions
            String successHandler = "[INPUT] Success Handler";
            channel.pipeline().addFirst("[INPUT] Exception Handler", new ChannelInboundHandlerAdapter() {
                private boolean firstRemove;

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                    if (!this.firstRemove) {
                        ctx.channel().pipeline().remove(successHandler);
                        ctx.channel().close();
                        this.firstRemove = true;
                    }
                    cause.printStackTrace();
                    DCCConnection.this.client.getEventManager().callEvent(new DCCFailedEvent(DCCConnection.this.client, "Netty exception", cause));
                }
            });
            channel.pipeline().addFirst(successHandler, new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                    DCCConnection.this.exchange.setRemoteAddress(ctx.channel().remoteAddress());
                    DCCConnection.this.exchange.setConnected(true);
                    DCCConnection.this.client.getEventManager().callEvent(new DCCConnectedEvent(DCCConnection.this.client, Collections.emptyList(), DCCConnection.this.exchange.snapshot()));
                    ctx.channel().pipeline().remove(this);
                }
            });
            channel.pipeline().addLast("[INPUT] Line splitter", new DelimiterBasedFrameDecoder(MAX_LINE_LENGTH, Unpooled.wrappedBuffer(new byte[]{(byte) '\r', (byte) '\n'})));
            channel.pipeline().addLast("[INPUT] String decoder", new StringDecoder(StandardCharsets.UTF_8));
            channel.pipeline().addLast("[INPUT] DCC Receive", new SimpleChannelInboundHandler<String>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, String msg) throws Exception {
                    if (msg == null) {
                        return;
                    }
                    DCCConnection.this.exchange.onMessage(msg);
                }
            });
            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelInactive(ChannelHandlerContext ctx) throws Exception {
                    // kill DCC when inactive
                    ctx.close();
                    DCCConnection.this.client.getEventManager().callEvent(new DCCConnectionClosedEvent(DCCConnection.this.client, Collections.emptyList(), DCCConnection.this.exchange.snapshot()));
                }
            });
        }
    }

    @Nullable
    private static Bootstrap bootstrap;
    @Nullable
    private static EventLoopGroup eventLoopGroup;
    private static final Set<ClientConnection> connections = new HashSet<>();
    private static final Map<InternalClient, List<Channel>> dccConnections = new HashMap<>();

    private NettyManager() {

    }

    private static synchronized void removeClientConnection(@Nonnull ClientConnection connection, boolean reconnecting) {
        connections.remove(connection);
        List<Channel> dcc = dccConnections.get(connection.client);
        if (dcc != null) {
            dcc.forEach(Channel::close);
        }
        if (!reconnecting && connections.isEmpty()) {
            if (eventLoopGroup != null) {
                eventLoopGroup.shutdownGracefully();
            }
            eventLoopGroup = null;
            bootstrap = null;
        }
    }

    static synchronized ClientConnection connect(@Nonnull InternalClient client) {
        if (bootstrap == null) {
            bootstrap = new Bootstrap();
            bootstrap.channel(NioSocketChannel.class);
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel channel) throws Exception {
                    // NOOP
                }
            });
            bootstrap.option(ChannelOption.TCP_NODELAY, true);
            eventLoopGroup = new NioEventLoopGroup();
            bootstrap.group(eventLoopGroup);
        }
        SocketAddress bind = client.getConfig().get(Config.BIND_ADDRESS);
        SocketAddress server = client.getConfig().getNotNull(Config.SERVER_ADDRESS);
        ClientConnection clientConnection;
        if (bind == null) {
            clientConnection = new ClientConnection(client, bootstrap.connect(server));
        } else {
            clientConnection = new ClientConnection(client, bootstrap.connect(server, bind));
        }
        connections.add(clientConnection);
        return clientConnection;
    }

    static Runnable connectDCC(InternalClient client, IRCDCCExchange exchange) {
        Sanity.nullCheck(eventLoopGroup, "A DCC connection cannot be made without a client");
        ServerBootstrap dccBootstrap = new ServerBootstrap();
        dccBootstrap.channel(NioServerSocketChannel.class);
        DCCConnection childHandler = new DCCConnection(exchange, client);
        dccBootstrap.childHandler(childHandler);
        dccBootstrap.childOption(ChannelOption.TCP_NODELAY, true);
        dccBootstrap.childOption(ChannelOption.SO_KEEPALIVE, true);
        dccBootstrap.group(eventLoopGroup);
        ChannelFuture future = dccBootstrap.bind(0);
        future.addListener(ft -> {
            if (ft.isSuccess()) {
                exchange.setLocalAddress(future.channel().localAddress());
                client.getEventManager().callEvent(new DCCSocketBoundEvent(client, Collections.emptyList(), exchange.snapshot()));
                exchange.onSocketBound();
            } else {
                client.getEventManager().callEvent(new DCCFailedEvent(client, "Failed to bind to address " + future.channel().localAddress(), ft.cause()));
            }
        });
        return () -> future.channel().close();
    }

    @Nonnull
    @Override
    public String toString() {
        return new ToStringer(this).toString();
    }
}
