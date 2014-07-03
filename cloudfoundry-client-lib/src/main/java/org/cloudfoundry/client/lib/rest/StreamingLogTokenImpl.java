package org.cloudfoundry.client.lib.rest;

import org.cloudfoundry.client.lib.StreamingLogToken;

import javax.websocket.Session;
import java.util.Timer;
import java.util.TimerTask;

public class StreamingLogTokenImpl implements StreamingLogToken {
    // The Go client uses 25 seconds which is not sufficient for some cases
    private static final long KEEP_ALIVE_TIME = 3 * 25000;

    private Timer keepAliveTimer = new Timer(true);

    private Session session;

    public StreamingLogTokenImpl(Session session) {
        this.session = session;

        keepAliveTimer.scheduleAtFixedRate(new KeepAliveTimerTask(), KEEP_ALIVE_TIME, KEEP_ALIVE_TIME);
    }
    
    public void cancel() {
        keepAliveTimer.cancel();
    }

    private class KeepAliveTimerTask extends TimerTask {
        @Override
        public void run() {
            if (session.isOpen()) {
                session.getAsyncRemote().sendText("keep alive");
            } else {
                keepAliveTimer.cancel();
            }
        }
    }
}
