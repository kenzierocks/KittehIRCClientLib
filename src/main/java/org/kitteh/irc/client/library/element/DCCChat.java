package org.kitteh.irc.client.library.element;

import javax.annotation.Nonnull;

public interface DCCChat extends DCCExchange {

    /**
     * Sends the user a message over DCC chat.
     * 
     * <p>
     * This method won't return until the chat is connected and the message has
     * been sent.
     * </p>
     *
     * @param message
     *            the message to send
     */
    void sendMessage(@Nonnull String message);

}
