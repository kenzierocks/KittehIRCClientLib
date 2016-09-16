package org.kitteh.irc.client.library.event.user;

import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.element.ServerMessage;
import org.kitteh.irc.client.library.element.User;
import org.kitteh.irc.client.library.event.abstractbase.ActorEventBase;
import org.kitteh.irc.client.library.util.Sanity;

import javax.annotation.Nonnull;
import java.util.List;

public class DCCRequestEvent extends ActorEventBase<User> {
    private final String type;
    private final String ip;
    private final int port;

    public DCCRequestEvent(@Nonnull Client client, @Nonnull List<ServerMessage> originalMessages, @Nonnull String type, @Nonnull String ip, int port, @Nonnull User actor) {
        super(client, originalMessages, actor);
        Sanity.nullCheck(type, "type cannot be null");
        Sanity.nullCheck(ip, "ip cannot be null");
        this.type = type;
        this.ip = ip;
        this.port = port;
    }

    public String getType() {
        return this.type;
    }

    public String getIp() {
        return this.ip;
    }

    public int getPort() {
        return this.port;
    }

    /**
     * Accepts the request and connect to the socket.
     *
     * <p>This will trigger {@link DCCConnectedEvent} if successful and
     * {@link DCCFailedEvent} otherwise.</p>
     */
    public void accept() {

    }
}
