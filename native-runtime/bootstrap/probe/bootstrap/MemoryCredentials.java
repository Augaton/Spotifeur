package probe.bootstrap;
import java.util.*;
import com.spotify.connectivity.auth.CredentialsStorage;
import com.spotify.authentication.credentials.SerializableCredentials;
/** Native session cache only. Persistent account storage belongs to the app's Keystore vault. */
public final class MemoryCredentials implements CredentialsStorage {
 private final Map<String,SerializableCredentials> credentials=new HashMap<>();
 private String selected=""; private boolean remember;
 public synchronized void deleteStoredCredentials(){credentials.clear();selected="";remember=false;}
 public synchronized void forgetRememberMe(String user){credentials.remove(user);if(selected.equals(user)){selected="";remember=false;}}
 public synchronized boolean getRememberMeMode(){return remember;}
 public synchronized List<String> getSavedUsernames(){return new ArrayList<>(credentials.keySet());}
 public synchronized StoredCredentialsAndUsername getStoredCredentials(){SerializableCredentials c=credentials.get(selected);return c==null?null:new StoredCredentialsAndUsername(c,selected,remember);}
 public synchronized SerializableCredentials getStoredCredentialsForUser(String user){return credentials.get(user);}
 public synchronized void saveLoginDetails(SerializableCredentials c,String user,boolean r){credentials.put(user,c);selected=user;remember=r;}
 public synchronized void setRememberMeMode(String user,boolean r){selected=user;remember=r;}
}
