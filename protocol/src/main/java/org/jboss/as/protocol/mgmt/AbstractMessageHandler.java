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

package org.jboss.as.protocol.mgmt;

import java.io.DataInput;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.jboss.as.protocol.StreamUtils;
import org.jboss.as.protocol.logging.ProtocolLogger;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.CloseHandler;
import org.jboss.remoting3.MessageOutputStream;
import org.jboss.threads.AsyncFuture;
import org.xnio.Cancellable;

/**
 * Utility class for request/response handling
 *
 * @author Emanuel Muckenhuber
 */
public abstract class AbstractMessageHandler extends ActiveOperationSupport implements ManagementMessageHandler, CloseHandler<Channel> {

    private final ExecutorService executorService;
    private final AtomicInteger requestID = new AtomicInteger();
    private final Map<Integer, ActiveRequest<?, ?>> requests = new ConcurrentHashMap<Integer, ActiveRequest<?, ?>>(16, 0.75f, Runtime.getRuntime().availableProcessors());

    protected AbstractMessageHandler(final ExecutorService executorService) {
        if(executorService == null) {
            throw ProtocolLogger.ROOT_LOGGER.nullExecutor();
        }
        this.executorService = executorService;
    }

    /**
     * Get the executor
     *
     * @return the executor
     */
    protected ExecutorService getExecutor() {
        return executorService;
    }

    /**
     * Get the request handler.
     *
     * @param header the request header
     * @return the request handler
     */
    protected ManagementRequestHandler<?, ?> getRequestHandler(final ManagementRequestHeader header) {
        return getFallbackHandler(header);
    }

    /**
     * Validate whether the request can be handled.
     *
     * @param header the protocol header
     * @return the management request header
     * @throws IOException
     */
    protected ManagementRequestHeader validateRequest(final ManagementProtocolHeader header) throws IOException {
        return (ManagementRequestHeader) header;
    }

    /**
     * Handle a message.
     *
     * @param channel the channel
     * @param input the message
     * @param header the management protocol header
     * @throws IOException
     */
    public void handleMessage(final Channel channel, final DataInput input, final ManagementProtocolHeader header) throws IOException {
        final byte type = header.getType();
        if(type == ManagementProtocol.TYPE_RESPONSE) {
            // Handle response to local requests
            final ManagementResponseHeader response =  (ManagementResponseHeader) header;
            final ActiveRequest<?, ?> request = requests.remove(response.getResponseId());
            if(request == null) {
                ProtocolLogger.CONNECTION_LOGGER.noSuchRequest(response.getResponseId(), channel);
                safeWriteErrorResponse(channel, header, ProtocolLogger.ROOT_LOGGER.responseHandlerNotFound(response.getResponseId()));
            } else if(response.getError() != null) {
                request.handleFailed(response);
            } else {
                handleRequest(channel, input, header, request);
            }
        } else {
            // Handle requests (or other messages)
            try {
                final ManagementRequestHeader requestHeader = validateRequest(header);
                final ManagementRequestHandler<?, ?> handler = getRequestHandler(requestHeader);
                if(handler == null) {
                    safeWriteErrorResponse(channel, header, ProtocolLogger.ROOT_LOGGER.responseHandlerNotFound(requestHeader.getBatchId()));
                } else {
                    handleMessage(channel, input, requestHeader, handler);
                }
            } catch (Exception e) {
                safeWriteErrorResponse(channel, header, e);
            }
        }
    }

    /**
     * Execute a request.
     *
     * @param request the request
     * @param channel the channel
     * @param support the request support
     * @return the future result
     */
    protected <T, A> AsyncFuture<T> executeRequest(final ManagementRequest<T, A> request, final Channel channel, final ActiveOperation<T, A> support) {
        assert support != null;
        updateChannelRef(support, channel);
        final Integer requestId = this.requestID.incrementAndGet();
        final ActiveRequest<T, A> ar = new ActiveRequest<T, A>(support, request, channel);
        requests.put(requestId, ar);
        final ManagementRequestHeader header = new ManagementRequestHeader(ManagementProtocol.VERSION, requestId, support.getOperationId(), request.getOperationType());
        final ActiveOperation.ResultHandler<T> resultHandler = support.getResultHandler();
        try {
            request.sendRequest(resultHandler, new ManagementRequestContext<A>() {

                @Override
                public Integer getOperationId() {
                    return support.getOperationId();
                }

                @Override
                public A getAttachment() {
                    return support.getAttachment();
                }

                @Override
                public Channel getChannel() {
                    return channel;
                }

                @Override
                public ManagementProtocolHeader getRequestHeader() {
                    return header;
                }

                Runnable createAsyncTaskRunner(final AsyncTask<A> task, final boolean cancellable) {
                    final ManagementRequestContext<A> context = this;
                    final AsyncTaskRunner runner = new AsyncTaskRunner(cancellable) {
                        @Override
                        protected void doExecute() {
                            try {
                                task.execute(context);
                            } catch (Exception e) {
                                resultHandler.failed(e);
                                requests.remove(requestId);
                                if (e instanceof InterruptedException) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                    };
                    if (cancellable) {
                        support.addCancellable(runner);
                    }
                    return runner;
                }

                @Override
                public void executeAsync(final AsyncTask<A> task) {
                    executeAsync(task, true, getExecutor());
                }

                @Override
                public void executeAsync(final AsyncTask<A> task, boolean cancellable) {
                    executeAsync(task, cancellable, getExecutor());
                }

                @Override
                public void executeAsync(AsyncTask<A> task, Executor executor) {
                    executeAsync(task, true, executor);
                }

                @Override
                public void executeAsync(AsyncTask<A> task, boolean cancellable, Executor executor) {
                    executor.execute(createAsyncTaskRunner(task, cancellable));
                }

                @Override
                public FlushableDataOutput writeMessage(final ManagementProtocolHeader header) throws IOException {
                    final MessageOutputStream os = channel.writeMessage();
                    return writeHeader(header, os);
                }
            });

        } catch (Exception e) {
            resultHandler.failed(e);
            requests.remove(requestId);
        }
        return support.getResult();
    }

    /**
     * Handle a message.
     *
     * @param channel the channel
     * @param message the message
     * @param header the protocol header
     * @param activeRequest the active request
     */
    protected <T, A> void handleRequest(final Channel channel, final DataInput message, final ManagementProtocolHeader header, ActiveRequest<T, A> activeRequest) {
        handleMessage(channel, message, header, activeRequest.context, activeRequest.handler);
    }

    /**
     * Handle a message.
     *
     * @param channel the channel
     * @param message the message
     * @param header the protocol header
     * @param handler the request handler
     * @throws IOException
     */
    protected <T, A> void handleMessage(final Channel channel, final DataInput message, final ManagementRequestHeader header, ManagementRequestHandler<T, A> handler) throws IOException {
        final ActiveOperation<T, A> support = getActiveOperation(header);
        if(support == null) {
            throw ProtocolLogger.ROOT_LOGGER.responseHandlerNotFound(header.getBatchId());
        }
        handleMessage(channel, message, header, support, handler);
    }

    /**
     * Handle a message.
     *
     * @param channel the channel
     * @param message the message
     * @param header the protocol header
     * @param support the request support
     * @param handler the request handler
     */
    protected <T, A> void handleMessage(final Channel channel, final DataInput message, final ManagementProtocolHeader header,
                                 final ActiveOperation<T, A> support, final ManagementRequestHandler<T, A> handler) {
        assert support != null;
        updateChannelRef(support, channel);
        final ActiveOperation.ResultHandler<T> resultHandler = support.getResultHandler();
        try {
            handler.handleRequest(message, resultHandler, new ManagementRequestContext<A>() {

                @Override
                public Integer getOperationId() {
                    return support.getOperationId();
                }

                @Override
                public A getAttachment() {
                    return support.getAttachment();
                }

                @Override
                public Channel getChannel() {
                    return channel;
                }

                @Override
                public ManagementProtocolHeader getRequestHeader() {
                    return header;
                }

                Runnable createAsyncTaskRunner(final AsyncTask<A> task, final boolean cancellable) {
                    final ManagementRequestContext<A> context = this;
                    final AsyncTaskRunner runner = new AsyncTaskRunner(cancellable) {
                        @Override
                        protected void doExecute() {
                            try {
                                task.execute(context);
                            } catch (Exception e) {
                                ProtocolLogger.ROOT_LOGGER.debugf(e, " failed to process async request for %s on channel %s", task, channel);
                                if(resultHandler.failed(e)) {
                                    safeWriteErrorResponse(channel, header, e);
                                }
                            }
                        }
                    };
                    if (cancellable) {
                        support.addCancellable(runner);
                    }
                    return runner;
                }

                @Override
                public void executeAsync(final AsyncTask<A> task) {
                    executeAsync(task, true, getExecutor());
                }

                @Override
                public void executeAsync(final AsyncTask<A> task, boolean cancellable) {
                    executeAsync(task, cancellable, getExecutor());
                }

                @Override
                public void executeAsync(AsyncTask<A> task, Executor executor) {
                    executeAsync(task, true, executor);
                }

                @Override
                public void executeAsync(final AsyncTask<A> task, boolean cancellable, final Executor executor) {
                    try {
                        executor.execute(createAsyncTaskRunner(task, cancellable));
                    } catch (RejectedExecutionException e) {
                        if(resultHandler.failed(e)) {
                            safeWriteErrorResponse(channel, header, e);
                        }
                    }
                }

                @Override
                public FlushableDataOutput writeMessage(final ManagementProtocolHeader header) throws IOException {
                    final MessageOutputStream os = channel.writeMessage();
                    return writeHeader(header, os);
                }

            });
        } catch (Exception e) {
            resultHandler.failed(e);
            safeWriteErrorResponse(channel, header, e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shutdown() {
        super.shutdown();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shutdownNow() {
        shutdown();
        cancelAllActiveOperations();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean awaitCompletion(long timeout, TimeUnit unit) throws InterruptedException {
        return super.awaitCompletion(timeout, unit);
    }

    @Override
    public void handleClose(final Channel closed, final IOException exception) {
        handleChannelClosed(closed, exception);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected <T, A> ActiveOperation<T, A> removeActiveOperation(Integer id) {
        final ActiveOperation<T, A> removed = super.removeActiveOperation(id);
        if(removed != null) {
            for(final Map.Entry<Integer, ActiveRequest<?, ?>> requestEntry : requests.entrySet()) {
                final ActiveRequest<?, ?> request = requestEntry.getValue();
                if(request.context == removed) {
                    requests.remove(requestEntry.getKey());
                }
            }
        }
        return removed;
    }

    /**
     * Safe write error response.
     *
     * @param channel the channel
     * @param header the request header
     * @param error the exception
     */
    protected static void safeWriteErrorResponse(final Channel channel, final ManagementProtocolHeader header, final Exception error) {
        if(header.getType() == ManagementProtocol.TYPE_REQUEST) {
            try {
                writeErrorResponse(channel, (ManagementRequestHeader) header, error);
            } catch(IOException ioe) {
                ProtocolLogger.ROOT_LOGGER.tracef(ioe, "failed to write error response for %s on channel: %s", header, channel);
            }
        }
    }

    /**
     * Write an error response.
     *
     * @param channel the channel
     * @param header the request
     * @param error the error
     * @throws IOException
     */
    protected static void writeErrorResponse(final Channel channel, final ManagementRequestHeader header, final Exception error) throws IOException {
        final ManagementResponseHeader response = ManagementResponseHeader.create(header, error);
        final MessageOutputStream output = channel.writeMessage();
        try {
            writeHeader(response, output);
            output.close();
        } finally {
            StreamUtils.safeClose(output);
        }
    }

    /**
     * Write the management protocol header.
     *
     * @param header the mgmt protocol header
     * @param os the output stream
     * @throws IOException
     */
    protected static FlushableDataOutput writeHeader(final ManagementProtocolHeader header, final OutputStream os) throws IOException {
        final FlushableDataOutput output = FlushableDataOutputImpl.create(os);
        header.write(output);
        return output;
    }

    /**
     * Get a fallback handler.
     *
     * @param header the protocol header
     * @return the fallback handler
     */
    protected <T, A> ManagementRequestHandler<T, A> getFallbackHandler(final ManagementRequestHeader header) {
        return new ManagementRequestHandler<T, A>() {
            @Override
            public void handleRequest(final DataInput input, ActiveOperation.ResultHandler<T> resultHandler, ManagementRequestContext<A> context) throws IOException {
                final Exception error = ProtocolLogger.ROOT_LOGGER.noSuchResponseHandler(Integer.toHexString(header.getRequestId()));
                if(resultHandler.failed(error)) {
                    safeWriteErrorResponse(context.getChannel(), context.getRequestHeader(), error);
                }
            }
        };
    }

    private static class ActiveRequest<T, A> {

        private final Channel channel;
        private final ActiveOperation<T, A> context;
        private final ManagementResponseHandler<T, A> handler;

        ActiveRequest(ActiveOperation<T, A> context, ManagementResponseHandler<T, A> handler, Channel channel) {
            this.context = context;
            this.handler = handler;
            this.channel = channel;
        }

        protected void handleFailed(final ManagementResponseHeader header) {
            handler.handleFailed(header, context.getResultHandler());
        }

    }

    private abstract static class AsyncTaskRunner implements Runnable, Cancellable {

        private final boolean cancellable;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile Thread thread;

        private AsyncTaskRunner(boolean cancellable) {
            this.cancellable = cancellable;
        }
        @Override
        public Cancellable cancel() {
            if (cancellable && cancelled.compareAndSet(false, true)) {
                final Thread thread = this.thread;
                if(thread != null) {
                    thread.interrupt();
                    ProtocolLogger.ROOT_LOGGER.cancelledAsyncTask(getClass().getSimpleName(), thread);
                }
            }
            return this;
        }

        /**
         * Execute...
         */
        protected abstract void doExecute();

        @Override
        public void run() {
            if (cancellable && cancelled.get()) {
                Thread.currentThread().interrupt();
                ProtocolLogger.ROOT_LOGGER.cancelledAsyncTaskBeforeRun(getClass().getSimpleName());
            }
            this.thread = Thread.currentThread();
            try {
                doExecute();
            } finally {
                this.thread = null;
            }
        }

        final boolean isCancelled() {
            return cancelled.get();
        }
    }

}
