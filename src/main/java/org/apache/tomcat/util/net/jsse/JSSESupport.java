/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.tomcat.util.net.jsse;

import static org.jboss.web.CoyoteMessages.MESSAGES;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;

import javax.net.ssl.HandshakeCompletedEvent;
import javax.net.ssl.HandshakeCompletedListener;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.security.cert.X509Certificate;

import org.apache.tomcat.util.net.Constants;
import org.apache.tomcat.util.net.SSLSupport;
import org.jboss.web.CoyoteLogger;

/** JSSESupport

   Concrete implementation class for JSSE
   Support classes.

   This will only work with JDK 1.2 and up since it
   depends on JDK 1.2's certificate support

   @author EKR
   @author Craig R. McClanahan
   @author Filip Hanik
   Parts cribbed from JSSECertCompat       
   Parts cribbed from CertificatesValve
*/

class JSSESupport implements SSLSupport {
    
    protected SSLSocket ssl;
    protected SSLSession session;

    Listener listener = new Listener();

    JSSESupport(SSLSocket sock){
        ssl=sock;
        session = sock.getSession();
        sock.addHandshakeCompletedListener(listener);
    }
    
    JSSESupport(SSLSession session) {
        this.session = session;
    }

    public String getCipherSuite() throws IOException {
        // Look up the current SSLSession
        if (session == null)
            return null;
        return session.getCipherSuite();
    }

    public Object[] getPeerCertificateChain() 
        throws IOException {
        return getPeerCertificateChain(false);
    }

    protected java.security.cert.X509Certificate [] getX509Certificates(SSLSession session) 
        throws IOException {
        Certificate [] certs=null;
        try {
            certs = session.getPeerCertificates();
        } catch( Throwable t ) {
            CoyoteLogger.UTIL_LOGGER.debug("Error getting client certs", t);
            return null;
        }
        if( certs==null ) return null;
        
        java.security.cert.X509Certificate [] x509Certs = 
            new java.security.cert.X509Certificate[certs.length];
        for(int i=0; i < certs.length; i++) {
            if (certs[i] instanceof java.security.cert.X509Certificate ) {
                // always currently true with the JSSE 1.1.x
                x509Certs[i] = (java.security.cert.X509Certificate) certs[i];
            } else {
                try {
                    byte [] buffer = certs[i].getEncoded();
                    CertificateFactory cf =
                        CertificateFactory.getInstance("X.509");
                    ByteArrayInputStream stream =
                        new ByteArrayInputStream(buffer);
                    x509Certs[i] = (java.security.cert.X509Certificate) cf.generateCertificate(stream);
                } catch(Exception ex) { 
                    CoyoteLogger.UTIL_LOGGER.errorTranslatingCertificate(certs[i], ex);
                    return null;
                }
            }
            if(CoyoteLogger.UTIL_LOGGER.isTraceEnabled())
                CoyoteLogger.UTIL_LOGGER.trace("Cert #" + i + " = " + x509Certs[i]);
        }
        if(x509Certs.length < 1)
            return null;
        return x509Certs;
    }

    public Object[] getPeerCertificateChain(boolean force)
        throws IOException {
        // Look up the current SSLSession
        if (session == null)
            return null;

        // Convert JSSE's certificate format to the ones we need
	X509Certificate [] jsseCerts = null;
	try {
	    jsseCerts = session.getPeerCertificateChain();
	} catch(Exception bex) {
	    // ignore.
	}
	if (jsseCerts == null)
	    jsseCerts = new X509Certificate[0];
	if(jsseCerts.length <= 0 && force) {
	    session.invalidate();
	    handShake();
	    session = ssl.getSession();
	}
        return getX509Certificates(session);
    }

    protected void handShake() throws IOException {
        if( ssl.getWantClientAuth() ) {
            CoyoteLogger.UTIL_LOGGER.debug("No client cert sent for want");
        } else {
            ssl.setNeedClientAuth(true);
        }

        if (ssl.getEnabledCipherSuites().length == 0) {
            // Handshake is never going to be successful.
            // Assume this is because handshakes are disabled
            CoyoteLogger.UTIL_LOGGER.disabledSslRenegociation();
            session.invalidate();
            ssl.close();
            return;
        }

        InputStream in = ssl.getInputStream();
        int oldTimeout = ssl.getSoTimeout();
        ssl.setSoTimeout(1000);
        byte[] b = new byte[1];
        listener.reset();
        ssl.startHandshake();
        int maxTries = 60; // 60 * 1000 = example 1 minute time out
        for (int i = 0; i < maxTries; i++) {
            try {
                int read = in.read(b);
                if (read > 0) {
                    // Shouldn't happen as all input should have been swallowed
                    // before trying to do the handshake. If it does, something
                    // went wrong so lets bomb out now.
                    throw new SSLException(MESSAGES.sslHandshakeData());
                }
            } catch(SSLException sslex) {
                CoyoteLogger.UTIL_LOGGER.trace("SSL Error getting client Certs",sslex);
                throw sslex;
            } catch (IOException e) {
                // ignore - presumably the timeout
            }
            if (listener.completed) {
                break;
            }
        }
        ssl.setSoTimeout(oldTimeout);
        if (listener.completed == false) {
            throw new SocketException(MESSAGES.sslHandshakeTimeout());
        }

    }

    /**
     * Copied from <code>org.apache.catalina.valves.CertificateValve</code>
     */
    public Integer getKeySize() 
        throws IOException {
        // Look up the current SSLSession
        SSLSupport.CipherData c_aux[]=ciphers;
        if (session == null)
            return null;
        Integer keySize = (Integer) session.getValue(Constants.KEY_SIZE_KEY);
        if (keySize == null) {
            int size = 0;
            String cipherSuite = session.getCipherSuite();
            for (int i = 0; i < c_aux.length; i++) {
                if (cipherSuite.indexOf(c_aux[i].phrase) >= 0) {
                    size = c_aux[i].keySize;
                    break;
                }
            }
            keySize = new Integer(size);
            session.putValue(Constants.KEY_SIZE_KEY, keySize);
        }
        return keySize;
    }

    public String getSessionId()
        throws IOException {
        // Look up the current SSLSession
        if (session == null)
            return null;
        // Expose ssl_session (getId)
        byte [] ssl_session = session.getId();
        if ( ssl_session == null) 
            return null;
        StringBuilder buf=new StringBuilder("");
        for(int x=0; x<ssl_session.length; x++) {
            String digit=Integer.toHexString((int)ssl_session[x]);
            if (digit.length()<2) buf.append('0');
            if (digit.length()>2) digit=digit.substring(digit.length()-2);
            buf.append(digit);
        }
        return buf.toString();
    }


    private static class Listener implements HandshakeCompletedListener {
        volatile boolean completed = false;
        public void handshakeCompleted(HandshakeCompletedEvent event) {
            completed = true;
        }
        void reset() {
            completed = false;
        }
    }

}

