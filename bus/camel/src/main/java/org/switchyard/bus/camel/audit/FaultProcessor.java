/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
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
package org.switchyard.bus.camel.audit;

import java.util.Map;
import java.util.Map.Entry;

import org.apache.camel.AsyncCallback;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.processor.DelegateAsyncProcessor;
import org.apache.camel.util.ExchangeHelper;
import org.apache.log4j.Logger;
import org.switchyard.ExchangeState;
import org.switchyard.HandlerException;
import org.switchyard.bus.camel.CamelHelper;
import org.switchyard.bus.camel.ErrorListener;

/**
 * Processor which catches {@link HandlerException} before calling processor.
 * 
 * If an exception occurs it's caught by this processor except exceptions reported
 * during handling FAULT exchanges. These errors are handled in HandlerProcessor.
 */
public class FaultProcessor extends DelegateAsyncProcessor {

    private Logger _logger = Logger.getLogger(FaultProcessor.class);

    /**
     * Creates new fault processor.
     * 
     * @param target Wrapped processor.
     */
    public FaultProcessor(Processor target) {
        super(target);
    }

    @Override
    public boolean process(final Exchange exchange, final AsyncCallback callback) {
        return super.process(exchange, new AsyncCallback() {
            @Override
            public void done(boolean doneSync) {
                if (doneSync) { // verify exchange only if processing is done
                    org.switchyard.Exchange exc = CamelHelper.getSwitchYardExchange(exchange);
                    if (exchange.getException() != null) {
                        handle(exchange.getException(), exchange, exc);
                    }
                }
                callback.done(doneSync);
            }
        });
    }

    /**
     * Method which tries to send FAULT if there is exception reported by processor
     * and exchange state is still OK.
     * 
     * @param throwable Exception thrown by target processor.
     * @param camel Camel exchange. 
     * @param exchange SwitchYard exchange related to exception.
     */
    protected void handle(Throwable throwable, Exchange camel, org.switchyard.Exchange exchange) {
        if (ExchangeState.OK == exchange.getState()) {
            notifyListeners(camel.getContext(), exchange, throwable);
            Throwable content = detectHandlerException(throwable);
            exchange.sendFault(exchange.createMessage().setContent(content));
        } else {
            // exception thrown during handling FAULT state cannot be forwarded
            // anywhere, because we already have problem to handle
            _logger.error("Unexpected exception thrown during handling FAULT response. "
                + "This exception can not be handled, thus it's marked as handled and only logged. "
                + "If you don't want see messages like this consider handling "
                + "exceptions in your handler logic", throwable);
            ExchangeHelper.setFailureHandled(camel);
        }
    }

    protected void notifyListeners(CamelContext context, org.switchyard.Exchange exchange, Throwable exception) {
        Map<String, ErrorListener> listeners = context.getRegistry().lookupByType(ErrorListener.class);
        if (listeners != null && listeners.size() > 0) {
            for (Entry<String, ErrorListener> entry : listeners.entrySet()) {
                try {
                    entry.getValue().notify(exchange, exception);
                } catch (Exception e) {
                    _logger.error("Error listener " + entry.getKey() + " failed to handle exception " + exception.getClass());
                }
            }
        }
    }

    private Throwable detectHandlerException(Throwable throwable) {
        if (throwable instanceof HandlerException) {
            return (HandlerException) throwable;
        }
        return new HandlerException(throwable);
    }

    @Override
    public String toString() {
        return "FaultProcessor [" + getProcessor() + "]";
    }
}
