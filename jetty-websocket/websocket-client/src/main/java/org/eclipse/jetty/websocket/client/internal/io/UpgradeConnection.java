//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.client.internal.io;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.websocket.api.Extension;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.UpgradeResponse;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.client.internal.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.internal.ClientUpgradeResponse;
import org.eclipse.jetty.websocket.client.internal.IWebSocketClient;
import org.eclipse.jetty.websocket.driver.WebSocketEventDriver;
import org.eclipse.jetty.websocket.io.IncomingFrames;
import org.eclipse.jetty.websocket.io.OutgoingFrames;
import org.eclipse.jetty.websocket.io.WebSocketSession;
import org.eclipse.jetty.websocket.protocol.AcceptHash;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;

/**
 * This is the initial connection handling that exists immediately after physical connection is established to destination server.
 * <p>
 * Eventually, upon successful Upgrade request/response, this connection swaps itself out for the WebSocektClientConnection handler.
 */
public class UpgradeConnection extends AbstractConnection
{
    public class SendUpgradeRequest extends FutureCallback<String> implements Runnable
    {
        @Override
        public void completed(String context)
        {
            // Writing the request header is complete.
            super.completed(context);
            // start the interest in fill
            fillInterested();
        }

        @Override
        public void run()
        {
            URI uri = client.getWebSocketUri();
            String rawRequest = request.generate(uri);

            ByteBuffer buf = BufferUtil.toBuffer(rawRequest,StringUtil.__UTF8_CHARSET);
            getEndPoint().write("REQ",this,buf);
        }
    }

    private static final Logger LOG = Log.getLogger(UpgradeConnection.class);
    private final ByteBufferPool bufferPool;
    private final Scheduler scheduler;
    private final IWebSocketClient client;
    private final HttpResponseHeaderParser parser;
    private ClientUpgradeRequest request;

    public UpgradeConnection(EndPoint endp, Executor executor, IWebSocketClient client)
    {
        super(endp,executor);
        this.client = client;
        this.bufferPool = client.getFactory().getBufferPool();
        this.scheduler = client.getFactory().getScheduler();
        this.parser = new HttpResponseHeaderParser();

        try
        {
            this.request = (ClientUpgradeRequest)client.getUpgradeRequest();
        }
        catch (ClassCastException e)
        {
            client.failed(null,new RuntimeException("Invalid Upgrade Request structure",e));
        }
    }

    public void disconnect(boolean onlyOutput)
    {
        EndPoint endPoint = getEndPoint();
        // We need to gently close first, to allow
        // SSL close alerts to be sent by Jetty
        LOG.debug("Shutting down output {}",endPoint);
        endPoint.shutdownOutput();
        if (!onlyOutput)
        {
            LOG.debug("Closing {}",endPoint);
            endPoint.close();
        }
    }

    private void notifyConnect()
    {
        client.completed(client.getUpgradeResponse());
    }

    @Override
    public void onFillable()
    {
        ByteBuffer buffer = bufferPool.acquire(getInputBufferSize(),false);
        BufferUtil.clear(buffer);
        boolean readMore = false;
        try
        {
            readMore = read(buffer);
        }
        finally
        {
            bufferPool.release(buffer);
        }

        if (readMore)
        {
            fillInterested();
        }
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        // TODO: handle timeout
        getExecutor().execute(new SendUpgradeRequest());
    }

    /**
     * Read / Parse the waiting read/fill buffer
     * 
     * @param buffer
     *            the buffer to fill into from the endpoint
     * @return true if there is more to read, false if reading should stop
     */
    private boolean read(ByteBuffer buffer)
    {
        EndPoint endPoint = getEndPoint();
        try
        {
            while (true)
            {
                int filled = endPoint.fill(buffer);
                if (filled == 0)
                {
                    return true;
                }
                else if (filled < 0)
                {
                    LOG.debug("read - EOF Reached");
                    return false;
                }
                else
                {
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug("Filled {} bytes - {}",filled,BufferUtil.toDetailString(buffer));
                    }
                    ClientUpgradeResponse resp = parser.parse(buffer);
                    if (resp != null)
                    {
                        // Got a response!
                        client.setUpgradeResponse(resp);
                        validateResponse(resp);
                        notifyConnect();
                        upgradeConnection(resp);
                        return false; // do no more reading
                    }
                }
            }
        }
        catch (IOException e)
        {
            LOG.warn(e);
            client.failed(null,e);
            disconnect(false);
            return false;
        }
        catch (UpgradeException e)
        {
            LOG.warn(e);
            client.failed(null,e);
            disconnect(false);
            return false;
        }
    }

    private void upgradeConnection(ClientUpgradeResponse response)
    {
        EndPoint endp = getEndPoint();
        Executor executor = getExecutor();
        WebSocketClientConnection connection = new WebSocketClientConnection(endp,executor,client);

        // Initialize / Negotiate Extensions
        WebSocketEventDriver websocket = client.getWebSocket();
        WebSocketPolicy policy = client.getPolicy();
        String acceptedSubProtocol = response.getAcceptedSubProtocol();
        WebSocketSession session = new WebSocketSession(websocket,connection,policy,acceptedSubProtocol);
        connection.setSession(session);
        List<Extension> extensions = client.getFactory().initExtensions(response.getExtensions());

        // Start with default routing.
        IncomingFrames incoming = session;
        OutgoingFrames outgoing = connection;

        // Connect extensions
        if (extensions != null)
        {
            Iterator<Extension> extIter;
            // Connect outgoings
            extIter = extensions.iterator();
            while (extIter.hasNext())
            {
                Extension ext = extIter.next();
                ext.setNextOutgoingFrames(outgoing);
                outgoing = ext;

                // Handle RSV reservations
                if (ext.useRsv1())
                {
                    connection.getGenerator().setRsv1InUse(true);
                    connection.getParser().setRsv1InUse(true);
                }
                if (ext.useRsv2())
                {
                    connection.getGenerator().setRsv2InUse(true);
                    connection.getParser().setRsv2InUse(true);
                }
                if (ext.useRsv3())
                {
                    connection.getGenerator().setRsv3InUse(true);
                    connection.getParser().setRsv3InUse(true);
                }
            }

            // Connect incomings
            Collections.reverse(extensions);
            extIter = extensions.iterator();
            while (extIter.hasNext())
            {
                Extension ext = extIter.next();
                ext.setNextIncomingFrames(incoming);
                incoming = ext;
            }
        }

        // configure session for outgoing flows
        session.setOutgoing(outgoing);
        // configure connection for incoming flows
        connection.getParser().setIncomingFramesHandler(incoming);

        // Now swap out the connection
        endp.setConnection(connection);
    }

    private void validateResponse(UpgradeResponse response)
    {
        // Check the Accept hash
        String reqKey = request.getKey();
        String expectedHash = AcceptHash.hashKey(reqKey);
        response.validateWebSocketHash(expectedHash);

        // Parse extensions
        List<ExtensionConfig> extensions = new ArrayList<>();
        Iterator<String> extIter = response.getHeaderValues("Sec-WebSocket-Extensions");
        while (extIter.hasNext())
        {
            String extVal = extIter.next();
            QuotedStringTokenizer tok = new QuotedStringTokenizer(extVal,",");
            while (tok.hasMoreTokens())
            {
                extensions.add(ExtensionConfig.parse(tok.nextToken()));
            }
        }
        response.setExtensions(extensions);
    }
}
