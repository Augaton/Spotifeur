package com.spotify.core.http;
public interface HttpConnection {
 void onError(int code);
 void onHeaders(HttpResponse response);
 void onBytesAvailable(byte[] bytes,int count);
 void onComplete(boolean cached);
 void onRedirect();
}
