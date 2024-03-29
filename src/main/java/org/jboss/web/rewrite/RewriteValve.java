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

package org.jboss.web.rewrite;

import static org.jboss.web.WebMessages.MESSAGES;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.StringTokenizer;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;

import org.apache.catalina.Container;
import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleListener;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.util.LifecycleSupport;
import org.apache.catalina.valves.ValveBase;
import org.apache.tomcat.util.buf.CharChunk;
import org.apache.tomcat.util.buf.MessageBytes;
import org.apache.tomcat.util.net.URL;

/**
 * 
 * @author Remy Maucherat
 */
public class RewriteValve extends ValveBase
    implements Lifecycle {

    /**
     * The lifecycle event support for this component.
     */
    protected LifecycleSupport lifecycle = new LifecycleSupport(this);

    
    /**
     * The rewrite rules that the valve will use.
     */
    protected RewriteRule[] rules = null;
    
    
    /**
     * If rewriting occurs, the whole request will be processed again.
     */
    protected ThreadLocal<Boolean> invoked = new ThreadLocal<Boolean>();
    
    
    /**
     * Relative path to the configuration file.
     * Note: If the valve's container is a context, this will be relative to
     * /WEB-INF/.
     */
    protected String resourcePath = "rewrite.properties";

    
    /**
     * Will be set to true if the valve is associated with a context.
     */
    protected boolean context = false;
    
    
    /**
     * Maps to be used by the rules.
     */
    protected Map<String, RewriteMap> maps = new Hashtable<String, RewriteMap>();
    
    
    public void addLifecycleListener(LifecycleListener listener) {
        lifecycle.addLifecycleListener(listener);
    }

    public LifecycleListener[] findLifecycleListeners() {
        return lifecycle.findLifecycleListeners();
    }

    public void removeLifecycleListener(LifecycleListener listener) {
        lifecycle.removeLifecycleListener(listener);
    }

    public void start() throws LifecycleException {

        InputStream is = null;

        // Process configuration file for this valve
        if (getContainer() instanceof Context) {
            context = true;
            is = ((Context) getContainer()).getServletContext()
                .getResourceAsStream("/WEB-INF/" + resourcePath);
            if (container.getLogger().isDebugEnabled()) {
                if (is == null) {
                    container.getLogger().debug("No configuration resource found: /WEB-INF/" + resourcePath);
                } else {
                    container.getLogger().debug("Read configuration from: /WEB-INF/" + resourcePath);
                }
            }
        }
        if (is == null) {
            String resourceName = getHostConfigPath(resourcePath);
            File file = new File(getConfigBase(), resourceName);
            try {
                if (!file.exists()) {
                    if (resourceName != null) {
                        // Use getResource and getResourceAsStream
                        is = getClass().getClassLoader()
                            .getResourceAsStream(resourceName);
                        if (is != null && container.getLogger().isDebugEnabled()) {
                            container.getLogger().debug("Read configuration from CL at " + resourceName);
                        }
                    }
                } else {
                    if (container.getLogger().isDebugEnabled()) {
                        container.getLogger().debug("Read configuration from " + file.getAbsolutePath());
                    }
                    is = new FileInputStream(file);
                }
                if ((is == null) && (container.getLogger().isDebugEnabled())) {
                    container.getLogger().debug("No configuration resource found: " + resourceName + 
                            " in " + getConfigBase() + " or in the classloader");
                }
            } catch (Exception e) {
                container.getLogger().error(MESSAGES.errorOpeningRewriteConfiguration(), e);
            }
        }
        
        if (is == null) {
            // Will use management operations to configure the valve dynamically
            return;
        }
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));

        try {
            parse(reader);
        } finally {
            try {
                reader.close();
            } catch (IOException e) {
            }
            try {
                if (is != null) {
                    is.close();
                }
            } catch (IOException e) {
            }
        }

    }

    public void setConfiguration(String configuration)
        throws Exception {
        maps.clear();
        parse(new BufferedReader(new StringReader(configuration)));
    }
    
    public String getConfiguration() {
        StringBuffer buffer = new StringBuffer();
        // FIXME: Output maps if possible
        for (int i = 0; i < rules.length; i++) {
            for (int j = 0; j < rules[i].getConditions().length; j++) {
                buffer.append(rules[i].getConditions()[j].toString()).append("\r\n");
            }
            buffer.append(rules[i].toString()).append("\r\n").append("\r\n");
        }
        return buffer.toString();
    }
    
    protected void parse(BufferedReader reader) throws LifecycleException {
        ArrayList<RewriteRule> rules = new ArrayList<RewriteRule>();
        ArrayList<RewriteCond> conditions = new ArrayList<RewriteCond>();
        while (true) {
            try {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                Object result = parse(line);
                if (result instanceof RewriteRule) {
                    RewriteRule rule = (RewriteRule) result;
                    if (container.getLogger().isDebugEnabled()) {
                        container.getLogger().debug("Add rule with pattern " + rule.getPatternString()
                                + " and substitution " + rule.getSubstitutionString());
                    }
                    for (int i = (conditions.size() - 1); i > 0; i--) {
                        if (conditions.get(i - 1).isOrnext()) {
                            conditions.get(i).setOrnext(true);
                        }
                    }
                    for (int i = 0; i < conditions.size(); i++) {
                        if (container.getLogger().isDebugEnabled()) {
                            RewriteCond cond = conditions.get(i);
                            container.getLogger().debug("Add condition " + cond.getCondPattern() 
                                    + " test " + cond.getTestString() + " to rule with pattern " 
                                    + rule.getPatternString() + " and substitution " 
                                    + rule.getSubstitutionString() + (cond.isOrnext() ? " [OR]" : "")
                                    + (cond.isNocase() ? " [NC]" : ""));
                        }
                        rule.addCondition(conditions.get(i));
                    }
                    conditions.clear();
                    rules.add(rule);
                } else if (result instanceof RewriteCond) {
                    conditions.add((RewriteCond) result);
                } else if (result instanceof Object[]) {
                    String mapName = (String) ((Object[]) result)[0];
                    RewriteMap map = (RewriteMap) ((Object[]) result)[1];
                    maps.put(mapName, map);
                    if (map instanceof Lifecycle) {
                        ((Lifecycle) map).start();
                    }
                }
            } catch (IOException e) {
                container.getLogger().error(MESSAGES.errorReadingRewriteConfiguration(), e);
            }
        }
        this.rules = (RewriteRule[]) rules.toArray(new RewriteRule[0]);
        
        // Finish parsing the rules
        for (int i = 0; i < this.rules.length; i++) {
            this.rules[i].parse(maps);
        }
    }

    public void stop() throws LifecycleException {
        Iterator<RewriteMap> values = maps.values().iterator();
        while (values.hasNext()) {
            RewriteMap map = values.next();
            if (map instanceof Lifecycle) {
                ((Lifecycle) map).stop();
            }
        }
        maps.clear();
        rules = null;
    }


    public void invoke(Request request, Response response)
        throws IOException, ServletException {

        if (rules == null || rules.length == 0) {
            getNext().invoke(request, response);
            return;
        }
        
        if (invoked.get() == Boolean.TRUE) {
            getNext().invoke(request, response);
            invoked.set(null);
            return;
        }
        
        TomcatResolver resolver = new TomcatResolver(request);
        
        invoked.set(Boolean.TRUE);
        
        // As long as MB isn't a char sequence or affiliated, this has to be
        // converted to a string
        MessageBytes urlMB = context ? request.getRequestPathMB() : request.getDecodedRequestURIMB();
        urlMB.toChars();
        CharSequence url = urlMB.getCharChunk();
        CharSequence host = request.getServerName();
        boolean rewritten = false;
        boolean done = false;
        for (int i = 0; i < rules.length; i++) {
            CharSequence test = (rules[i].isHost()) ? host : url;
            CharSequence newtest = rules[i].evaluate(test, resolver);
            if (newtest != null && !test.equals(newtest.toString())) {
                if (container.getLogger().isDebugEnabled()) {
                    container.getLogger().debug("Rewrote " + test + " as " + newtest
                            + " with rule pattern " + rules[i].getPatternString());
                }
                if (rules[i].isHost()) {
                    host = newtest;
                } else {
                    url = newtest;
                }
                rewritten = true;
            }

            // Final reply

            // - forbidden
            if (rules[i].isForbidden() && newtest != null) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN);
                done = true;
                break;
            }
            // - gone
            if (rules[i].isGone() && newtest != null) {
                response.sendError(HttpServletResponse.SC_GONE);
                done = true;
                break;
            }
            // - redirect (code)
            if (rules[i].isRedirect() && newtest != null) {
                // append the query string to the url if there is one and it hasn't been rewritten
                String queryString = request.getQueryString();
                StringBuffer urlString = new StringBuffer(url);
                if (queryString != null && queryString.length() > 0) {
                    int index = urlString.indexOf("?");
                    if (index != -1) {
                        // if qsa is specified append the query
                        if (rules[i].isQsappend()) {
                            urlString.append('&');
                            urlString.append(queryString);
                        }
                        // if the ? is the last character delete it, its only purpose was to
                        // prevent the rewrite module from appending the query string
                        else if (index == urlString.length() - 1) {
                            urlString.deleteCharAt(index);
                        }
                    } else {
                        urlString.append('?');
                        urlString.append(queryString);
                    }
                }
                // Insert the context if
                // 1. this valve is associated with a context
                // 2. the url starts with a leading slash
                // 3. the url isn't absolute
                if (context && urlString.charAt(0) == '/' && !hasScheme(urlString)) {
                    urlString.insert(0, request.getContext().getEncodedPath());
                }
                response.sendRedirect(urlString.toString());
                response.setStatus(rules[i].getRedirectCode());
                done = true;
                break;
            }
            
            // Reply modification

            // - cookie
            if (rules[i].isCookie() && newtest != null) {
                Cookie cookie = new Cookie(rules[i].getCookieName(), 
                        rules[i].getCookieResult());
                cookie.setDomain(rules[i].getCookieDomain());
                cookie.setMaxAge(rules[i].getCookieLifetime());
                cookie.setPath(rules[i].getCookiePath());
                cookie.setSecure(rules[i].isCookieSecure());
                cookie.setHttpOnly(rules[i].isCookieHttpOnly());
                response.addCookie(cookie);
            }
            // - env (note: this sets a request attribute)
            if (rules[i].isEnv() && newtest != null) {
                for (int j = 0; j < rules[i].getEnvSize(); j++) {
                    request.setAttribute(rules[i].getEnvName(j), rules[i].getEnvResult(j));
                }
            }
            // - content type (note: this will not force the content type, use a filter
            //   to do that)
            if (rules[i].isType() && newtest != null) {
                request.setContentType(rules[i].getTypeValue());
            }
            // - qsappend
            if (rules[i].isQsappend() && newtest != null) {
                String queryString = request.getQueryString();
                String urlString = url.toString();
                if (urlString.indexOf('?') != -1 && queryString != null) {
                    url = urlString + "&" + queryString;
                }
            }
            
            // Control flow processing
            
            // - chain (skip remaining chained rules if this one does not match)
            if (rules[i].isChain() && newtest == null) {
                for (int j = i; j < rules.length; j++) {
                    if (!rules[j].isChain()) {
                        i = j;
                        break;
                    }
                }
                continue;
            }
            // - last (stop rewriting here)
            if (rules[i].isLast() && newtest != null) {
                break;
            }
            // - next (redo again)
            if (rules[i].isNext() && newtest != null) {
                i = 0;
                continue;
            }
            // - skip (n rules)
            if (newtest != null) {
                i += rules[i].getSkip();
            }
            
        }
        
        if (rewritten) {
            if (!done) {
                // See if we need to replace the query string
                String urlString = url.toString();
                String queryString = null;
                int queryIndex = urlString.indexOf('?');
                if (queryIndex != -1) {
                    queryString = urlString.substring(queryIndex+1);
                    urlString = urlString.substring(0, queryIndex);
                }
                // Set the new URL
                CharChunk chunk = request.getCoyoteRequest().requestURI().getCharChunk();
                chunk.recycle();
                if (context) {
                    chunk.append(request.getContextPath());
                }
                chunk.append(urlString);
                request.getCoyoteRequest().requestURI().toChars();
                // Set the new Query if there is one
                if (queryString != null) {
                    request.getCoyoteRequest().queryString().setString(null);
                    chunk = request.getCoyoteRequest().queryString().getCharChunk();
                    chunk.recycle();
                    chunk.append(queryString);
                    request.getCoyoteRequest().queryString().toChars();
                }
                // Set the new host if it changed
                if (!host.equals(request.getServerName())) {
                    request.getCoyoteRequest().serverName().setString(null);
                    chunk = request.getCoyoteRequest().serverName().getCharChunk();
                    chunk.recycle();
                    chunk.append(host.toString());
                    request.getCoyoteRequest().serverName().toChars();
                }
                request.getMappingData().recycle();
                // Reinvoke the whole request recursively
                try {
                    request.getConnector().getProtocolHandler().getAdapter().service
                        (request.getCoyoteRequest(), response.getCoyoteResponse());
                } catch (Exception e) {
                    // This doesn't actually happen in the Catalina adapter implementation
                }
            }
        } else {
            getNext().invoke(request, response);
        }
        
        invoked.set(null);
        
    }
    
    
    /**
     * Get config base.
     */
    protected File getConfigBase() {
        File configBase = 
            new File(System.getProperty("catalina.base"), "conf");
        if (!configBase.exists()) {
            return null;
        } else {
            return configBase;
        }
    }  

    
    /**
     * Find the configuration path where the rewrite configuration file
     * will be stored.
     * 
     * @param resourceName
     * @return
     */
    protected String getHostConfigPath(String resourceName) {
        StringBuffer result = new StringBuffer();
        Container container = getContainer();
        Container host = null;
        Container engine = null;
        while (container != null) {
            if (container instanceof Host)
                host = container;
            if (container instanceof Engine)
                engine = container;
            container = container.getParent();
        }
        if (engine != null) {
            result.append(engine.getName()).append('/');
        }
        if (host != null) {
            result.append(host.getName()).append('/');
        }
        result.append(resourceName);
        return result.toString();
    }

    
    /**
     * This factory method will parse a line formed like:
     *  
     * Example:
     *  RewriteCond %{REMOTE_HOST}  ^host1.*  [OR]
     * 
     * @param line
     * @return
     */
    public static Object parse(String line) {
        StringTokenizer tokenizer = new StringTokenizer(line);
        if (tokenizer.hasMoreTokens()) {
            String token = tokenizer.nextToken();
            if (token.equals("RewriteCond")) {
                // RewriteCond TestString CondPattern [Flags]
                RewriteCond condition = new RewriteCond();
                if (tokenizer.countTokens() < 2) {
                    throw MESSAGES.invalidRewriteConfiguration(line);
                }
                condition.setTestString(tokenizer.nextToken());
                condition.setCondPattern(tokenizer.nextToken());
                if (tokenizer.hasMoreTokens()) {
                    String flags = tokenizer.nextToken();
                    if (flags.startsWith("[") && flags.endsWith("]")) {
                        flags = flags.substring(1, flags.length() - 1);
                    }
                    StringTokenizer flagsTokenizer = new StringTokenizer(flags, ",");
                    while (flagsTokenizer.hasMoreElements()) {
                        parseCondFlag(line, condition, flagsTokenizer.nextToken());
                    }
                }
                return condition;
            } else if (token.equals("RewriteRule")) {
                // RewriteRule Pattern Substitution [Flags]
                RewriteRule rule = new RewriteRule();
                if (tokenizer.countTokens() < 2) {
                    throw MESSAGES.invalidRewriteConfiguration(line);
                }
                rule.setPatternString(tokenizer.nextToken());
                rule.setSubstitutionString(tokenizer.nextToken());
                if (tokenizer.hasMoreTokens()) {
                    String flags = tokenizer.nextToken();
                    if (flags.startsWith("[") && flags.endsWith("]")) {
                        flags = flags.substring(1, flags.length() - 1);
                    }
                    StringTokenizer flagsTokenizer = new StringTokenizer(flags, ",");
                    while (flagsTokenizer.hasMoreElements()) {
                        parseRuleFlag(line, rule, flagsTokenizer.nextToken());
                    }
                }
                return rule;
            } else if (token.equals("RewriteMap")) {
                // RewriteMap name rewriteMapClassName whateverOptionalParameterInWhateverFormat
                if (tokenizer.countTokens() < 2) {
                    throw MESSAGES.invalidRewriteConfiguration(line);
                }
                String name = tokenizer.nextToken();
                String rewriteMapClassName = tokenizer.nextToken();
                RewriteMap map = null; 
                try {
                    map = (RewriteMap) (Class.forName(rewriteMapClassName).newInstance());
                } catch (Exception e) {
                    throw MESSAGES.invalidRewriteMap(rewriteMapClassName);
                }
                if (tokenizer.hasMoreTokens()) {
                    map.setParameters(tokenizer.nextToken());
                }
                Object[] result = new Object[2];
                result[0] = name;
                result[1] = map;
                return result;
            } else if (token.startsWith("#")) {
                // it's a comment, ignore it
            } else {
                throw MESSAGES.invalidRewriteConfiguration(line);
            }
        }
        return null;
    }
    
    
    /**
     * Parser for RewriteCond flags.
     * 
     * @param condition
     * @param flag
     */
    protected static void parseCondFlag(String line, RewriteCond condition, String flag) {
        if (flag.equals("NC") || flag.equals("nocase")) {
            condition.setNocase(true);
        } else if (flag.equals("OR") || flag.equals("ornext")) {
            condition.setOrnext(true);
        } else {
            throw MESSAGES.invalidRewriteFlags(line, flag);
        }
    }
    
    
    /**
     * Parser for ReweriteRule flags.
     * 
     * @param rule
     * @param flag
     */
    protected static void parseRuleFlag(String line, RewriteRule rule, String flag) {
        if (flag.equals("chain") || flag.equals("C")) {
            rule.setChain(true);
        } else if (flag.startsWith("cookie=") || flag.startsWith("C=")) {
            rule.setCookie(true);
            if (flag.startsWith("cookie")) {
                flag = flag.substring("cookie=".length());
            } else if (flag.startsWith("C=")) {
                flag = flag.substring("C=".length());
            }
            StringTokenizer tokenizer = new StringTokenizer(flag, ":");
            if (tokenizer.countTokens() < 2) {
                throw MESSAGES.invalidRewriteFlags(line);
            }
            rule.setCookieName(tokenizer.nextToken());
            rule.setCookieValue(tokenizer.nextToken());
            if (tokenizer.hasMoreTokens()) {
                rule.setCookieDomain(tokenizer.nextToken());
            }
            if (tokenizer.hasMoreTokens()) {
                try {
                    rule.setCookieLifetime(Integer.parseInt(tokenizer.nextToken()));
                } catch (NumberFormatException e) {
                    throw MESSAGES.invalidRewriteFlags(line);
                }
            }
            if (tokenizer.hasMoreTokens()) {
                rule.setCookiePath(tokenizer.nextToken());
            }
            if (tokenizer.hasMoreTokens()) {
                rule.setCookieSecure(Boolean.parseBoolean(tokenizer.nextToken()));
            }
            if (tokenizer.hasMoreTokens()) {
                rule.setCookieHttpOnly(Boolean.parseBoolean(tokenizer.nextToken()));
            }
        } else if (flag.startsWith("env=") || flag.startsWith("E=")) {
            rule.setEnv(true);
            if (flag.startsWith("env=")) {
                flag = flag.substring("env=".length());
            } else if (flag.startsWith("E=")) {
                flag = flag.substring("E=".length());
            }
            int pos = flag.indexOf(':');
            if (pos == -1 || (pos + 1) == flag.length()) {
                throw MESSAGES.invalidRewriteFlags(line);
            }
            rule.addEnvName(flag.substring(0, pos));
            rule.addEnvValue(flag.substring(pos + 1));
        } else if (flag.startsWith("forbidden") || flag.startsWith("F")) {
            rule.setForbidden(true);
        } else if (flag.startsWith("gone") || flag.startsWith("G")) {
            rule.setGone(true);
        } else if (flag.startsWith("host") || flag.startsWith("H")) {
            rule.setHost(true);
        } else if (flag.startsWith("last") || flag.startsWith("L")) {
            rule.setLast(true);
        } else if (flag.startsWith("next") || flag.startsWith("N")) {
            rule.setNext(true);
        } else if (flag.startsWith("nocase") || flag.startsWith("NC")) {
            rule.setNocase(true);
        } else if (flag.startsWith("noescape") || flag.startsWith("NE")) {
            rule.setNoescape(true);
        /* Proxy not supported, would require strong proxy capabilities
        } else if (flag.startsWith("proxy") || flag.startsWith("P")) {
            rule.setProxy(true);*/
        } else if (flag.startsWith("qsappend") || flag.startsWith("QSA")) {
            rule.setQsappend(true);
        } else if (flag.startsWith("redirect") || flag.startsWith("R")) {
            if (flag.startsWith("redirect=")) {
                flag = flag.substring("redirect=".length());
                rule.setRedirect(true);
                rule.setRedirectCode(Integer.parseInt(flag));
            } else if (flag.startsWith("R=")) {
                flag = flag.substring("R=".length());
                rule.setRedirect(true);
                rule.setRedirectCode(Integer.parseInt(flag));
            } else {
                rule.setRedirect(true);
                rule.setRedirectCode(HttpServletResponse.SC_FOUND);
            }
        } else if (flag.startsWith("skip") || flag.startsWith("S")) {
            if (flag.startsWith("skip=")) {
                flag = flag.substring("skip=".length());
            } else if (flag.startsWith("S=")) {
                flag = flag.substring("S=".length());
            }
            rule.setSkip(Integer.parseInt(flag));
        } else if (flag.startsWith("type") || flag.startsWith("T")) {
            if (flag.startsWith("type=")) {
                flag = flag.substring("type=".length());
            } else if (flag.startsWith("T=")) {
                flag = flag.substring("T=".length());
            }
            rule.setType(true);
            rule.setTypeValue(flag);
        } else {
            throw MESSAGES.invalidRewriteFlags(line, flag);
        }
    }
    

    /**
     * Determine if a URI string has a <code>scheme</code> component.
     */
    protected static boolean hasScheme(StringBuffer uri) {
        int len = uri.length();
        for(int i=0; i < len ; i++) {
            char c = uri.charAt(i);
            if(c == ':') {
                return i > 0;
            } else if(!URL.isSchemeChar(c)) {
                return false;
            }
        }
        return false;
    }

}
