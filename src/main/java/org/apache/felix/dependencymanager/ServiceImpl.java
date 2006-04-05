/*
 *   Copyright 2006 The Apache Software Foundation
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 */
package org.apache.felix.dependencymanager;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;

import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * Service implementation.
 * 
 * @author Marcel Offermans
 */
public class ServiceImpl implements Service {
    private static final ServiceRegistration NULL_REGISTRATION;
    private static final int STARTING = 1;
    private static final int WAITING_FOR_REQUIRED = 2;
    private static final int TRACKING_OPTIONAL = 3;
    private static final int STOPPING = 4;
    private static final String[] STATE_NAMES = {
        "(unknown)", 
        "starting", 
        "waiting for required dependencies", 
        "tracking optional dependencies", 
        "stopping"};
    
    private BundleContext m_context;
    private ServiceRegistration m_registration;
    
    private String m_callbackInit;
    private String m_callbackStart;
    private String m_callbackStop;
    private String m_callbackDestroy;
    
    private List m_listeners = new ArrayList();
    private ArrayList m_dependencies = new ArrayList();
    private int m_state;
    
    private Object m_serviceInstance;
    private Object m_implementation;
    private Object m_serviceName;
    private Dictionary m_serviceProperties;

    public ServiceImpl(BundleContext context) {
        m_state = STARTING;
        m_context = context;
        m_callbackInit = "init";
        m_callbackStart = "start";
        m_callbackStop = "stop";
        m_callbackDestroy = "destroy";
    }
    
    public Service add(Dependency dependency) {
        synchronized (m_dependencies) {
            m_dependencies.add(dependency);
        }
        if (m_state == WAITING_FOR_REQUIRED) {
            // if we're waiting for required dependencies, and
            // this is a required dependency, start tracking it
            // ...otherwise, we don't need to do anything yet
            if (dependency.isRequired()) {
                dependency.start(this);
            }
        }
        else if (m_state == TRACKING_OPTIONAL) {
            // start tracking the dependency
            dependency.start(this);
            if (dependency.isRequired() && !dependency.isAvailable()) {
                // if this is a required dependency and it can not
                // be resolved right away, then we need to go back to 
                // the waiting for required state, until this
                // dependency is available
                deactivateService();
            }
        }
        return this;
    }

    public Service remove(Dependency dependency) {
        synchronized (m_dependencies) {
            m_dependencies.remove(dependency);
        }
        if (m_state == TRACKING_OPTIONAL) {
            // if we're tracking optional dependencies, then any
            // dependency that is removed can be stopped without
            // causing state changes
            dependency.stop(this);
        }
        else if (m_state == WAITING_FOR_REQUIRED) {
            // if we're waiting for required dependencies, then
            // we only need to stop tracking the dependency if it
            // too is required; this might trigger a state change
            if (dependency.isRequired()) {
                dependency.stop(this);
                if (allRequiredDependenciesAvailable()) {
                    activateService();
                }
            }
        }
        return this;
    }

    public List getDependencies() {
        List list;
        synchronized (m_dependencies) {
            list = (List) m_dependencies.clone();
        }
        return list;
    }
    
    public ServiceRegistration getServiceRegistration() {
        return m_registration;
    }
    
    public Object getService() {
        return m_serviceInstance;
    }
    
    public void dependencyAvailable(Dependency dependency) {
        if ((dependency.isRequired()) 
            && (m_state == WAITING_FOR_REQUIRED) 
            && (allRequiredDependenciesAvailable())) {
            activateService();
        }
        if ((!dependency.isRequired()) && (m_state == TRACKING_OPTIONAL)) {
            updateInstance(dependency);
        }
    }

    public void dependencyChanged(Dependency dependency) {
        if (m_state == TRACKING_OPTIONAL) {
            updateInstance(dependency);
        }
    }
    
    public void dependencyUnavailable(Dependency dependency) {
        if (dependency.isRequired()) {
            if (m_state == TRACKING_OPTIONAL) {
                if (!allRequiredDependenciesAvailable()) {
                    deactivateService();
                }
            }
        }
        else {
            // optional dependency
        }
        if (m_state == TRACKING_OPTIONAL) {
            updateInstance(dependency);
        }
    }

    public synchronized void start() {
        if ((m_state != STARTING) && (m_state != STOPPING)) {
            throw new IllegalStateException("Cannot start from state " + STATE_NAMES[m_state]);
        }
        startTrackingRequired();
        if (allRequiredDependenciesAvailable() && (m_state == WAITING_FOR_REQUIRED)) {
            activateService();
        }
    }

    public synchronized void stop() {
        if ((m_state != WAITING_FOR_REQUIRED) && (m_state != TRACKING_OPTIONAL)) {
            if ((m_state > 0) && (m_state < STATE_NAMES.length)) {
                throw new IllegalStateException("Cannot stop from state " + STATE_NAMES[m_state]);
            }
            throw new IllegalStateException("Cannot stop from unknown state.");
        }
        if (m_state == TRACKING_OPTIONAL) {
            deactivateService();
        }
        stopTrackingRequired();
    }

    private void activateService() {
        // service activation logic, first we initialize the service instance itself
        // meaning it is created if necessary and the bundle context is set
        initService();
        // then we invoke the init callback so the service can further initialize
        // itself
        invoke(m_callbackInit);
        // now is the time to configure the service, meaning all required
        // dependencies will be set and any callbacks called
        configureService();
        // inform the state listeners we're starting
        stateListenersStarting();
        // start tracking optional services
        startTrackingOptional();
        // invoke the start callback, since we're now ready to be used
        invoke(m_callbackStart);
        // register the service in the framework's service registry
        registerService();
        // inform the state listeners we've started
        stateListenersStarted();
    }

    private void deactivateService() {
        // service deactivation logic, first inform the state listeners
        // we're stopping
        stateListenersStopping();
        // then, unregister the service from the framework
        unregisterService();
        // invoke the stop callback
        invoke(m_callbackStop);
        // stop tracking optional services
        stopTrackingOptional();
        // inform the state listeners we've stopped
        stateListenersStopped();
        // invoke the destroy callback
        invoke(m_callbackDestroy);
        // destroy the service instance
        destroyService();
    }

    private void invoke(String name) {
        if (name != null) {
            // invoke method if it exists
            AccessibleObject.setAccessible(m_serviceInstance.getClass().getDeclaredMethods(), true);
            try {
                m_serviceInstance.getClass().getDeclaredMethod(name, null).invoke(m_serviceInstance, null);
            }
            catch (NoSuchMethodException e) {
                // ignore this, we don't care if the method does not exist
            }
            catch (Exception e) {
                // TODO handle this exception
                e.printStackTrace();
            }
        }
    }
    
    private synchronized void stateListenersStarting() {
        Iterator i = m_listeners.iterator();
        while (i.hasNext()) {
            ServiceStateListener ssl = (ServiceStateListener) i.next();
            ssl.starting(this);
        }
    }

    private synchronized void stateListenersStarted() {
        Iterator i = m_listeners.iterator();
        while (i.hasNext()) {
            ServiceStateListener ssl = (ServiceStateListener) i.next();
            ssl.started(this);
        }
    }

    private synchronized void stateListenersStopping() {
        Iterator i = m_listeners.iterator();
        while (i.hasNext()) {
            ServiceStateListener ssl = (ServiceStateListener) i.next();
            ssl.stopping(this);
        }
    }

    private synchronized void stateListenersStopped() {
        Iterator i = m_listeners.iterator();
        while (i.hasNext()) {
            ServiceStateListener ssl = (ServiceStateListener) i.next();
            ssl.stopped(this);
        }
    }

    private boolean allRequiredDependenciesAvailable() {
        Iterator i = getDependencies().iterator();
        while (i.hasNext()) {
            Dependency dependency = (Dependency) i.next();
            if (dependency.isRequired() && !dependency.isAvailable()) {
                return false;
            }
        }
        return true;
    }
    
    private void startTrackingOptional() {
        m_state = TRACKING_OPTIONAL;
        Iterator i = getDependencies().iterator();
        while (i.hasNext()) {
            Dependency dependency = (Dependency) i.next();
            if (!dependency.isRequired()) {
                dependency.start(this);
            }
        }
    }

    private void stopTrackingOptional() {
        m_state = WAITING_FOR_REQUIRED;
        Iterator i = getDependencies().iterator();
        while (i.hasNext()) {
            Dependency dependency = (Dependency) i.next();
            if (!dependency.isRequired()) {
                dependency.stop(this);
            }
        }
    }

    private void startTrackingRequired() {
        m_state = WAITING_FOR_REQUIRED;
        Iterator i = getDependencies().iterator();
        while (i.hasNext()) {
            Dependency dependency = (Dependency) i.next();
            if (dependency.isRequired()) {
                dependency.start(this);
            }
        }
    }

    private void stopTrackingRequired() {
        m_state = STOPPING;
        Iterator i = getDependencies().iterator();
        while (i.hasNext()) {
            Dependency dependency = (Dependency) i.next();
            if (dependency.isRequired()) {
                dependency.stop(this);
            }
        }
    }

    private void initService() {
        if (m_implementation instanceof Class) {
            // instantiate
            try {
                m_serviceInstance = ((Class) m_implementation).newInstance();
            } catch (InstantiationException e) {
                // TODO handle this exception
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                // TODO handle this exception
                e.printStackTrace();
            }
        }
        else {
            m_serviceInstance = m_implementation;
        }
        // configure the bundle context
        configureImplementation(BundleContext.class, m_context);
        configureImplementation(ServiceRegistration.class, NULL_REGISTRATION);
        
    }
    
    private void configureService() {
        // configure all services (the optional dependencies might be configured
        // as null objects but that's what we want at this point)
        configureServices();
    }

    private void destroyService() {
        unconfigureServices();
        m_serviceInstance = null;
    }
    
    private void registerService() {
        if (m_serviceName != null) {
            ServiceRegistrationImpl wrapper = new ServiceRegistrationImpl();
            m_registration = wrapper;
            configureImplementation(ServiceRegistration.class, wrapper);
            // service name can either be a string or an array of strings
            ServiceRegistration registration;
            if (m_serviceName instanceof String) {
                registration = m_context.registerService((String) m_serviceName, m_serviceInstance, m_serviceProperties);
            }
            else {
                registration = m_context.registerService((String[]) m_serviceName, m_serviceInstance, m_serviceProperties);
            }
            wrapper.setServiceRegistration(registration);
        }
    }
    
    private void unregisterService() {
        if (m_serviceName != null) {
            m_registration.unregister();
            configureImplementation(ServiceRegistration.class, NULL_REGISTRATION);
        }
    }
    
    private void updateInstance(Dependency dependency) {
        if (dependency instanceof ServiceDependency) {
            ServiceDependency sd = (ServiceDependency) dependency;
            // update the dependency in the service instance (it will use
            // a null object if necessary)
            if (sd.isAutoConfig()) {
                configureImplementation(sd.getInterface(), sd.getService());
            }
        }
    }
    
    /**
     * Configure a field in the service implementation. The service implementation
     * is searched for fields that have the same type as the class that was specified
     * and for each of these fields, the specified instance is filled in.
     * 
     * @param clazz the class to search for
     * @param instance the instance to fill in
     */
    private void configureImplementation(Class clazz, Object instance) {
        Class serviceClazz = m_serviceInstance.getClass();
        while (serviceClazz != null) {
            Field[] fields = serviceClazz.getDeclaredFields();
            AccessibleObject.setAccessible(fields, true);
            for (int j = 0; j < fields.length; j++) {
                if (fields[j].getType().equals(clazz)) {
                    try {
                        // synchronized makes sure the field is actually written to immediately
                        synchronized (new Object()) {
                            fields[j].set(m_serviceInstance, instance);
                        }
                    }
                    catch (Exception e) {
                        System.err.println("Exception while trying to set " + fields[j].getName() +
                            " of type " + fields[j].getType().getName() +
                            " by classloader " + fields[j].getType().getClassLoader() +
                            " which should equal type " + clazz.getName() +
                            " by classloader " + clazz.getClassLoader() +
                            " of type " + serviceClazz.getName() +
                            " by classloader " + serviceClazz.getClassLoader() +
                            " on " + m_serviceInstance + 
                            " by classloader " + m_serviceInstance.getClass().getClassLoader() +
                            "\nDumping stack:"
                        );
                        e.printStackTrace();
                        System.out.println("C: " + clazz);
                        System.out.println("I: " + instance);
                        System.out.println("I:C: " + instance.getClass().getClassLoader());
                        Class[] classes = instance.getClass().getInterfaces();
                        for (int i = 0; i < classes.length; i++) {
                            Class c = classes[i];
                            System.out.println("I:C:I: " + c);
                            System.out.println("I:C:I:C: " + c.getClassLoader());
                        }
                        System.out.println("F: " + fields[j]);
                        throw new IllegalStateException("Could not set field " + fields[j].getName() + " on " + m_serviceInstance);
                    }
                }
            }
            serviceClazz = serviceClazz.getSuperclass();
        }
    }

    private void configureServices() {
        Iterator i = getDependencies().iterator();
        while (i.hasNext()) {
            Dependency dependency = (Dependency) i.next();
            if (dependency instanceof ServiceDependency) {
                ServiceDependency sd = (ServiceDependency) dependency;
                if (sd.isAutoConfig()) {
                    configureImplementation(sd.getInterface(), sd.getService());
                }
                // for required dependencies, we invoke any callbacks here
                if (sd.isRequired()) {
                    sd.invokeAdded();
                }
            }
        }
    }
    
    private void unconfigureServices() {
        Iterator i = getDependencies().iterator();
        while (i.hasNext()) {
            Dependency dependency = (Dependency) i.next();
            if (dependency instanceof ServiceDependency) {
                ServiceDependency sd = (ServiceDependency) dependency;
                // for required dependencies, we invoke any callbacks here
                if (sd.isRequired()) {
                    sd.invokeRemoved();
                }
            }
        }
    }

    public synchronized void addStateListener(ServiceStateListener listener) {
        m_listeners.add(listener);
        if (m_state == TRACKING_OPTIONAL) {
        	listener.starting(this);
        	listener.started(this);
        }
    }

    public synchronized void removeStateListener(ServiceStateListener listener) {
        m_listeners.remove(listener);
    }

    synchronized void removeStateListeners() {
        m_listeners.clear();
    }
    
    public synchronized Service setInterface(String serviceName, Dictionary properties) {
        ensureNotActive();
        m_serviceName = serviceName;
        m_serviceProperties = properties;
        return this;
    }

    public synchronized Service setInterface(String[] serviceName, Dictionary properties) {
        ensureNotActive();
        m_serviceName = serviceName;
        m_serviceProperties = properties;
        return this;
    }
    
    public synchronized Service setCallbacks(String init, String start, String stop, String destroy) {
        ensureNotActive();
        m_callbackInit = init;
        m_callbackStart = start;
        m_callbackStop = stop;
        m_callbackDestroy = destroy;
        return this;
    }
    
    public synchronized Service setImplementation(Object implementation) {
        ensureNotActive();
        m_implementation = implementation;
        return this;
    }
    
    private void ensureNotActive() {
        if ((m_state == TRACKING_OPTIONAL) || (m_state == WAITING_FOR_REQUIRED)) {
            throw new IllegalStateException("Cannot modify state while active.");
        }
    }
    boolean isRegistered() {
        return (m_state == TRACKING_OPTIONAL);
    }
    
    public String toString() {
        return "ServiceImpl[" + m_serviceName + " " + m_implementation + "]";
    }

    public synchronized Dictionary getServiceProperties() {
        if (m_serviceProperties != null) {
            return (Dictionary) ((Hashtable) m_serviceProperties).clone();
        }
        return null;
    }

    public synchronized void setServiceProperties(Dictionary serviceProperties) {
        m_serviceProperties = serviceProperties;
        if (isRegistered() && (m_serviceName != null) && (m_serviceProperties != null)) {
            m_registration.setProperties(m_serviceProperties);
        }
    }
    
    static {
        NULL_REGISTRATION = (ServiceRegistration) Proxy.newProxyInstance(ServiceImpl.class.getClassLoader(), new Class[] {ServiceRegistration.class}, new DefaultNullObject()); 
    }
}
