package com.spotify.connectivity.auth;
import java.util.List;
import com.spotify.authentication.credentials.SerializableCredentials;
public interface CredentialsStorage {
 void deleteStoredCredentials(); void forgetRememberMe(String username); boolean getRememberMeMode();
 List<String> getSavedUsernames(); StoredCredentialsAndUsername getStoredCredentials();
 SerializableCredentials getStoredCredentialsForUser(String username);
 void saveLoginDetails(SerializableCredentials credential,String username,boolean remember);
 void setRememberMeMode(String username,boolean remember);
 class StoredCredentialsAndUsername { public StoredCredentialsAndUsername(SerializableCredentials c,String u,boolean r){throw new UnsupportedOperationException();} }
}
