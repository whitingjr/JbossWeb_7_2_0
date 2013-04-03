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

package org.apache.catalina.core;


import static org.jboss.web.CatalinaMessages.MESSAGES;

import java.io.Serializable;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import javax.management.ObjectName;
import javax.naming.NamingException;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterConfig;
import javax.servlet.FilterRegistration;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.apache.catalina.Context;
import org.apache.catalina.Globals;
import org.apache.catalina.deploy.FilterDef;
import org.apache.catalina.deploy.FilterMap;
import org.apache.catalina.security.SecurityUtil;
import org.apache.catalina.util.Enumerator;
import org.apache.tomcat.util.modeler.Registry;
import org.jboss.web.CatalinaLogger;


/**
 * Implementation of a <code>javax.servlet.FilterConfig</code> useful in
 * managing the filter instances instantiated when a web application
 * is first started.
 *
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 * @version $Revision: 1673 $ $Date: 2011-03-12 18:58:07 +0100 (Sat, 12 Mar 2011) $
 */

public final class ApplicationFilterConfig implements FilterConfig, Serializable {


    // ----------------------------------------------------------- Constructors


    /**
     * Construct a new ApplicationFilterConfig for the specified filter
     * definition.
     *
     * @param context The context with which we are associated
     * @param filterDef Filter definition for which a FilterConfig is to be
     *  constructed
     */
    public ApplicationFilterConfig(Context context, FilterDef filterDef) {
        this.context = context;
        this.filterDef = filterDef;
    }


    // ----------------------------------------------------- Instance Variables


    /**
     * The facade associated with this wrapper.
     */
    protected ApplicationFilterConfigFacade facade =
        new ApplicationFilterConfigFacade(this);


    /**
     * The Context with which we are associated.
     */
    private transient Context context = null;


    /**
     * Dynamic flag.
     */
    protected boolean dynamic = false;
    
    
    /**
     * The application Filter we are configured for.
     */
    private transient Filter filter = null;


    /**
     * The application Filter we are configured for.
     */
    private transient Filter filterInstance = null;


    /**
     * The <code>FilterDef</code> that defines our associated Filter.
     */
    private FilterDef filterDef = null;


    /**
     * JMX registration name
     */
    private ObjectName oname;


    // --------------------------------------------------- FilterConfig Methods


    /**
     * Return the name of the filter we are configuring.
     */
    public String getFilterName() {
        return (filterDef.getFilterName());
    }


    /**
     * Return the class of the filter we are configuring.
     */
    public String getFilterClass() {
        return filterDef.getFilterClass();
    }


    /**
     * Return a <code>String</code> containing the value of the named
     * initialization parameter, or <code>null</code> if the parameter
     * does not exist.
     *
     * @param name Name of the requested initialization parameter
     */
    public String getInitParameter(String name) {
        Map<String, String> map = filterDef.getParameterMap();
        if (map == null) {
            return (null);
        } else {
            return map.get(name);
        }
    }


    /**
     * Return an <code>Enumeration</code> of the names of the initialization
     * parameters for this Filter.
     */
    public Enumeration<String> getInitParameterNames() {
        Map<String, String> map = filterDef.getParameterMap();
        if (map == null) {
            return (new Enumerator(new ArrayList<String>()));
        } else {
            return (new Enumerator(map.keySet()));
        }
    }


    /**
     * Return the ServletContext of our associated web application.
     */
    public ServletContext getServletContext() {
        return (this.context.getServletContext());
    }


    /**
     * Get the facade FilterRegistration.
     */
    public FilterRegistration getFacade() {
        return facade;
    }
    

    public boolean isDynamic() {
        return dynamic;
    }


    public void setDynamic(boolean dynamic) {
        this.dynamic = dynamic;
        if (dynamic) {
            // Change the facade (normally, this happens when the Wrapper is created)
            facade = new ApplicationFilterConfigFacade.Dynamic(this);
        }
    }


    /**
     * Return a String representation of this object.
     */
    public String toString() {

        StringBuilder sb = new StringBuilder("ApplicationFilterConfig[");
        sb.append("name=");
        sb.append(filterDef.getFilterName());
        sb.append(", filterClass=");
        sb.append(filterDef.getFilterClass());
        sb.append("]");
        return (sb.toString());

    }


    public boolean addMappingForServletNames(EnumSet<DispatcherType> dispatcherTypes, 
            boolean isMatchAfter, String... servletNames) {
        if (!context.isStarting()) {
            throw MESSAGES.cannotAddFilterRegistrationAfterInit(context.getPath());
        }
        if (servletNames == null || servletNames.length == 0) {
            throw MESSAGES.invalidFilterRegistrationArguments();
        }
        FilterMap filterMap = new FilterMap(); 
        for (String servletName : servletNames) {
            filterMap.addServletName(servletName);
        }
        filterMap.setFilterName(filterDef.getFilterName());
        if (dispatcherTypes != null) {
            for (DispatcherType dispatcherType: dispatcherTypes) {
                filterMap.setDispatcher(dispatcherType.name());
            }
        }
        if (isMatchAfter) {
            context.addFilterMap(filterMap);
        } else {
            context.addFilterMapBefore(filterMap);
        }
        return true;
    }


    public boolean addMappingForUrlPatterns(
            EnumSet<DispatcherType> dispatcherTypes, boolean isMatchAfter,
            String... urlPatterns) {
        if (!context.isStarting()) {
            throw MESSAGES.cannotAddFilterRegistrationAfterInit(context.getPath());
        }
        if (urlPatterns == null || urlPatterns.length == 0) {
            throw MESSAGES.invalidFilterRegistrationArguments();
        }
        FilterMap filterMap = new FilterMap(); 
        for (String urlPattern : urlPatterns) {
            filterMap.addURLPattern(urlPattern);
        }
        filterMap.setFilterName(filterDef.getFilterName());
        if (dispatcherTypes != null) {
            for (DispatcherType dispatcherType: dispatcherTypes) {
                filterMap.setDispatcher(dispatcherType.name());
            }
        }
        if (isMatchAfter) {
            context.addFilterMap(filterMap);
        } else {
            context.addFilterMapBefore(filterMap);
        }
        return true;
    }


    public Collection<String> getServletNameMappings() {
        HashSet<String> result = new HashSet<String>();
        FilterMap[] filterMaps = context.findFilterMaps();
        for (int i = 0; i < filterMaps.length; i++) {
            if (filterDef.getFilterName().equals(filterMaps[i].getFilterName())) {
                FilterMap filterMap = filterMaps[i];
                String[] maps = filterMap.getServletNames();
                for (int j = 0; j < maps.length; j++) {
                    result.add(maps[j]);
                }
                if (filterMap.getMatchAllServletNames()) {
                    result.add("*");
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }


    public Collection<String> getUrlPatternMappings() {
        HashSet<String> result = new HashSet<String>();
        FilterMap[] filterMaps = context.findFilterMaps();
        for (int i = 0; i < filterMaps.length; i++) {
            if (filterDef.getFilterName().equals(filterMaps[i].getFilterName())) {
                FilterMap filterMap = filterMaps[i];
                String[] maps = filterMap.getURLPatterns();
                for (int j = 0; j < maps.length; j++) {
                    result.add(maps[j]);
                }
                if (filterMap.getMatchAllUrlPatterns()) {
                    result.add("*");
                }
            }
        }
        return Collections.unmodifiableSet(result);
    }


    public void setAsyncSupported(boolean asyncSupported) {
        if (!context.isStarting()) {
            throw MESSAGES.cannotAddFilterRegistrationAfterInit(context.getPath());
        }
        filterDef.setAsyncSupported(asyncSupported);
        context.addFilterDef(filterDef);
    }


    public void setDescription(String description) {
        filterDef.setDescription(description);
        context.addFilterDef(filterDef);
    }


    public boolean setInitParameter(String name, String value) {
        if (!context.isStarting()) {
            throw MESSAGES.cannotAddFilterRegistrationAfterInit(context.getPath());
        }
        if (name == null || value == null) {
            throw MESSAGES.invalidFilterRegistrationArguments();
        }
        if (filterDef.getInitParameter(name) != null) {
            return false;
        }
        filterDef.addInitParameter(name, value);
        context.addFilterDef(filterDef);
        return true;
    }


    public Set<String> setInitParameters(Map<String, String> initParameters) {
        if (!context.isStarting()) {
            throw MESSAGES.cannotAddFilterRegistrationAfterInit(context.getPath());
        }
        if (initParameters == null) {
            throw MESSAGES.invalidFilterRegistrationArguments();
        }
        Set<String> conflicts = new HashSet<String>();
        Iterator<String> parameterNames = initParameters.keySet().iterator();
        while (parameterNames.hasNext()) {
            String parameterName = parameterNames.next();
            if (filterDef.getInitParameter(parameterName) != null) {
                conflicts.add(parameterName);
            } else {
                String value = initParameters.get(parameterName);
                if (value == null) {
                    throw MESSAGES.invalidFilterRegistrationArguments();
                }
                filterDef.addInitParameter(parameterName, value);
            }
        }
        context.addFilterDef(filterDef);
        return conflicts;
    }


    public Map<String, String> getInitParameters() {
        return Collections.unmodifiableMap(filterDef.getParameterMap());
    }


    // -------------------------------------------------------- Package Methods


    /**
     * Return the application Filter we are configured for.
     *
     * @exception ClassCastException if the specified class does not implement
     *  the <code>javax.servlet.Filter</code> interface
     * @exception ClassNotFoundException if the filter class cannot be found
     * @exception IllegalAccessException if the filter class cannot be
     *  publicly instantiated
     * @exception InstantiationException if an exception occurs while
     *  instantiating the filter object
     * @exception ServletException if thrown by the filter's init() method
     * @throws NamingException
     * @throws InvocationTargetException
     */
    Filter getFilter() throws ClassCastException, ClassNotFoundException,
        IllegalAccessException, InstantiationException, ServletException,
        InvocationTargetException, NamingException {

        // Return the existing filter instance, if any
        if (this.filter != null)
            return (this.filter);

        // Identify the class loader we will be using
        if (filterInstance == null) {
            String filterClass = filterDef.getFilterClass();
            this.filter = (Filter) context.getInstanceManager().newInstance(filterClass);
        } else {
            this.filter = filterInstance;
            filterInstance = null;
        }

        filter.init(this);
        
        // Expose filter via JMX
        if (org.apache.tomcat.util.Constants.ENABLE_MODELER) {
            registerJMX();
        }
        
        return (this.filter);

    }

    
    /**
     * Set the filter instance programmatically.
     */
    public void setFilter(Filter filter) {
        filterInstance = filter;
    }
    

    /**
     * Return the filter instance.
     */
    public Filter getFilterInstance() {
        return (filterInstance != null) ? filterInstance : filter;
    }
    

    /**
     * Return the filter definition we are configured for.
     */
    public FilterDef getFilterDef() {

        return (this.filterDef);

    }

    /**
     * Release the Filter instance associated with this FilterConfig,
     * if there is one.
     */
    void release() {

        if (org.apache.tomcat.util.Constants.ENABLE_MODELER) {
            unregsiterJMX();
        }
        
        if (this.filter != null)
        {
            try {
                if (Globals.IS_SECURITY_ENABLED) {
                    SecurityUtil.doAsPrivilege("destroy", filter);
                    SecurityUtil.remove(filter);
                } else {
                    filter.destroy();
                }
            } catch(java.lang.Exception ex){
                context.getLogger().error(MESSAGES.errorDestroyingFilter(getFilterName()), ex);
            }
            try {
                ((StandardContext) context).getInstanceManager().destroyInstance(this.filter);
            } catch (Exception e) {
                context.getLogger().error(MESSAGES.preDestroyException(), e);
            }
        }
        this.filter = null;

     }


    // -------------------------------------------------------- Private Methods


    private void registerJMX() {
        String parentName = context.getName();
        parentName = ("".equals(parentName)) ? "/" : parentName;

        String hostName = context.getParent().getName();
        hostName = (hostName == null) ? "DEFAULT" : hostName;

        // domain == engine name
        String domain = context.getParent().getParent().getName();

        String webMod = "//" + hostName + parentName;
        String onameStr = null;
        if (context instanceof StandardContext) {
            StandardContext standardContext = (StandardContext) context;
            onameStr = domain + ":j2eeType=Filter,name=" +
                 filterDef.getFilterName() + ",WebModule=" + webMod +
                 ",J2EEApplication=" +
                 standardContext.getJ2EEApplication() + ",J2EEServer=" +
                 standardContext.getJ2EEServer();
        } else {
            onameStr = domain + ":j2eeType=Filter,name=" +
                 filterDef.getFilterName() + ",WebModule=" + webMod;
        }
        try {
            oname = new ObjectName(onameStr);
            Registry.getRegistry(null, null).registerComponent(this, oname,
                    null);
        } catch (Exception ex) {
            CatalinaLogger.CORE_LOGGER.filterJmxRegistrationFailed(getFilterClass(), getFilterName(), ex);
        }
    }
    
    private void unregsiterJMX() {
        // unregister this component
        if (oname != null) {
            try {
                Registry.getRegistry(null, null).unregisterComponent(oname);
            } catch(Exception ex) {
                CatalinaLogger.CORE_LOGGER.filterJmxUnregistrationFailed(getFilterClass(), getFilterName(), ex);
            }
        }

    }

}
