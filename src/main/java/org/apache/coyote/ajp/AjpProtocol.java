/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.coyote.ajp;

import java.net.InetAddress;
import java.net.Socket;
import java.net.URLEncoder;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.MBeanRegistration;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.coyote.Adapter;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.RequestGroupInfo;
import org.apache.coyote.RequestInfo;
import org.apache.tomcat.util.modeler.Registry;
import org.apache.tomcat.util.net.JIoEndpoint;
import org.apache.tomcat.util.net.SocketStatus;
import org.apache.tomcat.util.net.JIoEndpoint.Handler;
import org.jboss.web.CoyoteLogger;


/**
 * Abstract the protocol implementation, including threading, etc.
 * Processor is single threaded and specific to stream-based protocols,
 * will not fit Jk protocols like JNI.
 *
 * @author Remy Maucherat
 * @author Costin Manolache
 */
public class AjpProtocol 
    implements ProtocolHandler, MBeanRegistration {
    
    
    // ------------------------------------------------------------ Constructor


    public AjpProtocol() {
        cHandler = new AjpConnectionHandler(this);
        setSoLinger(Constants.DEFAULT_CONNECTION_LINGER);
        setSoTimeout(Constants.DEFAULT_CONNECTION_TIMEOUT);
        //setServerSoTimeout(Constants.DEFAULT_SERVER_SOCKET_TIMEOUT);
        setTcpNoDelay(Constants.DEFAULT_TCP_NO_DELAY);
    }

    
    // ----------------------------------------------------- Instance Variables


    protected ObjectName tpOname;
    
    
    protected ObjectName rgOname;


    /**
     * Associated java.io endpoint.
     */
    protected JIoEndpoint endpoint = new JIoEndpoint();


    /**
     * Configuration attributes.
     */
    protected Hashtable attributes = new Hashtable();


    /**
     * Adapter which will process the requests recieved by this endpoint.
     */
    private Adapter adapter;
    
    
    /**
     * Connection handler for AJP.
     */
    private AjpConnectionHandler cHandler;


    // --------------------------------------------------------- Public Methods


    /** 
     * Pass config info
     */
    public void setAttribute(String name, Object value) {
        attributes.put(name, value);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }


    public Iterator getAttributeNames() {
        return attributes.keySet().iterator();
    }


    /**
     * The adapter, used to call the connector
     */
    public void setAdapter(Adapter adapter) {
        this.adapter = adapter;
    }


    public Adapter getAdapter() {
        return adapter;
    }


    public boolean hasIoEvents() {
        return false;
    }

    public RequestGroupInfo getRequestGroupInfo() {
        return cHandler.global;
    }


    /** Start the protocol
     */
    public void init() throws Exception {
        endpoint.setName(getName());
        endpoint.setHandler(cHandler);

        try {
            endpoint.init();
        } catch (Exception ex) {
            CoyoteLogger.AJP_LOGGER.errorInitializingEndpoint(ex);
            throw ex;
        }
    }


    public void start() throws Exception {
        if (org.apache.tomcat.util.Constants.ENABLE_MODELER) {
            if (this.domain != null ) {
                try {
                    tpOname = new ObjectName
                    (domain + ":" + "type=ThreadPool,name=" + getJmxName());
                    Registry.getRegistry(null, null)
                    .registerComponent(endpoint, tpOname, null );
                } catch (Exception e) {
                    CoyoteLogger.AJP_LOGGER.errorRegisteringPool(e);
                }
                rgOname = new ObjectName
                (domain + ":type=GlobalRequestProcessor,name=" + getJmxName());
                Registry.getRegistry(null, null).registerComponent
                (cHandler.global, rgOname, null);
            }
        }
        try {
            endpoint.start();
        } catch (Exception ex) {
            CoyoteLogger.AJP_LOGGER.errorStartingEndpoint(ex);
            throw ex;
        }
        CoyoteLogger.AJP_LOGGER.startingAjpProtocol(getName());
    }

    public void pause() throws Exception {
        try {
            endpoint.pause();
        } catch (Exception ex) {
            CoyoteLogger.AJP_LOGGER.errorPausingEndpoint(ex);
            throw ex;
        }
        // Wait for a while until all the processors are idle
        RequestInfo[] states = cHandler.global.getRequestProcessors();
        int retry = 0;
        boolean done = false;
        while (!done && retry < org.apache.coyote.Constants.MAX_PAUSE_WAIT) {
            retry++;
            done = true;
            for (int i = 0; i < states.length; i++) {
                if (states[i].getStage() == org.apache.coyote.Constants.STAGE_SERVICE) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        ;
                    }
                    done = false;
                    break;
                }
            }
        }
        CoyoteLogger.AJP_LOGGER.pausingAjpProtocol(getName());
    }

    public void resume() throws Exception {
        try {
            endpoint.resume();
        } catch (Exception ex) {
            CoyoteLogger.AJP_LOGGER.errorResumingEndpoint(ex);
            throw ex;
        }
        CoyoteLogger.AJP_LOGGER.resumingAjpProtocol(getName());
    }

    public void destroy() throws Exception {
        CoyoteLogger.AJP_LOGGER.stoppingAjpProtocol(getName());
        endpoint.destroy();
        if (org.apache.tomcat.util.Constants.ENABLE_MODELER) {
            if (tpOname!=null)
                Registry.getRegistry(null, null).unregisterComponent(tpOname);
            if (rgOname != null)
                Registry.getRegistry(null, null).unregisterComponent(rgOname);
        }
    }

    public String getJmxName() {
        String encodedAddr = "";
        if (getAddress() != null) {
            encodedAddr = "" + getAddress();
            encodedAddr = URLEncoder.encode(encodedAddr.replace('/', '-').replace(':', '_').replace('%', '-')) + "-";
        }
        return ("ajp-" + encodedAddr + endpoint.getPort());
    }

    public String getName() {
        String encodedAddr = "";
        if (getAddress() != null) {
            encodedAddr = getAddress() + ":";
        }
        return ("ajp-" + encodedAddr + endpoint.getPort());
    }

    /**
     * Processor cache.
     */
    protected int processorCache = -1;
    public int getProcessorCache() { return this.processorCache; }
    public void setProcessorCache(int processorCache) { this.processorCache = processorCache; }

    public Executor getExecutor() { return endpoint.getExecutor(); }
    public void setExecutor(Executor executor) { endpoint.setExecutor(executor); }
    
    public int getMaxThreads() { return endpoint.getMaxThreads(); }
    public void setMaxThreads(int maxThreads) { endpoint.setMaxThreads(maxThreads); }

    public int getThreadPriority() { return endpoint.getThreadPriority(); }
    public void setThreadPriority(int threadPriority) { endpoint.setThreadPriority(threadPriority); }

    public int getBacklog() { return endpoint.getBacklog(); }
    public void setBacklog(int backlog) { endpoint.setBacklog(backlog); }

    public int getPort() { return endpoint.getPort(); }
    public void setPort(int port) { endpoint.setPort(port); }

    public InetAddress getAddress() { return endpoint.getAddress(); }
    public void setAddress(InetAddress ia) { endpoint.setAddress(ia); }

    public boolean getTcpNoDelay() { return endpoint.getTcpNoDelay(); }
    public void setTcpNoDelay(boolean tcpNoDelay) { endpoint.setTcpNoDelay(tcpNoDelay); }

    public int getSoLinger() { return endpoint.getSoLinger(); }
    public void setSoLinger(int soLinger) { endpoint.setSoLinger(soLinger); }

    public int getSoTimeout() { return endpoint.getSoTimeout(); }
    public void setSoTimeout(int soTimeout) { endpoint.setSoTimeout(soTimeout); }

    public void setPollerSize(int pollerSize) { endpoint.setPollerSize(pollerSize); }
    public int getPollerSize() { return endpoint.getPollerSize(); }

    /**
     * Should authentication be done in the native webserver layer, 
     * or in the Servlet container ?
     */
    protected boolean tomcatAuthentication = Constants.DEFAULT_TOMCAT_AUTHENTICATION;
    public boolean getTomcatAuthentication() { return tomcatAuthentication; }
    public void setTomcatAuthentication(boolean tomcatAuthentication) { this.tomcatAuthentication = tomcatAuthentication; }

    /**
     * Required secret.
     */
    protected String requiredSecret = null;
    public void setRequiredSecret(String requiredSecret) { this.requiredSecret = requiredSecret; }
    
    /**
     * AJP packet size.
     */
    protected int packetSize = Constants.MAX_PACKET_SIZE;
    public int getPacketSize() { return packetSize; }
    public void setPacketSize(int packetSize) { this.packetSize = packetSize; }

    
    /**
     * The number of seconds Tomcat will wait for a subsequent request
     * before closing the connection.
     */
    protected int keepAliveTimeout = -1;
    public int getKeepAliveTimeout() { return keepAliveTimeout; }
    public void setKeepAliveTimeout(int timeout) { keepAliveTimeout = timeout; }


    // --------------------------------------  AjpConnectionHandler Inner Class


    protected static class AjpConnectionHandler implements Handler {

        protected AjpProtocol proto;
        protected AtomicLong registerCount = new AtomicLong(0);
        protected RequestGroupInfo global = new RequestGroupInfo();

        protected ConcurrentHashMap<Socket, AjpProcessor> connections =
            new ConcurrentHashMap<Socket, AjpProcessor>();
        protected ConcurrentLinkedQueue<AjpProcessor> recycledProcessors = 
            new ConcurrentLinkedQueue<AjpProcessor>() {
            protected AtomicInteger size = new AtomicInteger(0);
            public boolean offer(AjpProcessor processor) {
                boolean offer = (proto.processorCache == -1) ? true : (size.get() < proto.processorCache);
                //avoid over growing our cache or add after we have stopped
                boolean result = false;
                if ( offer ) {
                    result = super.offer(processor);
                    if ( result ) {
                        size.incrementAndGet();
                    }
                }
                if (!result) unregister(processor);
                return result;
            }
            
            public AjpProcessor poll() {
                AjpProcessor result = super.poll();
                if ( result != null ) {
                    size.decrementAndGet();
                }
                return result;
            }
            
            public void clear() {
                AjpProcessor next = poll();
                while ( next != null ) {
                    unregister(next);
                    next = poll();
                }
                super.clear();
                size.set(0);
            }
        };

        public AjpConnectionHandler(AjpProtocol proto) {
            this.proto = proto;
        }

        public SocketState event(Socket socket, SocketStatus status) {
            AjpProcessor result = connections.get(socket);
            SocketState state = SocketState.CLOSED; 
            if (result != null) {
                result.startProcessing();
                // Call the appropriate event
                try {
                    state = result.event(status);
                } catch (java.net.SocketException e) {
                    // SocketExceptions are normal
                    CoyoteLogger.AJP_LOGGER.socketException(e);
                } catch (java.io.IOException e) {
                    // IOExceptions are normal
                    CoyoteLogger.AJP_LOGGER.socketException(e);
                }
                // Future developers: if you discover any other
                // rare-but-nonfatal exceptions, catch them here, and log as
                // above.
                catch (Throwable e) {
                    // any other exception or error is odd. Here we log it
                    // with "ERROR" level, so it will show up even on
                    // less-than-verbose logs.
                    CoyoteLogger.AJP_LOGGER.socketError(e);
                } finally {
                    if (state != SocketState.LONG) {
                        connections.remove(socket);
                        recycledProcessors.offer(result);
                    } else {
                        if (proto.endpoint.isRunning()) {
                            proto.endpoint.getEventPoller().add(socket, result.getTimeout(), 
                                    result.getResumeNotification(), false);
                        }
                    }
                    result.endProcessing();
                }
            }
            return state;
        }
        
        public SocketState process(Socket socket) {
            AjpProcessor processor = recycledProcessors.poll();
            try {

                if (processor == null) {
                    processor = createProcessor();
                }

                SocketState state = processor.process(socket);
                if (state == SocketState.LONG) {
                    // Associate the connection with the processor. The next request 
                    // processed by this thread will use either a new or a recycled
                    // processor.
                    connections.put(socket, processor);
                    proto.endpoint.getEventPoller().add(socket, processor.getTimeout(), 
                            processor.getResumeNotification(), false);
                } else {
                    recycledProcessors.offer(processor);
                }
                return state;

            } catch(java.net.SocketException e) {
                // SocketExceptions are normal
                CoyoteLogger.AJP_LOGGER.socketException(e);
            } catch (java.io.IOException e) {
                // IOExceptions are normal
                CoyoteLogger.AJP_LOGGER.socketException(e);
            }
            // Future developers: if you discover any other
            // rare-but-nonfatal exceptions, catch them here, and log as
            // above.
            catch (Throwable e) {
                // any other exception or error is odd. Here we log it
                // with "ERROR" level, so it will show up even on
                // less-than-verbose logs.
                CoyoteLogger.AJP_LOGGER.socketError(e);
            }
            recycledProcessors.offer(processor);
            return SocketState.CLOSED;
        }

        protected AjpProcessor createProcessor() {
            AjpProcessor processor = new AjpProcessor(proto.packetSize, proto.endpoint);
            processor.setAdapter(proto.adapter);
            processor.setTomcatAuthentication(proto.tomcatAuthentication);
            processor.setRequiredSecret(proto.requiredSecret);
            processor.setKeepAliveTimeout(proto.keepAliveTimeout);
            register(processor);
            return processor;
        }
        
        protected void register(AjpProcessor processor) {
            RequestInfo rp = processor.getRequest().getRequestProcessor();
            rp.setGlobalProcessor(global);
            if (org.apache.tomcat.util.Constants.ENABLE_MODELER && proto.getDomain() != null) {
                synchronized (this) {
                    try {
                        long count = registerCount.incrementAndGet();
                        ObjectName rpName = new ObjectName
                            (proto.getDomain() + ":type=RequestProcessor,worker="
                                + proto.getJmxName() + ",name=AjpRequest" + count);
                        Registry.getRegistry(null, null).registerComponent(rp, rpName, null);
                        rp.setRpName(rpName);
                    } catch (Exception e) {
                        CoyoteLogger.AJP_LOGGER.errorRegisteringRequest(e);
                    }
                }
            }
        }

        protected void unregister(AjpProcessor processor) {
            RequestInfo rp = processor.getRequest().getRequestProcessor();
            rp.setGlobalProcessor(null);
            if (org.apache.tomcat.util.Constants.ENABLE_MODELER && proto.getDomain() != null) {
                synchronized (this) {
                    try {
                        ObjectName rpName = rp.getRpName();
                        Registry.getRegistry(null, null).unregisterComponent(rpName);
                        rp.setRpName(null);
                    } catch (Exception e) {
                        CoyoteLogger.AJP_LOGGER.errorUnregisteringRequest(e);
                    }
                }
            }
        }

    }


    // -------------------- Various implementation classes --------------------


    protected String domain;
    protected ObjectName oname;
    protected MBeanServer mserver;

    public ObjectName getObjectName() {
        return oname;
    }

    public String getDomain() {
        return domain;
    }

    public ObjectName preRegister(MBeanServer server,
                                  ObjectName name) throws Exception {
        oname=name;
        mserver=server;
        domain=name.getDomain();
        return name;
    }

    public void postRegister(Boolean registrationDone) {
    }

    public void preDeregister() throws Exception {
    }

    public void postDeregister() {
    }
    
 
}
