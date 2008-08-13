//========================================================================
//Copyright 2007 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at 
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

package org.mortbay.cometd;
import java.io.IOException;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.mortbay.cometd.AbstractBayeux;
import org.mortbay.cometd.AbstractCometdServlet;
import org.mortbay.cometd.ClientImpl;
import org.mortbay.cometd.MessageImpl;
import org.mortbay.cometd.Transport;
import org.mortbay.util.ArrayQueue;

import org.cometd.Extension;
import org.cometd.Message;

public class SuspendingCometdServlet extends AbstractCometdServlet
{
    /* ------------------------------------------------------------ */
    protected AbstractBayeux newBayeux()
    {
        return new SuspendingBayeux();
    }

    /* ------------------------------------------------------------ */
    public void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        // Look for an existing client and protect from context restarts
        SuspendingClient client=null;
        Transport transport=null;
        boolean connect=false;
        int num_msgs=-1;
        
        // Have we seen this request before
        boolean initial=false;
        if (request.isInitial() || request.getAttribute(CLIENT_ATTR)==null)
        {
            initial=true;
            
            Message[] messages = getMessages(request);
            num_msgs=messages.length;

            /* check jsonp parameter */
            String jsonpParam=request.getParameter("jsonp");

            // Handle all messages
            boolean response_pending=false;
            try
            {
                for (Message message : messages)
                {
                    if (jsonpParam!=null)
                        message.put("jsonp",jsonpParam);

                    if (client==null)
                    {   
                        client=(SuspendingClient)_bayeux.getClient((String)message.get(AbstractBayeux.CLIENT_FIELD));

                        // If no client,  SHOULD be a handshake, so force a transport and handle
                        if (client==null)
                        {
                            // Setup a browser ID
                            String browser_id=browserId(request);
                            if (browser_id==null)
                                browser_id=newBrowserId(request,response);

                            if (transport==null)
                            {
                                transport=_bayeux.newTransport(client,message);
                                transport.setResponse(response);
                            }
                            _bayeux.handle(null,transport,message);
                            message=null;

                            continue;
                        }
                        else
                        {
                            String browser_id=browserId(request);
                            if (browser_id!=null && (client.getBrowserId()==null || !client.getBrowserId().equals(browser_id)))
                                client.setBrowserId(browser_id);

                            // resolve transport
                            if (transport==null)
                            {
                                transport=_bayeux.newTransport(client,message);
                                transport.setResponse(response);
                            }

                            // Tell client to hold messages as a response is likely to be sent.
                            if (!transport.resumePoll())
                            {
                                response_pending=true;
                                client.responsePending();
                            }
                        }
                    }

                    String channel=_bayeux.handle(client,transport,message);
                    connect|=AbstractBayeux.META_CONNECT.equals(channel);
                }
            }
            finally
            {
                if (response_pending)
                    client.responded();
                
                for (Message message : messages)
                    ((MessageImpl)message).decRef();
            }
        }
        else
        {
            transport=(Transport)request.getAttribute(TRANSPORT_ATTR);
            transport.setResponse(response);
            Object clientObj=request.getAttribute(CLIENT_ATTR); 
            client=(clientObj instanceof ClientImpl)?(SuspendingClient)clientObj:null;
            if (client!=null)
                client.setPollRequest(null);
        }

        // Do we need to wait for messages
        if (transport!=null)
        {
            Message pollReply=transport.getPollReply();
            if (pollReply!=null)
            {
                if (_bayeux.isLogDebug())
                    _bayeux.logDebug("doPost: transport is polling");
                long timeout=client.getTimeout();
                if (timeout==0)
                    timeout=_bayeux.getTimeout();

                if (initial)
                    client.access();

                // Get messages or wait
                synchronized (client)
                {
                    if (!client.hasMessages() && initial && num_msgs<=1)
                    {
                        // save state and suspend
                        client.setPollRequest(request);
                        request.setAttribute(CLIENT_ATTR,client);
                        request.setAttribute(TRANSPORT_ATTR,transport);
                        request.suspend(timeout);
                        return;
                    }
                }

                for (Extension e:_bayeux.getExtensions())
                    pollReply=e.sendMeta(pollReply);
                transport.send(pollReply);                 
            }
            else if (client!=null)
            {
                client.access();
            }
        }


        // Send any messages.
        if (client!=null) 
        { 
            Message message = null; 
            synchronized(client)
            {
                ArrayQueue<Message> messages= (ArrayQueue)client.getQueue();
                int size=messages.size();
                boolean flushed=false;
                try
                {
                    for (int i=0;i<size;i++)
                    {
                        message=messages.getUnsafe(i);
                        transport.send(message); 
                    }

                    transport.complete();
                    response.getWriter().close();
                    flushed=true;
                }
                finally
                {
                    if (flushed)
                        messages.clear();
                }
            }
            
            if (transport.resumePoll())
                client.resume();
        }
        else
            transport.complete();
    }
}
