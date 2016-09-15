package org.kitteh.irc.client.library.event.user;

import java.util.List;

import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.element.DCCExchange;
import org.kitteh.irc.client.library.element.ServerMessage;
import org.kitteh.irc.client.library.event.abstractbase.ActorEventBase;

/**
 * Fires when a {@link DCCExchange} is terminated.
 */
public class DCCConnectionClosedEvent extends ActorEventBase<DCCExchange> {

    protected DCCConnectionClosedEvent(Client client, List<ServerMessage> originalMessages, DCCExchange actor) {
        super(client, originalMessages, actor);
    }

}
