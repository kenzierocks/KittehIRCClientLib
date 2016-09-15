package org.kitteh.irc.client.library.event.user;

import org.kitteh.irc.client.library.Client;
import org.kitteh.irc.client.library.element.DCCExchange;
import org.kitteh.irc.client.library.event.abstractbase.ClientEventBase;

/**
 * Fires when a {@link DCCExchange} connection fails.
 */
public class DCCFailedEvent extends ClientEventBase {

    private final String reason;
    private final Throwable cause;

    public DCCFailedEvent(Client client, String reason, Throwable cause) {
        super(client);
        this.reason = reason;
        this.cause = cause;
    }

    /**
     * @return the readable reason for the failure, or null
     */
    public String getReason() {
        return this.reason;
    }

    /**
     * @return the exception that caused the failure, or null
     */
    public Throwable getCause() {
        return this.cause;
    }

}
