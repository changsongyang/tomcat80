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


package org.apache.catalina.session;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.util.HashMap;
import java.util.Map;

import org.apache.catalina.Lifecycle;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.LifecycleState;
import org.apache.catalina.Session;
import org.apache.catalina.Store;
import org.apache.catalina.security.SecurityUtil;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
/**
 * Extends the <b>ManagerBase</b> class to implement most of the
 * functionality required by a Manager which supports any kind of
 * persistence, even if only for  restarts.
 * <p>
 * <b>IMPLEMENTATION NOTE</b>:  Correct behavior of session storing and
 * reloading depends upon external calls to the <code>start()</code> and
 * <code>stop()</code> methods of this class at the correct times.
 *
 * @author Craig R. McClanahan
 * @author Jean-Francois Arcand
 * @version $Id$
 */

public abstract class PersistentManagerBase extends ManagerBase {

    private static final Log log = LogFactory.getLog(PersistentManagerBase.class);

    // ---------------------------------------------------- Security Classes

    private class PrivilegedStoreClear
        implements PrivilegedExceptionAction<Void> {

        PrivilegedStoreClear() {
            // NOOP
        }

        @Override
        public Void run() throws Exception{
           store.clear();
           return null;
        }                       
    }   
     
    private class PrivilegedStoreRemove
        implements PrivilegedExceptionAction<Void> {

        private String id;    
            
        PrivilegedStoreRemove(String id) {     
            this.id = id;
        }

        @Override
        public Void run() throws Exception{
           store.remove(id);
           return null;
        }                       
    }   
     
    private class PrivilegedStoreLoad
        implements PrivilegedExceptionAction<Session> {

        private String id;    
            
        PrivilegedStoreLoad(String id) {     
            this.id = id;
        }

        @Override
        public Session run() throws Exception{
           return store.load(id);
        }                       
    }   
          
    private class PrivilegedStoreSave
        implements PrivilegedExceptionAction<Void> {

        private Session session;    
            
        PrivilegedStoreSave(Session session) {     
            this.session = session;
        }

        @Override
        public Void run() throws Exception{
           store.save(session);
           return null;
        }                       
    }   
     
    private class PrivilegedStoreKeys
        implements PrivilegedExceptionAction<String[]> {

        PrivilegedStoreKeys() {
            // NOOP
        }

        @Override
        public String[] run() throws Exception{
           return store.keys();
        }                       
    }

    // ----------------------------------------------------- Instance Variables


    /**
     * The descriptive information about this implementation.
     */
    private static final String info = "PersistentManagerBase/1.1";


    /**
     * The descriptive name of this Manager implementation (for logging).
     */
    private static String name = "PersistentManagerBase";


    /**
     * Store object which will manage the Session store.
     */
    protected Store store = null;


    /**
     * Whether to save and reload sessions when the Manager <code>unload</code>
     * and <code>load</code> methods are called.
     */
    protected boolean saveOnRestart = true;


    /**
     * How long a session must be idle before it should be backed up.
     * -1 means sessions won't be backed up.
     */
    protected int maxIdleBackup = -1;


    /**
     * Minimum time a session must be idle before it is swapped to disk.
     * This overrides maxActiveSessions, to prevent thrashing if there are lots
     * of active sessions. Setting to -1 means it's ignored.
     */
    protected int minIdleSwap = -1;

    /**
     * The maximum time a session may be idle before it should be swapped
     * to file just on general principle. Setting this to -1 means sessions
     * should not be forced out.
     */
    protected int maxIdleSwap = -1;


    /**
     * Processing time during session expiration and passivation.
     */
    protected long processingTime = 0;


    /**
     * Sessions currently being swapped in and the associated locks
     */
    private final Map<String,Object> sessionSwapInLocks =
        new HashMap<String,Object>();


    // ------------------------------------------------------------- Properties


    /**
     * Indicates how many seconds old a session can get, after its last use in a
     * request, before it should be backed up to the store. -1 means sessions
     * are not backed up.
     */
    public int getMaxIdleBackup() {

        return maxIdleBackup;

    }


    /**
     * Sets the option to back sessions up to the Store after they
     * are used in a request. Sessions remain available in memory
     * after being backed up, so they are not passivated as they are
     * when swapped out. The value set indicates how old a session
     * may get (since its last use) before it must be backed up: -1
     * means sessions are not backed up.
     * <p>
     * Note that this is not a hard limit: sessions are checked
     * against this age limit periodically according to <b>processExpiresFrequency</b>.
     * This value should be considered to indicate when a session is
     * ripe for backing up.
     * <p>
     * So it is possible that a session may be idle for maxIdleBackup +
     * processExpiresFrequency * engine.backgroundProcessorDelay seconds, plus the time it takes to handle other
     * session expiration, swapping, etc. tasks.
     *
     * @param backup The number of seconds after their last accessed
     * time when they should be written to the Store.
     */
    public void setMaxIdleBackup (int backup) {

        if (backup == this.maxIdleBackup)
            return;
        int oldBackup = this.maxIdleBackup;
        this.maxIdleBackup = backup;
        support.firePropertyChange("maxIdleBackup",
                                   new Integer(oldBackup),
                                   new Integer(this.maxIdleBackup));

    }


    /**
     * The time in seconds after which a session should be swapped out of
     * memory to disk.
     */
    public int getMaxIdleSwap() {

        return maxIdleSwap;

    }


    /**
     * Sets the time in seconds after which a session should be swapped out of
     * memory to disk.
     */
    public void setMaxIdleSwap(int max) {

        if (max == this.maxIdleSwap)
            return;
        int oldMaxIdleSwap = this.maxIdleSwap;
        this.maxIdleSwap = max;
        support.firePropertyChange("maxIdleSwap",
                                   new Integer(oldMaxIdleSwap),
                                   new Integer(this.maxIdleSwap));

    }


    /**
     * The minimum time in seconds that a session must be idle before
     * it can be swapped out of memory, or -1 if it can be swapped out
     * at any time.
     */
    public int getMinIdleSwap() {

        return minIdleSwap;

    }


    /**
     * Sets the minimum time in seconds that a session must be idle before
     * it can be swapped out of memory due to maxActiveSession. Set it to -1
     * if it can be swapped out at any time.
     */
    public void setMinIdleSwap(int min) {

        if (this.minIdleSwap == min)
            return;
        int oldMinIdleSwap = this.minIdleSwap;
        this.minIdleSwap = min;
        support.firePropertyChange("minIdleSwap",
                                   new Integer(oldMinIdleSwap),
                                   new Integer(this.minIdleSwap));

    }


    /**
     * Return descriptive information about this Manager implementation and
     * the corresponding version number, in the format
     * <code>&lt;description&gt;/&lt;version&gt;</code>.
     */
    @Override
    public String getInfo() {

        return (info);

    }


    /**
     * Return true, if the session id is loaded in memory
     * otherwise false is returned
     *
     * @param id The session id for the session to be searched for
     */
    public boolean isLoaded( String id ){
        try {
            if ( super.findSession(id) != null )
                return true;
        } catch (IOException e) {
            log.error("checking isLoaded for id, " + id + ", "+e.getMessage(), e);
        }
        return false;
    }


    /**
     * Return the descriptive short name of this Manager implementation.
     */
    @Override
    public String getName() {

        return (name);

    }


    /**
     * Set the Store object which will manage persistent Session
     * storage for this Manager.
     *
     * @param store the associated Store
     */
    public void setStore(Store store) {
        this.store = store;
        store.setManager(this);

    }


    /**
     * Return the Store object which manages persistent Session
     * storage for this Manager.
     */
    public Store getStore() {

        return (this.store);

    }


    /**
     * Indicates whether sessions are saved when the Manager is shut down
     * properly. This requires the unload() method to be called.
     */
    public boolean getSaveOnRestart() {

        return saveOnRestart;

    }


    /**
     * Set the option to save sessions to the Store when the Manager is
     * shut down, then loaded when the Manager starts again. If set to
     * false, any sessions found in the Store may still be picked up when
     * the Manager is started again.
     *
     * @param saveOnRestart true if sessions should be saved on restart, false if
     *     they should be ignored.
     */
    public void setSaveOnRestart(boolean saveOnRestart) {

        if (saveOnRestart == this.saveOnRestart)
            return;

        boolean oldSaveOnRestart = this.saveOnRestart;
        this.saveOnRestart = saveOnRestart;
        support.firePropertyChange("saveOnRestart",
                                   new Boolean(oldSaveOnRestart),
                                   new Boolean(this.saveOnRestart));

    }


    // --------------------------------------------------------- Public Methods


    /**
     * Clear all sessions from the Store.
     */
    public void clearStore() {

        if (store == null)
            return;

        try {     
            if (SecurityUtil.isPackageProtectionEnabled()){
                try{
                    AccessController.doPrivileged(new PrivilegedStoreClear());
                }catch(PrivilegedActionException ex){
                    Exception exception = ex.getException();
                    log.error("Exception clearing the Store: " + exception);
                    exception.printStackTrace();                        
                }
            } else {
                store.clear();
            }
        } catch (IOException e) {
            log.error("Exception clearing the Store: " + e);
            e.printStackTrace();
        }

    }


    /**
     * Implements the Manager interface, direct call to processExpires and processPersistenceChecks
     */
    @Override
    public void processExpires() {
        
        long timeNow = System.currentTimeMillis();
        Session sessions[] = findSessions();
        int expireHere = 0 ;
        if(log.isDebugEnabled())
             log.debug("Start expire sessions " + getName() + " at " + timeNow + " sessioncount " + sessions.length);
        for (int i = 0; i < sessions.length; i++) {
            if (!sessions[i].isValid()) {
                expiredSessions++;
                expireHere++;
            }
        }
        processPersistenceChecks();
        if ((getStore() != null) && (getStore() instanceof StoreBase)) {
            ((StoreBase) getStore()).processExpires();
        }
        
        long timeEnd = System.currentTimeMillis();
        if(log.isDebugEnabled())
             log.debug("End expire sessions " + getName() + " processingTime " + (timeEnd - timeNow) + " expired sessions: " + expireHere);
        processingTime += (timeEnd - timeNow);
         
    }


    /**
     * Called by the background thread after active sessions have been checked
     * for expiration, to allow sessions to be swapped out, backed up, etc.
     */
    public void processPersistenceChecks() {

        processMaxIdleSwaps();
        processMaxActiveSwaps();
        processMaxIdleBackups();

    }


    /**
     * Return the active Session, associated with this Manager, with the
     * specified session id (if any); otherwise return <code>null</code>.
     * This method checks the persistence store if persistence is enabled,
     * otherwise just uses the functionality from ManagerBase.
     *
     * @param id The session id for the session to be returned
     *
     * @exception IllegalStateException if a new session cannot be
     *  instantiated for any reason
     * @exception IOException if an input/output error occurs while
     *  processing this request
     */
    @Override
    public Session findSession(String id) throws IOException {

        Session session = super.findSession(id);
        // OK, at this point, we're not sure if another thread is trying to
        // remove the session or not so the only way around this is to lock it
        // (or attempt to) and then try to get it by this session id again. If
        // the other code ran swapOut, then we should get a null back during
        // this run, and if not, we lock it out so we can access the session
        // safely.
        if(session != null) {
            synchronized(session){
                session = super.findSession(session.getIdInternal());
                if(session != null){
                   // To keep any external calling code from messing up the
                   // concurrency.
                   session.access();
                   session.endAccess();
                }
            }
        }
        if (session != null)
            return (session);

        // See if the Session is in the Store
        session = swapIn(id);
        return (session);

    }

    /**
     * Remove this Session from the active Sessions for this Manager,
     * but not from the Store. (Used by the PersistentValve)
     *
     * @param session Session to be removed
     */
    public void removeSuper(Session session) {
        super.remove (session);
    }

    /**
     * Load all sessions found in the persistence mechanism, assuming
     * they are marked as valid and have not passed their expiration
     * limit. If persistence is not supported, this method returns
     * without doing anything.
     * <p>
     * Note that by default, this method is not called by the MiddleManager
     * class. In order to use it, a subclass must specifically call it,
     * for example in the start() and/or processPersistenceChecks() methods.
     */
    @Override
    public void load() {

        // Initialize our internal data structures
        sessions.clear();

        if (store == null)
            return;

        String[] ids = null;
        try {
            if (SecurityUtil.isPackageProtectionEnabled()){
                try{
                    ids = AccessController.doPrivileged(
                            new PrivilegedStoreKeys());
                }catch(PrivilegedActionException ex){
                    Exception exception = ex.getException();
                    log.error("Exception in the Store during load: "
                              + exception);
                    exception.printStackTrace();                        
                }
            } else {
                ids = store.keys();
            }
        } catch (IOException e) {
            log.error("Can't load sessions from store, " + e.getMessage(), e);
            return;
        }

        int n = ids.length;
        if (n == 0)
            return;

        if (log.isDebugEnabled())
            log.debug(sm.getString("persistentManager.loading", String.valueOf(n)));

        for (int i = 0; i < n; i++)
            try {
                swapIn(ids[i]);
            } catch (IOException e) {
                log.error("Failed load session from store, " + e.getMessage(), e);
            }

    }


    /**
     * Remove this Session from the active Sessions for this Manager,
     * and from the Store.
     *
     * @param session Session to be removed
     */
    @Override
    public void remove(Session session) {

        super.remove (session);

        if (store != null){
            removeSession(session.getIdInternal());
        }
    }

    
    /**
     * Remove this Session from the active Sessions for this Manager,
     * and from the Store.
     *
     * @param id Session's id to be removed
     */    
    protected void removeSession(String id){
        try {
            if (SecurityUtil.isPackageProtectionEnabled()){
                try{
                    AccessController.doPrivileged(new PrivilegedStoreRemove(id));
                }catch(PrivilegedActionException ex){
                    Exception exception = ex.getException();
                    log.error("Exception in the Store during removeSession: "
                              + exception);
                    exception.printStackTrace();                        
                }
            } else {
                 store.remove(id);
            }               
        } catch (IOException e) {
            log.error("Exception removing session  " + e.getMessage());
            e.printStackTrace();
        }        
    }

    /**
     * Save all currently active sessions in the appropriate persistence
     * mechanism, if any.  If persistence is not supported, this method
     * returns without doing anything.
     * <p>
     * Note that by default, this method is not called by the MiddleManager
     * class. In order to use it, a subclass must specifically call it,
     * for example in the stop() and/or processPersistenceChecks() methods.
     */
    @Override
    public void unload() {

        if (store == null)
            return;

        Session sessions[] = findSessions();
        int n = sessions.length;
        if (n == 0)
            return;

        if (log.isDebugEnabled())
            log.debug(sm.getString("persistentManager.unloading",
                             String.valueOf(n)));

        for (int i = 0; i < n; i++)
            try {
                swapOut(sessions[i]);
            } catch (IOException e) {
                // This is logged in writeSession()
            }

    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Look for a session in the Store and, if found, restore
     * it in the Manager's list of active sessions if appropriate.
     * The session will be removed from the Store after swapping
     * in, but will not be added to the active session list if it
     * is invalid or past its expiration.
     */
    protected Session swapIn(String id) throws IOException {

        if (store == null)
            return null;

        Object swapInLock = null;

        /*
         * The purpose of this sync and these locks is to make sure that a
         * session is only loaded once. It doesn't matter if the lock is removed
         * and then another thread enters this method and tries to load the same
         * session. That thread will re-create a swapIn lock for that session,
         * quickly find that the session is already in sessions, use it and
         * carry on.
         */
        synchronized (this) {
            swapInLock = sessionSwapInLocks.get(id);
            if (swapInLock == null) {
                swapInLock = new Object();
                sessionSwapInLocks.put(id, swapInLock);
            }
        }

        Session session = null;

        synchronized (swapInLock) {
            // First check to see if another thread has loaded the session into
            // the manager
            session = sessions.get(id);

            if (session == null) {
                try {
                    if (SecurityUtil.isPackageProtectionEnabled()){
                        try {
                            session = AccessController.doPrivileged(
                                    new PrivilegedStoreLoad(id));
                        } catch (PrivilegedActionException ex) {
                            Exception e = ex.getException();
                            log.error(sm.getString(
                                    "persistentManager.swapInException", id),
                                    e);
                            if (e instanceof IOException){
                                throw (IOException)e;
                            } else if (e instanceof ClassNotFoundException) {
                                throw (ClassNotFoundException)e;
                            }
                        }
                    } else {
                         session = store.load(id);
                    }
                } catch (ClassNotFoundException e) {
                    String msg = sm.getString(
                            "persistentManager.deserializeError", id);
                    log.error(msg, e);
                    throw new IllegalStateException(msg, e);
                }

                if (session != null && !session.isValid()) {
                    log.error(sm.getString(
                            "persistentManager.swapInInvalid", id));
                    session.expire();
                    removeSession(id);
                    session = null;
                }

                if (session != null) {
                    if(log.isDebugEnabled())
                        log.debug(sm.getString("persistentManager.swapIn", id));

                    session.setManager(this);
                    // make sure the listeners know about it.
                    ((StandardSession)session).tellNew();
                    add(session);
                    ((StandardSession)session).activate();
                    // endAccess() to ensure timeouts happen correctly.
                    // access() to keep access count correct or it will end up
                    // negative
                    session.access();
                    session.endAccess();
                }
            }
        }

        // Make sure the lock is removed
        synchronized (this) {
            sessionSwapInLocks.remove(id);
        }

        return (session);

    }


    /**
     * Remove the session from the Manager's list of active
     * sessions and write it out to the Store. If the session
     * is past its expiration or invalid, this method does
     * nothing.
     *
     * @param session The Session to write out.
     */
    protected void swapOut(Session session) throws IOException {

        if (store == null || !session.isValid()) {
            return;
        }

        ((StandardSession)session).passivate();
        writeSession(session);
        super.remove(session);
        session.recycle();

    }


    /**
     * Write the provided session to the Store without modifying
     * the copy in memory or triggering passivation events. Does
     * nothing if the session is invalid or past its expiration.
     */
    protected void writeSession(Session session) throws IOException {

        if (store == null || !session.isValid()) {
            return;
        }

        try {
            if (SecurityUtil.isPackageProtectionEnabled()){
                try{
                    AccessController.doPrivileged(new PrivilegedStoreSave(session));
                }catch(PrivilegedActionException ex){
                    Exception exception = ex.getException();
                    log.error("Exception in the Store during writeSession: "
                              + exception);
                    exception.printStackTrace();                        
                }
            } else {
                 store.save(session);
            }   
        } catch (IOException e) {
            log.error(sm.getString
                ("persistentManager.serializeError", session.getIdInternal(), e));
            throw e;
        }

    }


    /**
     * Start this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#startInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    @Override
    protected synchronized void startInternal() throws LifecycleException {

        // Force initialization of the random number generator
        if (log.isDebugEnabled())
            log.debug("Force random number initialization starting");
        generateSessionId();
        if (log.isDebugEnabled())
            log.debug("Force random number initialization completed");

        if (store == null)
            log.error("No Store configured, persistence disabled");
        else if (store instanceof Lifecycle)
            ((Lifecycle)store).start();

        setState(LifecycleState.STARTING);
    }


    /**
     * Stop this component and implement the requirements
     * of {@link org.apache.catalina.util.LifecycleBase#stopInternal()}.
     *
     * @exception LifecycleException if this component detects a fatal error
     *  that prevents this component from being used
     */
    @Override
    protected synchronized void stopInternal() throws LifecycleException {

        if (log.isDebugEnabled())
            log.debug("Stopping");

        setState(LifecycleState.STOPPING);
        
        if (getStore() != null && saveOnRestart) {
            unload();
        } else {
            // Expire all active sessions
            Session sessions[] = findSessions();
            for (int i = 0; i < sessions.length; i++) {
                StandardSession session = (StandardSession) sessions[i];
                if (!session.isValid())
                    continue;
                session.expire();
            }
        }

        if (getStore() != null && getStore() instanceof Lifecycle)
            ((Lifecycle)getStore()).stop();

        // Require a new random number generator if we are restarted
        this.random = null;
    }


    // ------------------------------------------------------ Protected Methods


    /**
     * Swap idle sessions out to Store if they are idle too long.
     */
    protected void processMaxIdleSwaps() {

        if (!getState().isAvailable() || maxIdleSwap < 0)
            return;

        Session sessions[] = findSessions();
        long timeNow = System.currentTimeMillis();

        // Swap out all sessions idle longer than maxIdleSwap
        if (maxIdleSwap >= 0) {
            for (int i = 0; i < sessions.length; i++) {
                StandardSession session = (StandardSession) sessions[i];
                synchronized (session) {
                    if (!session.isValid())
                        continue;
                    int timeIdle = // Truncate, do not round up
                        (int) ((timeNow - session.getThisAccessedTime()) / 1000L);
                    if (timeIdle > maxIdleSwap && timeIdle > minIdleSwap) {
                        if (session.accessCount != null &&
                                session.accessCount.get() > 0) {
                            // Session is currently being accessed - skip it
                            continue;
                        }
                        if (log.isDebugEnabled())
                            log.debug(sm.getString
                                ("persistentManager.swapMaxIdle",
                                 session.getIdInternal(), new Integer(timeIdle)));
                        try {
                            swapOut(session);
                        } catch (IOException e) {
                            // This is logged in writeSession()
                        }
                    }
                }
            }
        }

    }


    /**
     * Swap idle sessions out to Store if too many are active
     */
    protected void processMaxActiveSwaps() {

        if (!getState().isAvailable() || getMaxActiveSessions() < 0)
            return;

        Session sessions[] = findSessions();

        // FIXME: Smarter algorithm (LRU)
        if (getMaxActiveSessions() >= sessions.length)
            return;

        if(log.isDebugEnabled())
            log.debug(sm.getString
                ("persistentManager.tooManyActive",
                 new Integer(sessions.length)));

        int toswap = sessions.length - getMaxActiveSessions();
        long timeNow = System.currentTimeMillis();

        for (int i = 0; i < sessions.length && toswap > 0; i++) {
            StandardSession session =  (StandardSession) sessions[i];
            synchronized (session) {
                int timeIdle = // Truncate, do not round up
                    (int) ((timeNow - session.getThisAccessedTime()) / 1000L);
                if (timeIdle > minIdleSwap) {
                    if (session.accessCount != null &&
                            session.accessCount.get() > 0) {
                        // Session is currently being accessed - skip it
                        continue;
                    }
                    if(log.isDebugEnabled())
                        log.debug(sm.getString
                            ("persistentManager.swapTooManyActive",
                             session.getIdInternal(), new Integer(timeIdle)));
                    try {
                        swapOut(session);
                    } catch (IOException e) {
                        // This is logged in writeSession()
                    }
                    toswap--;
                }
            }
        }

    }


    /**
     * Back up idle sessions.
     */
    protected void processMaxIdleBackups() {

        if (!getState().isAvailable() || maxIdleBackup < 0)
            return;

        Session sessions[] = findSessions();
        long timeNow = System.currentTimeMillis();

        // Back up all sessions idle longer than maxIdleBackup
        if (maxIdleBackup >= 0) {
            for (int i = 0; i < sessions.length; i++) {
                StandardSession session = (StandardSession) sessions[i];
                synchronized (session) {
                    if (!session.isValid())
                        continue;
                    int timeIdle = // Truncate, do not round up
                        (int) ((timeNow - session.getThisAccessedTime()) / 1000L);
                    if (timeIdle > maxIdleBackup) {
                        if (log.isDebugEnabled())
                            log.debug(sm.getString
                                ("persistentManager.backupMaxIdle",
                                session.getIdInternal(), new Integer(timeIdle)));
    
                        try {
                            writeSession(session);
                        } catch (IOException e) {
                            // This is logged in writeSession()
                        }
                    }
                }
            }
        }

    }

}
