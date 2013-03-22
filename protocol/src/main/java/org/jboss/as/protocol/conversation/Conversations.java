/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
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

package org.jboss.as.protocol.conversation;

import org.jboss.remoting3.Channel;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;

/**
 * @author Emanuel Muckenhuber
 */
final class Conversations {

    private ExecutorService executorService;
    private final ConcurrentMap<Integer, Conversation> conversations = new ConcurrentHashMap<>(16, 0.75f, Runtime.getRuntime().availableProcessors());

    /**
     * Create a new conversation.
     *
     * @param channel
     * @return
     */
    public Conversation createNewConversation(final Channel channel) {
        final Conversation conversation = new ConversationImpl(executorService) {
            @Override
            Channel getChannel() {
                return channel;
            }
        };
        return conversation;
    }

}
