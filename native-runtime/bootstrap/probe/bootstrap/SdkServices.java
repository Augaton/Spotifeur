package probe.bootstrap;

import com.spotify.esperanto.esperanto.Transport;
import io.reactivex.rxjava3.core.*;
import java.lang.reflect.*;

/** Supplies platform reachability and optional feature configuration to the native core. */
public final class SdkServices implements Transport {
    private final Object reachability;
    public SdkServices(byte[] state) {
        try { reachability=type("io.reactivex.rxjava3.subjects.BehaviorSubject").getConstructor(Object.class).newInstance((Object)state); }
        catch(Exception error){throw new IllegalStateException(error);}
    }
    private static Class<?> type(String name)throws Exception{return Class.forName(name,true,SdkServices.class.getClassLoader());}
    private static Object call(Object target,String name,Object...args)throws Exception{
        Class<?> type=target instanceof Class?(Class<?>)target:target.getClass();
        for(Method method:type.getMethods())if(method.getName().equals(name)&&method.getParameterCount()==args.length) {
            boolean compatible=true;
            for(int i=0;i<args.length;i++)if(args[i]!=null&&!method.getParameterTypes()[i].isInstance(args[i]))compatible=false;
            if(compatible)return method.invoke(target instanceof Class?null:target,args);
        }
        throw new NoSuchMethodException(type.getName()+"."+name);
    }
    public void updateReachability(byte[] state){try{call(reachability,"onNext",state);}catch(Exception error){throw new IllegalStateException(error);}}
    @Override public Observable callStream(String service,String method,byte[] bytes) {
        if(service.equals("spotify.connectivity.reachability.esperanto.proto.ReachabilityMonitor")&&method.equals("reachability"))return (Observable)reachability;
        try{return (Observable)call(type("io.reactivex.rxjava3.core.Observable"),"never");}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    @Override public Single callSingle(String service,String method,byte[] bytes) {
        try{return (Single)call(type("io.reactivex.rxjava3.core.Single"),"just",new byte[0]);}
        catch(Exception error){throw new IllegalStateException(error);}
    }
    @Override public byte[] callSync(String service,String method,byte[] bytes) {
        // An absent optional value tells the SDK to use its compiled default.
        return new byte[0];
    }
}
