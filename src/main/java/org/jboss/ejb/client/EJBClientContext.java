/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2011, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.ejb.client;

import org.jboss.ejb.client.remoting.ConfigBasedEJBClientContextSelector;
import org.jboss.ejb.client.remoting.ReconnectHandler;
import org.jboss.ejb.client.remoting.RemotingConnectionEJBReceiver;
import org.jboss.logging.Logger;
import org.jboss.remoting3.Connection;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

/**
 * The public API for an EJB client context.  An EJB client context may be associated with (and used by) one or more threads concurrently.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
@SuppressWarnings({"UnnecessaryThis"})
public final class EJBClientContext extends Attachable {

    private static final Logger logger = Logger.getLogger(EJBClientContext.class);

    private static final RuntimePermission SET_SELECTOR_PERMISSION = new RuntimePermission("setEJBClientContextSelector");
    private static final RuntimePermission ADD_INTERCEPTOR_PERMISSION = new RuntimePermission("registerInterceptor");
    private static final RuntimePermission CREATE_CONTEXT_PERMISSION = new RuntimePermission("createEJBClientContext");
    private static final AtomicReferenceFieldUpdater<EJBClientContext, EJBClientInterceptor.Registration[]> registrationsUpdater = AtomicReferenceFieldUpdater.newUpdater(EJBClientContext.class, EJBClientInterceptor.Registration[].class, "registrations");

    private static final EJBClientInterceptor.Registration[] NO_INTERCEPTORS = new EJBClientInterceptor.Registration[0];

    /**
     * EJB client context selector. By default the {@link ConfigBasedEJBClientContextSelector} is used.
     */
    private static volatile ContextSelector<EJBClientContext> SELECTOR;

    static {
        final Properties ejbClientProperties = EJBClientPropertiesLoader.loadEJBClientProperties();
        if (ejbClientProperties == null) {
            SELECTOR = new ConfigBasedEJBClientContextSelector(null);
        } else {
            final EJBClientConfiguration clientConfiguration = new PropertiesBasedEJBClientConfiguration(ejbClientProperties);
            SELECTOR = new ConfigBasedEJBClientContextSelector(clientConfiguration);
        }
    }

    private static volatile boolean SELECTOR_LOCKED;

    private final Map<EJBReceiver, ReceiverAssociation> ejbReceiverAssociations = new IdentityHashMap<EJBReceiver, ReceiverAssociation>();
    private final Map<EJBReceiverContext, EJBReceiverContextCloseHandler> receiverContextCloseHandlers = Collections.synchronizedMap(new IdentityHashMap<EJBReceiverContext, EJBReceiverContextCloseHandler>());
    private volatile EJBClientInterceptor.Registration[] registrations = NO_INTERCEPTORS;

    /**
     * Cluster contexts mapped against their cluster name
     */
    private final Map<String, ClusterContext> clusterContexts = Collections.synchronizedMap(new HashMap<String, ClusterContext>());

    private final EJBClientConfiguration ejbClientConfiguration;

    private final ClusterFormationNotifier clusterFormationNotifier = new ClusterFormationNotifier();

    private final ExecutorService reconnectionExecutorService = Executors.newCachedThreadPool(new DaemonThreadFactory("ejb-client-remote-connection-reconnect"));
    private final List<ReconnectHandler> reconnectHandlers = new ArrayList<ReconnectHandler>();

    private EJBClientContext(final EJBClientConfiguration ejbClientConfiguration) {
        this.ejbClientConfiguration = ejbClientConfiguration;
    }

    private void init(ClassLoader classLoader) {
        if (classLoader == null) {
            classLoader = EJBClientContext.class.getClassLoader();
        }
        for (final EJBClientContextInitializer contextInitializer : SecurityActions.loadService(EJBClientContextInitializer.class, classLoader)) {
            try {
                contextInitializer.initialize(this);
            } catch (Throwable ignored) {
                logger.debug("EJB client context initializer " + contextInitializer + " failed to initialize context " + this, ignored);
            }
        }
    }

    /**
     * Creates and returns a new client context.
     *
     * @return the newly created context
     */
    public static EJBClientContext create() {
        return create(null, EJBClientContext.class.getClassLoader());
    }

    /**
     * Creates and returns a new client context, using the given class loader to look for initializers.
     *
     * @param classLoader the class loader. Cannot be null
     * @return the newly created context
     */
    public static EJBClientContext create(ClassLoader classLoader) {
        return create(null, classLoader);
    }

    /**
     * Creates and returns a new client context. The passed <code>ejbClientConfiguration</code> will
     * be used by this client context during any of the context management activities (like auto-creation
     * of remoting EJB receivers)
     *
     * @param ejbClientConfiguration The EJB client configuration. Can be null.
     * @return
     */
    public static EJBClientContext create(final EJBClientConfiguration ejbClientConfiguration) {
        return create(ejbClientConfiguration, EJBClientContext.class.getClassLoader());
    }

    /**
     * Creates and returns a new client context, using the given class loader to look for initializers.
     * The passed <code>ejbClientConfiguration</code> will be used by this client context during any of
     * the context management activities (like auto-creation of remoting EJB receivers)
     *
     * @param ejbClientConfiguration The EJB client configuration. Can be null.
     * @param classLoader            The class loader. Cannot be null
     * @return
     */
    public static EJBClientContext create(final EJBClientConfiguration ejbClientConfiguration, final ClassLoader classLoader) {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(CREATE_CONTEXT_PERMISSION);
        }
        final EJBClientContext context = new EJBClientContext(ejbClientConfiguration);
        // run it through the initializers
        context.init(classLoader);
        return context;
    }

    /**
     * Sets the EJB client context selector. Replaces the existing selector, which is then returned by this method
     *
     * @param newSelector The selector to set. Cannot be null
     * @return Returns the previously set EJB client context selector.
     * @throws SecurityException if a security manager is installed and you do not have the {@code setEJBClientContextSelector}
     *                           {@link RuntimePermission}
     */
    public static ContextSelector<EJBClientContext> setSelector(final ContextSelector<EJBClientContext> newSelector) {
        if (newSelector == null) {
            throw new IllegalArgumentException("EJB client context selector cannot be set to null");
        }
        if (SELECTOR_LOCKED) {
            throw new SecurityException("EJB client context selector may not be changed");
        }
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(SET_SELECTOR_PERMISSION);
        }
        final ContextSelector<EJBClientContext> oldSelector = SELECTOR;
        SELECTOR = newSelector;
        return oldSelector;
    }

    /**
     * Set a constant EJB client context.  Replaces the existing selector, which is then returned by this method
     *
     * @param context the context to set
     * @return Returns the previously set EJB client context selector.
     * @throws SecurityException if a security manager is installed and you do not have the {@code setEJBClientContextSelector} {@link RuntimePermission}
     */
    public static ContextSelector<EJBClientContext> setConstantContext(final EJBClientContext context) {
        return setSelector(new ConstantContextSelector<EJBClientContext>(context));
    }

    /**
     * Prevent the selector from being changed again.  Attempts to do so will result in a {@code SecurityException}.
     *
     * @throws SecurityException if a security manager is installed and you do not have the {@code setEJBClientContextSelector}
     *                           {@link RuntimePermission}
     */
    public static void lockSelector() {
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(SET_SELECTOR_PERMISSION);
        }
        SELECTOR_LOCKED = true;
    }

    /**
     * Get the current client context for this thread.
     *
     * @return the current client context
     */
    public static EJBClientContext getCurrent() {
        return SELECTOR.getCurrent();
    }

    /**
     * Get the current client context for this thread, throwing an exception if none is set.
     *
     * @return the current client context
     * @throws IllegalStateException if the current client context is not set
     */
    public static EJBClientContext requireCurrent() throws IllegalStateException {
        final EJBClientContext clientContext = getCurrent();
        if (clientContext == null) {
            throw new IllegalStateException("No EJB client context is available");
        }
        return clientContext;
    }

    /**
     * Register an EJB receiver with this client context.
     * <p/>
     * If the same {@link EJBReceiver} has already been associated in this client context or if a {@link EJBReceiver receiver}
     * with the same {@link org.jboss.ejb.client.EJBReceiver#getNodeName() node name} has already been associated in this client
     * context, then this method does <i>not</i> register the passed <code>receiver</code> and returns false.
     *
     * @param receiver the receiver to register
     * @return Returns true if the receiver was registered in this client context. Else returns false.
     * @throws IllegalArgumentException If the passed <code>receiver</code> is null
     */
    public boolean registerEJBReceiver(final EJBReceiver receiver) {
        return this.registerEJBReceiver(receiver, null);
    }

    /**
     * Registers a {@link EJBReceiver} in this context and uses the {@link EJBReceiverContextCloseHandler receiverContextCloseHandler}
     * to notify of a {@link EJBReceiverContext} being closed.
     * <p/>
     * If the same {@link EJBReceiver} has already been associated in this client context or if a {@link EJBReceiver receiver}
     * with the same {@link org.jboss.ejb.client.EJBReceiver#getNodeName() node name} has already been associated in this client
     * context, then this method does <i>not</i> register the passed <code>receiver</code> and returns false.
     *
     * @param receiver                    The EJB receiver to register
     * @param receiverContextCloseHandler The receiver context close handler. Can be null.
     * @return Returns true if the receiver was registered in this client context. Else returns false.
     */
    boolean registerEJBReceiver(final EJBReceiver receiver, final EJBReceiverContextCloseHandler receiverContextCloseHandler) {
        if (receiver == null) {
            throw new IllegalArgumentException("Cannot register a null receiver");
        }
        final EJBReceiverContext ejbReceiverContext;
        final ReceiverAssociation association;
        synchronized (this.ejbReceiverAssociations) {
            if (this.ejbReceiverAssociations.containsKey(receiver)) {
                logger.debug("Skipping registration of receiver " + receiver + " since the same instance already exists in this client context " + this);
                // nothing to do
                return false;
            }
            // see if we already have a receiver for the node name corresponding to the receiver
            // being registered
            final EJBReceiver existingReceiverForNode = this.getNodeEJBReceiver(receiver.getNodeName(), false);
            if (existingReceiverForNode != null) {
                logger.debug("Skipping registration of receiver " + receiver + " since an EJB receiver already exists for " +
                        "node name " + receiver.getNodeName() + " in client context " + this);
                return false;
            }

            ejbReceiverContext = new EJBReceiverContext(receiver, this);
            association = new ReceiverAssociation(ejbReceiverContext);
            this.ejbReceiverAssociations.put(receiver, association);
            // register a close handler, if any, for this receiver context
            if (receiverContextCloseHandler != null) {
                this.receiverContextCloseHandlers.put(ejbReceiverContext, receiverContextCloseHandler);
            }
        }
        // associate it with a context
        receiver.associate(ejbReceiverContext);

        synchronized (this.ejbReceiverAssociations) {

            association.associated = true;
            // Associating a receiver with a context might be either successful or might fail (for example:
            // failure in version handshake between client/server), in which case the receiver context
            // will be closed and ultimately the association removed from this client context.
            // So registration is successful only if the association is still in the associations map of this
            // client context
            return this.ejbReceiverAssociations.get(receiver) != null;
        }
    }

    /**
     * Unregister (a previously registered) EJB receiver from this client context.
     * <p/>
     * This EJB client context will not use this unregistered receiver for any subsequent
     * invocations
     *
     * @param receiver The EJB receiver to unregister
     * @throws IllegalArgumentException If the passed <code>receiver</code> is null
     */
    public void unregisterEJBReceiver(final EJBReceiver receiver) {
        if (receiver == null) {
            throw new IllegalArgumentException("Receiver cannot be null");
        }
        synchronized (this.ejbReceiverAssociations) {
            final ReceiverAssociation association = this.ejbReceiverAssociations.remove(receiver);
            if (association != null) {
                final EJBReceiverContext receiverContext = association.context;
                final EJBReceiverContextCloseHandler receiverContextCloseHandler = this.receiverContextCloseHandlers.remove(receiverContext);
                if (receiverContextCloseHandler != null) {
                    receiverContextCloseHandler.receiverContextClosed(receiverContext);
                }
            }
        }
    }

    /**
     * Register a Remoting connection with this client context.
     *
     * @param connection the connection to register
     */
    public void registerConnection(final Connection connection) {
        registerEJBReceiver(new RemotingConnectionEJBReceiver(connection));
    }

    /**
     * Register a client interceptor with this client context.
     * <p/>
     * If the passed <code>clientInterceptor</code> is already added to this context with the same <code>priority</code>
     * then this method just returns the old {@link org.jboss.ejb.client.EJBClientInterceptor.Registration}. If however,
     * the <code>clientInterceptor</code> is already registered in this context with a different priority then this method
     * throws an {@link IllegalArgumentException}
     *
     * @param priority          the absolute priority of this interceptor (lower runs earlier; higher runs later)
     * @param clientInterceptor the interceptor to register
     * @return a handle which may be used to later remove this registration
     * @throws IllegalArgumentException if the given interceptor is {@code null}, the priority is less than 0, or the
     *                                  given interceptor is already registered with a different priority
     */
    public EJBClientInterceptor.Registration registerInterceptor(final int priority, final EJBClientInterceptor clientInterceptor) throws IllegalArgumentException {
        if (clientInterceptor == null) {
            throw new IllegalArgumentException("clientInterceptor is null");
        }
        final SecurityManager sm = System.getSecurityManager();
        if (sm != null) {
            sm.checkPermission(ADD_INTERCEPTOR_PERMISSION);
        }
        final EJBClientInterceptor.Registration newRegistration = new EJBClientInterceptor.Registration(this, clientInterceptor, priority);
        EJBClientInterceptor.Registration[] oldRegistrations, newRegistrations;
        do {
            oldRegistrations = registrations;
            for (EJBClientInterceptor.Registration oldRegistration : oldRegistrations) {
                if (oldRegistration.getInterceptor() == clientInterceptor) {
                    if (oldRegistration.compareTo(newRegistration) == 0) {
                        // This means that a client interceptor which has already been added to this context,
                        // is being added with the same priority. In such cases, this new registration request
                        // is effectively a no-op and we just return the old registration
                        return oldRegistration;
                    } else {
                        // This means that a client interceptor which has been added to this context, is being added
                        // again with a different priority. We don't allow that to happen
                        throw new IllegalArgumentException("Interceptor '" + clientInterceptor + "' is already registered");
                    }
                }
            }
            final int length = oldRegistrations.length;
            newRegistrations = Arrays.copyOf(oldRegistrations, length + 1);
            newRegistrations[length] = newRegistration;
            Arrays.sort(newRegistrations);
        } while (!registrationsUpdater.compareAndSet(this, oldRegistrations, newRegistrations));
        return newRegistration;
    }

    /**
     * Returns the {@link EJBClientConfiguration} applicable to this EJB client context. Returns null
     * if this EJB client context isn't configured with a {@link EJBClientConfiguration}
     *
     * @return
     */
    public EJBClientConfiguration getEJBClientConfiguration() {
        return this.ejbClientConfiguration;
    }

    /**
     * Registers a {@link ReconnectHandler} in this {@link EJBClientContext}
     *
     * @param reconnectHandler The reconnect handler. Cannot be null
     */
    public void registerReconnectHandler(final ReconnectHandler reconnectHandler) {
        if (reconnectHandler == null) {
            throw new IllegalArgumentException("Reconnect handler cannot be null");
        }
        synchronized (this.reconnectHandlers) {
            this.reconnectHandlers.add(reconnectHandler);
        }
    }

    /**
     * Unregisters a {@link ReconnectHandler} from this {@link EJBClientContext}
     *
     * @param reconnectHandler The reconnect handler to unregister
     */
    public void unregisterReconnectHandler(final ReconnectHandler reconnectHandler) {
        synchronized (this.reconnectHandlers) {
            this.reconnectHandlers.remove(reconnectHandler);
        }
    }

    void removeInterceptor(final EJBClientInterceptor.Registration registration) {
        EJBClientInterceptor.Registration[] oldRegistrations, newRegistrations;
        do {
            oldRegistrations = registrations;
            newRegistrations = null;
            final int length = oldRegistrations.length;
            final int newLength = length - 1;
            if (length == 1) {
                if (oldRegistrations[0].getInterceptor() == registration) {
                    newRegistrations = NO_INTERCEPTORS;
                }
            } else {
                for (int i = 0; i < length; i++) {
                    if (oldRegistrations[i].getInterceptor() == registration) {
                        if (i == newLength) {
                            newRegistrations = Arrays.copyOf(oldRegistrations, newLength);
                            break;
                        } else {
                            newRegistrations = new EJBClientInterceptor.Registration[newLength];
                            if (i > 0) System.arraycopy(oldRegistrations, 0, newRegistrations, 0, i);
                            System.arraycopy(oldRegistrations, i + 1, newRegistrations, i, newLength - i);
                            break;
                        }
                    }
                }
            }
            if (newRegistrations == null) {
                return;
            }
        } while (!registrationsUpdater.compareAndSet(this, oldRegistrations, newRegistrations));
    }

    Collection<EJBReceiver> getEJBReceivers(final String appName, final String moduleName, final String distinctName) {
        return this.getEJBReceivers(appName, moduleName, distinctName, true);
    }

    private Collection<EJBReceiver> getEJBReceivers(final String appName, final String moduleName, final String distinctName,
                                                    final boolean attemptReconnect) {
        final Collection<EJBReceiver> eligibleEJBReceivers = new HashSet<EJBReceiver>();
        synchronized (this.ejbReceiverAssociations) {
            for (final Map.Entry<EJBReceiver, ReceiverAssociation> entry : this.ejbReceiverAssociations.entrySet()) {
                if (entry.getValue().associated) {
                    final EJBReceiver ejbReceiver = entry.getKey();
                    if (ejbReceiver.acceptsModule(appName, moduleName, distinctName)) {
                        eligibleEJBReceivers.add(ejbReceiver);
                    }
                }
            }
        }
        if (eligibleEJBReceivers.isEmpty() && attemptReconnect) {
            // we found no receivers, so see if we there are re-connect handlers which can create possible
            // receivers
            this.attemptReconnections();
            // now that the re-connect handlers have run, let's fetch the receivers (if any) for this app/module/distinct-name
            // combination. We won't attempt any reconnections now.
            eligibleEJBReceivers.addAll(this.getEJBReceivers(appName, moduleName, distinctName, false));
        }
        return eligibleEJBReceivers;
    }

    /**
     * Get the first EJB receiver which matches the given combination of app, module and distinct name.
     *
     * @param appName      the application name, or {@code null} for a top-level module
     * @param moduleName   the module name
     * @param distinctName the distinct name, or {@code null} for none
     * @return the first EJB receiver to match, or {@code null} if none match
     */
    EJBReceiver getEJBReceiver(final String appName, final String moduleName, final String distinctName) {
        final Iterator<EJBReceiver> iterator = getEJBReceivers(appName, moduleName, distinctName).iterator();
        return iterator.hasNext() ? iterator.next() : null;
    }

    /**
     * Get the first EJB receiver which matches the given combination of app, module and distinct name. If there's
     * no such EJB receiver, then this method throws a {@link IllegalStateException}
     *
     * @param appName      the application name, or {@code null} for a top-level module
     * @param moduleName   the module name
     * @param distinctName the distinct name, or {@code null} for none
     * @return the first EJB receiver to match
     * @throws IllegalStateException If there's no {@link EJBReceiver} which can handle a EJB for the passed combination
     *                               of app, module and distinct name.
     */
    EJBReceiver requireEJBReceiver(final String appName, final String moduleName, final String distinctName)
            throws IllegalStateException {

        // try and find a receiver which can handle this combination
        final EJBReceiver ejbReceiver = this.getEJBReceiver(appName, moduleName, distinctName);
        if (ejbReceiver == null) {
            throw new IllegalStateException("No EJB receiver available for handling [appName:" + appName + ",modulename:"
                    + moduleName + ",distinctname:" + distinctName + "] combination");
        }
        return ejbReceiver;
    }

    /**
     * Returns a {@link EJBReceiverContext} for the passed <code>receiver</code>. If the <code>receiver</code>
     * hasn't been registered with this {@link EJBClientContext}, either through a call to {@link #registerConnection(org.jboss.remoting3.Connection)}
     * or to {@link #requireEJBReceiver(String, String, String)}, then this method throws an {@link IllegalStateException}
     * <p/>
     *
     * @param receiver The {@link EJBReceiver} for which the {@link EJBReceiverContext} is being requested
     * @return The {@link EJBReceiverContext}
     * @throws IllegalStateException If the passed <code>receiver</code> hasn't been registered with this {@link EJBClientContext}
     */
    EJBReceiverContext requireEJBReceiverContext(final EJBReceiver receiver) throws IllegalStateException {
        synchronized (this.ejbReceiverAssociations) {
            final ReceiverAssociation association = this.ejbReceiverAssociations.get(receiver);
            if (association == null) {
                throw new IllegalStateException(receiver + " has not been associated with " + this);
            }
            return association.context;
        }
    }

    EJBReceiver requireNodeEJBReceiver(final String nodeName) {
        final EJBReceiver receiver = getNodeEJBReceiver(nodeName);
        if (receiver != null) return receiver;
        throw new IllegalStateException("No EJBReceiver available for node name " + nodeName);
    }

    EJBReceiver getNodeEJBReceiver(final String nodeName) {
        return this.getNodeEJBReceiver(nodeName, true);
    }

    private EJBReceiver getNodeEJBReceiver(final String nodeName, final boolean attemptReconnect) {
        if (nodeName == null) {
            throw new IllegalArgumentException("Node name cannot be null");
        }

        synchronized (this.ejbReceiverAssociations) {
            for (final Map.Entry<EJBReceiver, ReceiverAssociation> entry : this.ejbReceiverAssociations.entrySet()) {
                if (entry.getValue().associated) {
                    final EJBReceiver ejbReceiver = entry.getKey();
                    if (nodeName.equals(ejbReceiver.getNodeName())) {
                        return ejbReceiver;
                    }
                }
            }
        }
        // no EJB receiver found for the node name, so let's see if there are re-connect handlers which can
        // create the EJB receivers
        if (attemptReconnect) {
            this.attemptReconnections();
            // now that re-connections have been attempted, let's fetch any EJB receiver for this node name.
            // we won't try reconnecting again now
            return this.getNodeEJBReceiver(nodeName, false);
        }

        return null;
    }

    EJBReceiverContext requireNodeEJBReceiverContext(final String nodeName) {
        final EJBReceiver ejbReceiver = requireNodeEJBReceiver(nodeName);
        return requireEJBReceiverContext(ejbReceiver);
    }

    EJBReceiverContext getNodeEJBReceiverContext(final String nodeName) {
        final EJBReceiver ejbReceiver = getNodeEJBReceiver(nodeName);
        return ejbReceiver == null ? null : requireEJBReceiverContext(ejbReceiver);
    }

    /**
     * Returns true if the <code>nodeName</code> belongs to a cluster named <code>clusterName</code>. Else
     * returns false.
     *
     * @param clusterName The name of the cluster
     * @param nodeName    The name of the node within the cluster
     * @return
     */
    boolean clusterContains(final String clusterName, final String nodeName) {
        final ClusterContext clusterContext = this.clusterContexts.get(clusterName);
        if (clusterContext == null) {
            return false;
        }
        return clusterContext.isNodeAvailable(nodeName);
    }

    /**
     * Returns a {@link EJBReceiverContext} for the <code>clusterName</code>. If there's no such receiver context
     * for the cluster, then this method returns null
     *
     * @param clusterName The name of the cluster
     * @return
     */
    EJBReceiverContext getClusterEJBReceiverContext(final String clusterName) throws IllegalArgumentException {
        return this.getClusterEJBReceiverContext(clusterName, true);
    }

    private EJBReceiverContext getClusterEJBReceiverContext(final String clusterName, final boolean attemptReconnect) throws IllegalArgumentException {
        final ClusterContext clusterContext = this.clusterContexts.get(clusterName);
        if (clusterContext == null) {
            return null;
        }
        final EJBReceiverContext ejbReceiverContext = clusterContext.getEJBReceiverContext();
        if (ejbReceiverContext == null && attemptReconnect) {
            // no receiver context was found for the cluster. So let's see if there are any re-connect handlers
            // which can generate the EJB receivers
            this.attemptReconnections();
            // now that we have attempted the re-connections, let's fetch any EJB receiver context for the cluster.
            // we won't try re-connecting again now
            return this.getClusterEJBReceiverContext(clusterName, false);
        }
        return ejbReceiverContext;
    }

    /**
     * Returns a {@link EJBReceiverContext} for the <code>clusterName</code>. If there's no such receiver context
     * for the cluster, then this method throws an {@link IllegalArgumentException}
     *
     * @param clusterName The name of the cluster
     * @return
     * @throws IllegalArgumentException If there's no EJB receiver context available for the cluster
     */
    EJBReceiverContext requireClusterEJBReceiverContext(final String clusterName) throws IllegalArgumentException {
        ClusterContext clusterContext = this.clusterContexts.get(clusterName);
        if (clusterContext == null) {
            // let's wait for some time to see if the asynchronous cluster topology becomes available.
            // Note that this isn't a great thing to do for clusters which might have been removed or for clusters
            // which will never be formed, since this wait results in a 5 second delay in the invocation. But ideally
            // such cases should be pretty low.
            logger.debug("Waiting for cluster topology information to be available for cluster named " + clusterName);
            this.waitForClusterTopology(clusterName);
            // see if the cluster context was created during this wait time
            clusterContext = this.clusterContexts.get(clusterName);
            if (clusterContext == null) {
                throw new IllegalArgumentException("No cluster context (and as a result EJB receiver context) available for cluster named " + clusterName);
            }
        }
        final EJBReceiverContext ejbReceiverContext = this.getClusterEJBReceiverContext(clusterName);
        if (ejbReceiverContext == null) {
            throw new IllegalStateException("No EJB receiver contexts available in cluster " + clusterName);
        }
        return ejbReceiverContext;
    }

    EJBClientInterceptor[] getInterceptorChain() {
        // todo optimize to eliminate copy
        final EJBClientInterceptor.Registration[] registrations = this.registrations;
        final EJBClientInterceptor[] interceptors = new EJBClientInterceptor[registrations.length];
        for (int i = 0; i < registrations.length; i++) {
            interceptors[i] = registrations[i].getInterceptor();
        }
        return interceptors;
    }

    /**
     * Returns a {@link ClusterContext} corresponding to the passed <code>clusterName</code>. If no
     * such cluster context exists, a new one is created and returned. Subsequent invocations on this
     * {@link EJBClientContext} for the same cluster name will return this same {@link ClusterContext}, unless
     * the cluster has been removed from this client context.
     *
     * @param clusterName The name of the cluster
     * @return
     */
    public synchronized ClusterContext getOrCreateClusterContext(final String clusterName) {
        ClusterContext clusterContext = this.clusterContexts.get(clusterName);
        if (clusterContext == null) {
            clusterContext = new ClusterContext(clusterName, this, this.ejbClientConfiguration);
            this.clusterContexts.put(clusterName, clusterContext);
            // notify any waiting listeners about cluster formation
            this.clusterFormationNotifier.notifyClusterFormation(clusterName);
        }
        return clusterContext;
    }

    /**
     * Returns a {@link ClusterContext} corresponding to the passed <code>clusterName</code>. If no
     * such cluster context exists, then this method returns null.
     *
     * @param clusterName The name of the cluster
     * @return
     */
    public synchronized ClusterContext getClusterContext(final String clusterName) {
        return this.clusterContexts.get(clusterName);
    }

    /**
     * Removes the cluster identified by the <code>clusterName</code> from this client context
     *
     * @param clusterName The name of the cluster
     */
    public synchronized void removeCluster(final String clusterName) {
        final ClusterContext clusterContext = this.clusterContexts.remove(clusterName);
        if (clusterContext == null) {
            return;
        }
        try {
            // close the cluster context to allow it to cleanup any resources
            clusterContext.close();
        } catch (Throwable t) {
            // ignore
            logger.debug("Ignoring an error that occured while closing a cluster context for cluster named " + clusterName, t);
        }
    }

    /**
     * Waits for (a maximum of 5 seconds for) a cluster topology to be available for <code>clusterName</code>
     *
     * @param clusterName The name of the cluster
     */
    private void waitForClusterTopology(final String clusterName) {
        final CountDownLatch clusterFormationLatch = new CountDownLatch(1);
        // register for the notification
        this.clusterFormationNotifier.registerForClusterFormation(clusterName, clusterFormationLatch);
        // now wait (max 5 seconds)
        try {
            final boolean receivedClusterTopology = clusterFormationLatch.await(5, TimeUnit.SECONDS);
            if (receivedClusterTopology) {
                logger.debug("Received the cluster topology for cluster named " + clusterName + " during the wait time");
            }
        } catch (InterruptedException e) {
            // ignore
        } finally {
            // unregister from the cluster formation notification
            this.clusterFormationNotifier.unregisterFromClusterNotification(clusterName, clusterFormationLatch);
        }
    }

    private synchronized void attemptReconnections() {
        final CountDownLatch reconnectTasksCompletionNotifierLatch;
        if (this.reconnectHandlers.isEmpty()) {
            // no reconnections to attempt just return
            return;
        }
        reconnectTasksCompletionNotifierLatch = new CountDownLatch(this.reconnectHandlers.size());
        for (final ReconnectHandler reconnectHandler : this.reconnectHandlers) {
            // submit each of the reconnection tasks
            this.reconnectionExecutorService.submit(new ReconnectAttempt(reconnectHandler, reconnectTasksCompletionNotifierLatch));
        }
        // wait for all tasks to complete (with a upper bound on time limit)
        try {
            reconnectTasksCompletionNotifierLatch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    /**
     * A {@link EJBReceiverContextCloseHandler} will be notified through a call to
     * {@link #receiverContextClosed(EJBReceiverContext)} whenever a {@link EJBReceiverContext}, to which
     * the {@link EJBReceiverContextCloseHandler}, has been {@link EJBClientContext#registerEJBReceiver(EJBReceiver, org.jboss.ejb.client.EJBClientContext.EJBReceiverContextCloseHandler) associated}
     * is closed by this {@link EJBClientContext}
     */
    interface EJBReceiverContextCloseHandler {
        /**
         * A callback method which will be invoked when the {@link EJBReceiverContext receiverContext}
         * is closed. This method can do the necessary cleanup (if any) of resources associated with the
         * receiver context
         *
         * @param receiverContext The receiver context which was closed
         */
        void receiverContextClosed(final EJBReceiverContext receiverContext);
    }

    private static final class ReceiverAssociation {
        final EJBReceiverContext context;
        boolean associated = false;

        private ReceiverAssociation(final EJBReceiverContext context) {
            this.context = context;
        }
    }

    /**
     * A notifier which can be used for waiting for cluster formation events
     */
    private final class ClusterFormationNotifier {

        private final Map<String, List<CountDownLatch>> clusterFormationListeners = new HashMap<String, List<CountDownLatch>>();

        /**
         * Register for cluster formation event notification.
         *
         * @param clusterName The name of the cluster
         * @param latch       The {@link CountDownLatch} which the caller can use to wait for the cluster formation
         *                    to take place. The {@link ClusterFormationNotifier} will invoke the {@link java.util.concurrent.CountDownLatch#countDown()}
         *                    when the cluster is formed
         */
        void registerForClusterFormation(final String clusterName, final CountDownLatch latch) {
            synchronized (this.clusterFormationListeners) {
                List<CountDownLatch> listeners = this.clusterFormationListeners.get(clusterName);
                if (listeners == null) {
                    listeners = new ArrayList<CountDownLatch>();
                    this.clusterFormationListeners.put(clusterName, listeners);
                }
                listeners.add(latch);
            }
        }

        /**
         * Callback invocation for the cluster formation event. This method will invoke {@link java.util.concurrent.CountDownLatch#countDown()}
         * on each of the waiting {@link CountDownLatch} for the cluster.
         *
         * @param clusterName The name of the cluster
         */
        void notifyClusterFormation(final String clusterName) {
            final List<CountDownLatch> listeners;
            synchronized (this.clusterFormationListeners) {
                // remove the waiting listeners
                listeners = this.clusterFormationListeners.remove(clusterName);
            }
            if (listeners == null) {
                return;
            }
            // notify any waiting listeners
            for (final CountDownLatch latch : listeners) {
                latch.countDown();
            }
        }

        /**
         * Unregisters from cluster formation notifications for the cluster
         *
         * @param clusterName The name of the cluster
         * @param latch       The {@link CountDownLatch} which will be unregistered from the waiting {@link CountDownLatch}es
         */
        void unregisterFromClusterNotification(final String clusterName, final CountDownLatch latch) {
            synchronized (this.clusterFormationListeners) {
                final List<CountDownLatch> listeners = this.clusterFormationListeners.get(clusterName);
                if (listeners == null) {
                    return;
                }
                listeners.remove(latch);
            }
        }
    }

    private class ReconnectAttempt implements Runnable {

        private final ReconnectHandler reconnectHandler;
        private final CountDownLatch taskCompletionNotifierLatch;

        ReconnectAttempt(final ReconnectHandler reconnectHandler, final CountDownLatch taskCompletionNotifierLatch) {
            this.reconnectHandler = reconnectHandler;
            this.taskCompletionNotifierLatch = taskCompletionNotifierLatch;
        }

        @Override
        public void run() {
            try {
                this.reconnectHandler.reconnect();
            } catch (Exception e) {
                logger.debug("Exception trying to re-establish a connection from EJB client context " + EJBClientContext.this, e);
            } finally {
                this.taskCompletionNotifierLatch.countDown();
            }
        }
    }
}
