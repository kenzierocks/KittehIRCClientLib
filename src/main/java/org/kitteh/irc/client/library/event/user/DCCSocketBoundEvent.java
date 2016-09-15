package org.kitteh.irc.client.library.event.user;

import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.element.DCCExchange;
import org.kitteh.irc.client.library.element.ServerMessage;
import org.kitteh.irc.client.library.event.abstractbase.ActorEventBase;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Fired when the socket for a DCCExchange is bound, but no connection has been made yet.
 */
public class DCCSocketBoundEvent extends ActorEventBase<DCCExchange> {
    public DCCSocketBoundEvent(@Nonnull Client client, @Nonnull List<ServerMessage> originalMessages, @Nonnull DCCExchange actor) {
        super(client, originalMessages, actor);
    }
}
