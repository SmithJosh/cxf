/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cxf.ws.security.wss4j;

import java.io.IOException;
import java.net.URL;
import java.security.Key;
import java.util.Date;

import javax.crypto.SecretKey;
import javax.xml.namespace.QName;

import org.apache.cxf.Bus;
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.common.classloader.ClassLoaderUtils;
import org.apache.cxf.endpoint.Endpoint;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageUtils;
import org.apache.cxf.resource.ResourceManager;
import org.apache.cxf.service.model.EndpointInfo;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.cxf.ws.security.cache.CXFEHCacheReplayCache;
import org.apache.cxf.ws.security.tokenstore.SecurityToken;
import org.apache.cxf.ws.security.tokenstore.TokenStore;
import org.apache.cxf.ws.security.tokenstore.TokenStoreFactory;
import org.apache.wss4j.common.cache.ReplayCache;
import org.apache.wss4j.common.cache.ReplayCacheFactory;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.stax.ext.WSSConstants;
import org.apache.wss4j.stax.securityToken.WSSecurityTokenConstants;
import org.apache.xml.security.exceptions.XMLSecurityException;

/**
 * Some common functionality that can be shared between the WSS4JInInterceptor and the
 * UsernameTokenInterceptor.
 */
public final class WSS4JUtils {
    
    // FAULT error messages
    public static final String UNSUPPORTED_TOKEN_ERR = "An unsupported token was provided";
    public static final String UNSUPPORTED_ALGORITHM_ERR = 
        "An unsupported signature or encryption algorithm was used";
    public static final String INVALID_SECURITY_ERR = 
        "An error was discovered processing the <wsse:Security> header.";
    public static final String INVALID_SECURITY_TOKEN_ERR = 
        "An invalid security token was provided";
    public static final String FAILED_AUTHENTICATION_ERR = 
        "The security token could not be authenticated or authorized";
    public static final String FAILED_CHECK_ERR = "The signature or decryption was invalid";
    public static final String SECURITY_TOKEN_UNAVAILABLE_ERR = 
        "Referenced security token could not be retrieved";
    public static final String MESSAGE_EXPIRED_ERR = "The message has expired";

    private WSS4JUtils() {
        // complete
    }

    /**
     * Get a ReplayCache instance. It first checks to see whether caching has been explicitly 
     * enabled or disabled via the booleanKey argument. If it has been set to false then no
     * replay caching is done (for this booleanKey). If it has not been specified, then caching
     * is enabled only if we are not the initiator of the exchange. If it has been specified, then
     * caching is enabled.
     * 
     * It tries to get an instance of ReplayCache via the instanceKey argument from a 
     * contextual property, and failing that the message exchange. If it can't find any, then it
     * defaults to using an EH-Cache instance and stores that on the message exchange.
     */
    public static ReplayCache getReplayCache(
        SoapMessage message, String booleanKey, String instanceKey
    ) {
        boolean specified = false;
        Object o = message.getContextualProperty(booleanKey);
        if (o != null) {
            if (!MessageUtils.isTrue(o)) {
                return null;
            }
            specified = true;
        }

        if (!specified && MessageUtils.isRequestor(message)) {
            return null;
        }
        Endpoint ep = message.getExchange().get(Endpoint.class);
        if (ep != null && ep.getEndpointInfo() != null) {
            EndpointInfo info = ep.getEndpointInfo();
            synchronized (info) {
                ReplayCache replayCache = 
                        (ReplayCache)message.getContextualProperty(instanceKey);
                if (replayCache == null) {
                    replayCache = (ReplayCache)info.getProperty(instanceKey);
                }
                if (replayCache == null) {
                    String cacheKey = instanceKey;
                    if (info.getName() != null) {
                        cacheKey += "-" + info.getName().toString().hashCode();
                    }
                    URL configFile = getConfigFileURL(message);

                    if (ReplayCacheFactory.isEhCacheInstalled()) {
                        Bus bus = message.getExchange().getBus();
                        replayCache = new CXFEHCacheReplayCache(cacheKey, bus, configFile);
                    } else {
                        ReplayCacheFactory replayCacheFactory = ReplayCacheFactory.newInstance();
                        replayCache = replayCacheFactory.newReplayCache(cacheKey, configFile);
                    }
                    
                    info.setProperty(instanceKey, replayCache);
                }
                return replayCache;
            }
        }
        return null;
    }
    
    private static URL getConfigFileURL(Message message) {
        Object o = message.getContextualProperty(SecurityConstants.CACHE_CONFIG_FILE);
        if (o == null) {
            o = "cxf-ehcache.xml";
        }
        
        if (o instanceof String) {
            URL url = null;
            ResourceManager rm = message.getExchange().get(Bus.class).getExtension(ResourceManager.class);
            url = rm.resolveResource((String)o, URL.class);
            try {
                if (url == null) {
                    url = ClassLoaderUtils.getResource((String)o, ReplayCacheFactory.class);
                }
                if (url == null) {
                    url = new URL((String)o);
                }
                return url;
            } catch (IOException e) {
                // Do nothing
            }
        } else if (o instanceof URL) {
            return (URL)o;        
        }
        return null;
    }
    
    public static TokenStore getTokenStore(Message message) {
        return getTokenStore(message, true);
    }
    
    public static TokenStore getTokenStore(Message message, boolean create) {
        EndpointInfo info = message.getExchange().get(Endpoint.class).getEndpointInfo();
        synchronized (info) {
            TokenStore tokenStore = 
                (TokenStore)message.getContextualProperty(SecurityConstants.TOKEN_STORE_CACHE_INSTANCE);
            if (tokenStore == null) {
                tokenStore = (TokenStore)info.getProperty(SecurityConstants.TOKEN_STORE_CACHE_INSTANCE);
            }
            if (create && tokenStore == null) {
                TokenStoreFactory tokenStoreFactory = TokenStoreFactory.newInstance();
                String cacheKey = SecurityConstants.TOKEN_STORE_CACHE_INSTANCE;
                if (info.getName() != null) {
                    cacheKey += "-" + info.getName().toString().hashCode();
                }
                tokenStore = tokenStoreFactory.newTokenStore(cacheKey, message);
                info.setProperty(SecurityConstants.TOKEN_STORE_CACHE_INSTANCE, tokenStore);
            }
            return tokenStore;
        }
    }
    
    public static String parseAndStoreStreamingSecurityToken(
        org.apache.xml.security.stax.securityToken.SecurityToken securityToken,
        Message message
    ) throws XMLSecurityException {
        if (securityToken == null) {
            return null;
        }
        SecurityToken existingToken = getTokenStore(message).getToken(securityToken.getId());
        if (existingToken == null) {
            Date created = new Date();
            Date expires = new Date();
            expires.setTime(created.getTime() + 300000);

            SecurityToken cachedTok = new SecurityToken(securityToken.getId(), created, expires);
            cachedTok.setSHA1(securityToken.getSha1Identifier());

            if (securityToken.getTokenType() != null) {
                if (securityToken.getTokenType() == WSSecurityTokenConstants.EncryptedKeyToken) {
                    cachedTok.setTokenType(WSSConstants.NS_WSS_ENC_KEY_VALUE_TYPE);
                } else if (securityToken.getTokenType() == WSSecurityTokenConstants.KerberosToken) {
                    cachedTok.setTokenType(WSSConstants.NS_GSS_Kerberos5_AP_REQ);
                } else if (securityToken.getTokenType() == WSSecurityTokenConstants.Saml11Token) {
                    cachedTok.setTokenType(WSSConstants.NS_SAML11_TOKEN_PROFILE_TYPE);
                } else if (securityToken.getTokenType() == WSSecurityTokenConstants.Saml20Token) {
                    cachedTok.setTokenType(WSSConstants.NS_SAML20_TOKEN_PROFILE_TYPE);
                } else if (securityToken.getTokenType() == WSSecurityTokenConstants.SecureConversationToken
                    || securityToken.getTokenType() == WSSecurityTokenConstants.SecurityContextToken) {
                    cachedTok.setTokenType(WSSConstants.NS_WSC_05_02);
                }
            }

            for (String key : securityToken.getSecretKey().keySet()) {
                Key keyObject = securityToken.getSecretKey().get(key);
                if (keyObject != null) {
                    cachedTok.setKey(keyObject);
                    if (keyObject instanceof SecretKey) {
                        cachedTok.setSecret(keyObject.getEncoded());
                    }
                    break;
                }
            }
            getTokenStore(message).add(cachedTok);

            return cachedTok.getId();
        }
        return existingToken.getId();

    }

    /**
     * Map a WSSecurityException FaultCode to a standard error String, so as not to leak
     * internal configuration to an attacker.
     */
    public static String getSafeExceptionMessage(WSSecurityException ex) {
        // Allow a Replay Attack message to be returned, otherwise it could be confusing
        // for clients who don't understand the default caching functionality of WSS4J/CXF
        if (ex.getMessage() != null && ex.getMessage().contains("replay attack")) {
            return ex.getMessage();
        }
        
        String errorMessage = null;
        QName faultCode = ex.getFaultCode();
        if (WSConstants.UNSUPPORTED_SECURITY_TOKEN.equals(faultCode)) {
            errorMessage = UNSUPPORTED_TOKEN_ERR;
        } else if (WSConstants.UNSUPPORTED_ALGORITHM.equals(faultCode)) {
            errorMessage = UNSUPPORTED_ALGORITHM_ERR;
        } else if (WSConstants.INVALID_SECURITY.equals(faultCode)) {
            errorMessage = INVALID_SECURITY_ERR;
        } else if (WSConstants.INVALID_SECURITY_TOKEN.equals(faultCode)) {
            errorMessage = INVALID_SECURITY_TOKEN_ERR;
        } else if (WSConstants.FAILED_AUTHENTICATION.equals(faultCode)) {
            errorMessage = FAILED_AUTHENTICATION_ERR;
        } else if (WSConstants.FAILED_CHECK.equals(faultCode)) {
            errorMessage = FAILED_CHECK_ERR;
        } else if (WSConstants.SECURITY_TOKEN_UNAVAILABLE.equals(faultCode)) {
            errorMessage = SECURITY_TOKEN_UNAVAILABLE_ERR;
        } else if (WSConstants.MESSAGE_EXPIRED.equals(faultCode)) {
            errorMessage = MESSAGE_EXPIRED_ERR;
        }
        return errorMessage;
        
    }
}
