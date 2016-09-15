package org.kitteh.irc.client.library.event.user;

import java.util.List;

import javax.annotation.Nonnull;

import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.element.DCCChat;
import org.kitteh.irc.client.library.element.ServerMessage;
import org.kitteh.irc.client.library.event.abstractbase.ActorMessageEventBase;
import org.kitteh.irc.client.library.event.helper.Replyable;

public class DCCMessageEvent extends ActorMessageEventBase<DCCChat> implements Replyable {

    public DCCMessageEvent(Client client, List<ServerMessage> originalMessages, DCCChat actor, String message) {
        super(client, originalMessages, actor, message);
    }

    @Override
    public void sendReply(@Nonnull String message) {
        this.getActor().sendMessage(message);
    }

}
