package com.spotify.eventsender.api;
public interface EventSenderCoreBridge {
    void queueMetricsDataSnapshotForSending(byte[] a,byte[] b);
    int send(byte[] a,byte[] b,byte[] c,EventPersistedCallback callback);
}
