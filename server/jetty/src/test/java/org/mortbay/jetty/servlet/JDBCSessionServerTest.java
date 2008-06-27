// ========================================================================
// Copyright 2008 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at 
// http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// ========================================================================

package org.mortbay.jetty.servlet;

import junit.framework.TestCase;

public class JDBCSessionServerTest extends TestCase
{
    JDBCSessionServer _serverA;
    JDBCSessionServer _serverB;
    
    
    public class JDBCSessionServer extends SessionTestServer
    {
        public JDBCSessionServer (int port, String workerName)
        {
            super(port, workerName);
        }

        public void configureEnvironment()
        {
           System.setProperty("derby.system.home", System.getProperty("java.io.tmpdir"));
        }

        public void configureIdManager()
        {
           JDBCSessionIdManager idMgr = new JDBCSessionIdManager(this);
           idMgr.setWorkerName(_workerName);
           idMgr.setDriverInfo("org.apache.derby.jdbc.EmbeddedDriver", "jdbc:derby:stest;create=true");
           _sessionIdMgr = idMgr;
        }

        public void configureSessionManager1()
        {
           JDBCSessionManager mgr1 = new JDBCSessionManager();
           _sessionMgr1 = mgr1;
        }

        public void configureSessionManager2()
        {
            JDBCSessionManager mgr2 = new JDBCSessionManager();
            _sessionMgr2 = mgr2;
        }
    }
    
    public void setUp () throws Exception
    {
        _serverA = new JDBCSessionServer (8010, "duke");
        _serverA.start();
        _serverB = new JDBCSessionServer (8011, "daisy");
        _serverB.start();
    }
    
    public void tearDown () throws Exception
    {
        if (_serverA != null)
            _serverA.stop();
        if (_serverB != null)
            _serverB.stop();
        
        _serverA=null;
        _serverB=null;
    }
    
    public void testSessions () throws Exception
    {
        SessionTestClient client1 = new SessionTestClient("http://localhost:8010");
        SessionTestClient client2 = new SessionTestClient("http://localhost:8011");
        // confirm that user has no session
        assertFalse(client1.send("/contextA", null));
        String cookieA = client1.newSession("/contextA");
        assertNotNull(cookieA);
        System.err.println("cookieA: " + cookieA);
        
        // confirm that client2 has the same session attributes as client1
        assertTrue(client1.setAttribute("/contextA", cookieA, "foo", "bar"));        
        assertTrue(client2.hasAttribute("/contextA", cookieA, "foo", "bar"));
        
        /* Forward from contextA to contextB */
        
        // confirm that cookieA would not work on /contextB
        assertFalse(client1.send("/contextA/dispatch/forward/contextB/session", cookieA));        
        assertFalse(client2.send("/contextA/dispatch/forward/contextB/session", cookieA));
        
        /* contextB */
        
        // confirm that cookieA would not work on /contextB
        assertFalse(client1.send("/contextB", cookieA));        
        assertFalse(client1.hasAttribute("/contextB", cookieA, "foo", "bar"));        
        assertFalse(client2.hasAttribute("/contextB", cookieA, "foo", "bar"));
        
        String cookieB = client2.newSession("/contextB");
        assertNotNull(cookieB);
        System.err.println("cookieB: " + cookieB);
        
        // confirm that client1 has same session attributes as client2
        assertTrue(client2.setAttribute("/contextB", cookieB, "hello", "world"));
        assertTrue(client1.hasAttribute("/contextB", cookieB, "hello", "world"));
                
        // confirm that cookieB would not work on /contextA
        assertFalse(client1.hasAttribute("/contextA", cookieB, "hello", "world"));
        assertFalse(client2.hasAttribute("/contextA", cookieB, "hello", "world"));
    }
}
