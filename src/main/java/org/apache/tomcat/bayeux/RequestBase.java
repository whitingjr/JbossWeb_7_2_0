/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.bayeux;

import static org.jboss.web.CoyoteMessages.MESSAGES;

import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import javax.servlet.ServletException;

import org.apache.cometd.bayeux.Bayeux;
import org.apache.cometd.bayeux.Message;
import org.apache.tomcat.util.json.JSONArray;
import org.apache.tomcat.util.json.JSONException;
import org.apache.tomcat.util.json.JSONObject;
import org.jboss.logging.Logger;
import org.jboss.servlet.http.HttpEvent;
import org.jboss.web.CoyoteLogger;

/**
 * Common functionality and member variables for all Bayeux requests.
 *
 * @author Guy A. Molinari
 * @author Filip Hanik
 * @version 0.9
 *
 */
public abstract class RequestBase implements BayeuxRequest {
    
    protected static final SimpleDateFormat timestampFmt =
        new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
    static {
        timestampFmt.setTimeZone(TimeZone.getTimeZone("GMT"));
    }
    //message properties, combined for all messages
    protected TomcatBayeux tomcatBayeux;
    protected String channel;
    protected String id;
    protected String clientId;
    protected String version = null;
    protected String[] suppConnTypes = null;
    protected int suppConnTypesFlag = 0;
    protected int desiredConnTypeFlag = 0;
    protected String minVersion = null;
    protected String subscription = null;
    protected String data = null;
    protected String conType = null;
    protected LinkedHashMap<String, Object> ext = new LinkedHashMap<String, Object> ();

    
    protected HttpEvent event;
    
    protected HashMap<String, Object> response = null;
    
    protected int reconnectInterval = 1000;
    
    protected RequestBase(TomcatBayeux tb, HttpEvent event, JSONObject jsReq) throws JSONException {
        this.tomcatBayeux = tb;
        this.event = event;
        channel = jsReq.optString(Bayeux.CHANNEL_FIELD);
        id = jsReq.optString(Bayeux.ID_FIELD);
        clientId = jsReq.optString(Bayeux.CLIENT_FIELD);
        version = jsReq.optString(Bayeux.VERSION_FIELD);
        minVersion = jsReq.optString(Bayeux.MIN_VERSION_FIELD);
        conType = jsReq.optString(Bayeux.CONNECTION_TYPE_FIELD);
        subscription = jsReq.optString(Bayeux.SUBSCRIPTION_FIELD);
        data = jsReq.optString(Bayeux.DATA_FIELD);
        reconnectInterval = tb.getReconnectInterval();
        if (jsReq.has(Bayeux.EXT_FIELD)) {
            JSONObject jext = jsReq.getJSONObject(Bayeux.EXT_FIELD);
            for (Iterator<String> i = jext.keys(); i.hasNext(); ) {
                String key = i.next();
                ext.put(key, jext.get(key));
            }//for
        }//end if
        
        if (jsReq.has(Bayeux.SUPP_CONNECTION_TYPE_FIELD)) {
            JSONArray types = jsReq.getJSONArray(Bayeux.SUPP_CONNECTION_TYPE_FIELD);
            suppConnTypes = new String[types.length()];
            for (int i = 0; i < types.length(); i++) {
                suppConnTypes[i] = types.getString(i);
                if (Bayeux.TRANSPORT_CALLBACK_POLL.equals(suppConnTypes[i]))
                    suppConnTypesFlag = suppConnTypesFlag|ClientImpl.SUPPORT_CALLBACK_POLL;
                else if (Bayeux.TRANSPORT_LONG_POLL.equals(suppConnTypes[i]))
                    suppConnTypesFlag = suppConnTypesFlag|ClientImpl.SUPPORT_LONG_POLL;
            }//for
        }//end if

        if (conType!=null) {
            if (Bayeux.TRANSPORT_CALLBACK_POLL.equals(conType))
                desiredConnTypeFlag = ClientImpl.SUPPORT_CALLBACK_POLL;
            else if (Bayeux.TRANSPORT_LONG_POLL.equals(conType))
                desiredConnTypeFlag = ClientImpl.SUPPORT_LONG_POLL;
        }//end if
        
        //due to the fact that the javascript doesn't send up a required field
        //we have to fake it
        suppConnTypesFlag = ClientImpl.SUPPORT_CALLBACK_POLL | ClientImpl.SUPPORT_LONG_POLL;

    }

    public HttpError validate() {
        HttpError result = null;
//        if (clientId == null) {
//            result = new HttpError(401,"No Client ID.", null);
//        }
        return result;
    }

    public TomcatBayeux getTomcatBayeux() {
        return tomcatBayeux;
    }

    public String getChannel() {
        return channel;
    }

    public String getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public LinkedHashMap getExt() {
        return ext;
    }

    public HttpEvent getEvent() {
        return event;
    }
    
    protected static void deliver(HttpEvent event, ClientImpl to) throws IOException, ServletException, BayeuxException {
        JSONArray jarray = getJSONArray(event,true);
        if ( jarray == null ) throw new BayeuxException(MESSAGES.noBayeuxMessage());
        String jsonstring = jarray.toString();
        if (CoyoteLogger.BAYEUX_LOGGER.isTraceEnabled()) {
            CoyoteLogger.BAYEUX_LOGGER.trace("["+Thread.currentThread().getName()+"] Delivering message to[" + to + "] message:" + jsonstring);
        }

        if (to!=null) {
            if (to.useJsonFiltered()) {
                if (!event.getHttpServletResponse().isCommitted()) event.getHttpServletResponse().setContentType("text/json-comment-filtered");
            }else {	
                if (!event.getHttpServletResponse().isCommitted()) event.getHttpServletResponse().setContentType("text/json");
            }
        }

        PrintWriter out = event.getHttpServletResponse().getWriter();
        if (to==null) {
            //do nothing
        }else if ( (to.getDesirectConnType() == 0 && to.supportsLongPoll()) || to.getDesirectConnType() == ClientImpl.SUPPORT_LONG_POLL) {
            if (to.useJsonFiltered())
                out.print("/*");
        } else if ( (to.getDesirectConnType() == 0 && to.supportsCallbackPoll()) || to.getDesirectConnType() == ClientImpl.SUPPORT_CALLBACK_POLL) {
            String jsonp = event.getHttpServletRequest().getParameter(Bayeux.JSONP_PARAMETER);
            if (jsonp == null)
                jsonp = Bayeux.JSONP_DEFAULT_NAME;
            out.print(jsonp);
            out.print('(');
        } else {
            throw new BayeuxException(MESSAGES.noBayeuxConnectionType());
        }
        out.print(jsonstring);
        if ( to == null ) {
            //do nothing
        } else if ( (to.getDesirectConnType() == 0 && to.supportsLongPoll()) || to.getDesirectConnType() == ClientImpl.SUPPORT_LONG_POLL) {
            if (to.useJsonFiltered())
                out.print("*/");
        } else if ( (to.getDesirectConnType() == 0 && to.supportsCallbackPoll()) || to.getDesirectConnType() == ClientImpl.SUPPORT_CALLBACK_POLL) {
            out.print(");");
        } 
        out.flush();

    }

    protected static JSONArray getJSONArray(HttpEvent event, boolean nullok) {
        synchronized(event) {
            JSONArray jarray = (JSONArray) event.getHttpServletRequest().getAttribute(JSON_MSG_ARRAY);
            if (jarray == null && (!nullok)) {
                jarray = new JSONArray();
                event.getHttpServletRequest().setAttribute(JSON_MSG_ARRAY, jarray);
            }
            return jarray;
        }
    }

    protected JSONArray getJSONArray() {
        return getJSONArray(event,false);
    }

    protected void addToDeliveryQueue(ClientImpl to, JSONObject msg) throws IOException, ServletException, BayeuxException {
        synchronized (event) {
            getJSONArray().put(msg);
        }
    }
    
    protected void flushMessages(ClientImpl client) throws BayeuxException {
        List<Message> msgs = client.takeMessages();
        synchronized (event) {
            try {
                for (Iterator<Message> it = msgs.iterator(); it.hasNext(); ){
                    MessageImpl msg = (MessageImpl)it.next();
                    Map map = new HashMap();
                    map.put(Bayeux.CHANNEL_FIELD,msg.getChannel().getId());
                    if (msg.getClient()!=null) map.put(Bayeux.CLIENT_FIELD,msg.getClient().getId());
                    map.put(Bayeux.DATA_FIELD,msg);
                    JSONObject obj = new JSONObject(map);
                    addToDeliveryQueue(client, obj);
                }
            } catch (ServletException x) {
                throw new BayeuxException(x);
            } catch (IOException x) {
                throw new BayeuxException(x);
            }
        }
    }
    
    public int process(int prevops) throws BayeuxException {
        event.getHttpServletRequest().setAttribute(CURRENT_REQ_ATTR,this);
        return prevops;
    }
    
    public int getReconnectInterval() {
        return reconnectInterval;
    }

    public String getTimeStamp() {
        return timestampFmt.format(new Date(System.currentTimeMillis()));
    }

}
