package org.kitteh.irc.client.library.element;

import java.net.SocketAddress;

/**
 * Represents an exchange using DCC.
 */
public interface DCCExchange extends Actor {

    /**
     * Gets the socket address of the local end.
     * May return null if not connected.
     * 
     * @return the socket address of the local end
     */
    SocketAddress getLocalSocket();

    /**
     * Gets the socket address of the remote end.
     * May return null if not connected.
     * 
     * @return the socket address of the remote end
     */
    SocketAddress getRemoteSocket();

    /**
     * Gets the connection status.
     * 
     * @return {@code true} if the exchange is connected, otherwise false
     */
    boolean isConnected();

}
