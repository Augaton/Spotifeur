package com.spotify.esperanto.esperanto;
public interface Transport {
    io.reactivex.rxjava3.core.Single callSingle(String service,String method,byte[] body);
    io.reactivex.rxjava3.core.Observable callStream(String service,String method,byte[] body);
    byte[] callSync(String service,String method,byte[] body);
}
