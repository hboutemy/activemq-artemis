/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.activemq.artemis.protocol.amqp.connect.federation;

import static org.apache.activemq.artemis.protocol.amqp.connect.federation.AMQPFederationConstants.ADDRESS_AUTO_DELETE;
import static org.apache.activemq.artemis.protocol.amqp.connect.federation.AMQPFederationConstants.ADDRESS_AUTO_DELETE_DELAY;
import static org.apache.activemq.artemis.protocol.amqp.connect.federation.AMQPFederationConstants.ADDRESS_AUTO_DELETE_MSG_COUNT;
import static org.apache.activemq.artemis.protocol.amqp.connect.federation.AMQPFederationConstants.FEDERATION_ADDRESS_RECEIVER;
import static org.apache.activemq.artemis.protocol.amqp.connect.federation.AMQPFederationPolicySupport.FEDERATED_ADDRESS_SOURCE_PROPERTIES;
import static org.apache.activemq.artemis.protocol.amqp.connect.federation.AMQPFederation.FEDERATION_INSTANCE_RECORD;
import static org.apache.activemq.artemis.protocol.amqp.proton.AmqpSupport.QUEUE_CAPABILITY;
import static org.apache.activemq.artemis.protocol.amqp.proton.AmqpSupport.TOPIC_CAPABILITY;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import org.apache.activemq.artemis.api.config.ActiveMQDefaultConfiguration;
import org.apache.activemq.artemis.api.core.ActiveMQExceptionType;
import org.apache.activemq.artemis.api.core.ActiveMQSecurityException;
import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.server.AddressQueryResult;
import org.apache.activemq.artemis.core.server.Consumer;
import org.apache.activemq.artemis.core.server.QueueQueryResult;
import org.apache.activemq.artemis.protocol.amqp.broker.AMQPSessionCallback;
import org.apache.activemq.artemis.protocol.amqp.exceptions.ActiveMQAMQPException;
import org.apache.activemq.artemis.protocol.amqp.exceptions.ActiveMQAMQPIllegalStateException;
import org.apache.activemq.artemis.protocol.amqp.exceptions.ActiveMQAMQPInternalErrorException;
import org.apache.activemq.artemis.protocol.amqp.logger.ActiveMQAMQPProtocolMessageBundle;
import org.apache.activemq.artemis.protocol.amqp.proton.AMQPSessionContext;
import org.apache.activemq.artemis.protocol.amqp.proton.AmqpSupport;
import org.apache.activemq.artemis.protocol.amqp.proton.ProtonServerSenderContext;
import org.apache.activemq.artemis.protocol.amqp.proton.SenderController;
import org.apache.activemq.artemis.selector.filter.FilterException;
import org.apache.activemq.artemis.selector.impl.SelectorParser;
import org.apache.qpid.proton.amqp.DescribedType;
import org.apache.qpid.proton.amqp.Symbol;
import org.apache.qpid.proton.amqp.messaging.Source;
import org.apache.qpid.proton.amqp.transport.AmqpError;
import org.apache.qpid.proton.amqp.transport.ReceiverSettleMode;
import org.apache.qpid.proton.engine.Connection;
import org.apache.qpid.proton.engine.Sender;

/**
 * {@link SenderController} used when an AMQP federation Address receiver is created
 * and this side of the connection needs to create a matching sender. The address sender
 * controller must check on initialization if the address exists and if not it should
 * create it using the configuration values supplied in the link source properties that
 * control the lifetime of the address once the link is closed.
 */
public final class AMQPFederationAddressSenderController implements SenderController {

   // Capabilities offered to the attaching federation receiver link that indicate this sender
   // is a federation sender which allows the link open to complete.
   private static final Symbol[] OFFERED_LINK_CAPABILITIES = new Symbol[] {FEDERATION_ADDRESS_RECEIVER};

   private final AMQPSessionContext session;
   private final AMQPSessionCallback sessionSPI;

   public AMQPFederationAddressSenderController(AMQPSessionContext session) {
      this.session = session;
      this.sessionSPI = session.getSessionSPI();
   }

   public AMQPSessionContext getSessionContext() {
      return session;
   }

   public AMQPSessionCallback getSessionCallback() {
      return sessionSPI;
   }

   @SuppressWarnings("unchecked")
   @Override
   public Consumer init(ProtonServerSenderContext senderContext) throws Exception {
      final Sender sender = senderContext.getSender();
      final Source source = (Source) sender.getRemoteSource();
      final String selector;
      final SimpleString queueName = SimpleString.toSimpleString(sender.getName());
      final Connection protonConnection = sender.getSession().getConnection();
      final org.apache.qpid.proton.engine.Record attachments = protonConnection.attachments();

      if (attachments.get(FEDERATION_INSTANCE_RECORD, AMQPFederation.class) == null) {
         throw new ActiveMQAMQPIllegalStateException("Cannot create a federation link from non-federation connection");
      }

      // Match the settlement mode of the remote instead of relying on the default of MIXED.
      sender.setSenderSettleMode(sender.getRemoteSenderSettleMode());
      // We don't currently support SECOND so enforce that the answer is always FIRST
      sender.setReceiverSettleMode(ReceiverSettleMode.FIRST);
      // We need to offer back that we support federation for the remote to complete the attach.
      sender.setOfferedCapabilities(OFFERED_LINK_CAPABILITIES);

      final Map<String, Object> addressSourceProperties;

      if (sender.getRemoteProperties() == null || !sender.getRemoteProperties().containsKey(FEDERATED_ADDRESS_SOURCE_PROPERTIES)) {
         addressSourceProperties = Collections.EMPTY_MAP;
      } else {
         addressSourceProperties = (Map<String, Object>) sender.getRemoteProperties().get(FEDERATED_ADDRESS_SOURCE_PROPERTIES);
      }

      final boolean autoDelete = (boolean) addressSourceProperties.getOrDefault(ADDRESS_AUTO_DELETE, false);
      final long autoDeleteDelay = ((Number) addressSourceProperties.getOrDefault(ADDRESS_AUTO_DELETE_DELAY, 0)).longValue();
      final long autoDeleteMsgCount = ((Number) addressSourceProperties.getOrDefault(ADDRESS_AUTO_DELETE_MSG_COUNT, 0)).longValue();

      // An address receiver may opt to filter on things like max message hops so we must
      // check for a filter here and apply it if it exists.
      final Map.Entry<Symbol, DescribedType> filter = AmqpSupport.findFilter(source.getFilter(), AmqpSupport.JMS_SELECTOR_FILTER_IDS);

      if (filter != null) {
         selector = filter.getValue().getDescribed().toString();
         try {
            SelectorParser.parse(selector);
         } catch (FilterException e) {
            throw new ActiveMQAMQPException(AmqpError.INVALID_FIELD, "Invalid filter", ActiveMQExceptionType.INVALID_FILTER_EXPRESSION);
         }
      } else {
         selector = null;
      }

      final SimpleString address = SimpleString.toSimpleString(source.getAddress());
      final AddressQueryResult addressQueryResult;

      try {
         addressQueryResult = sessionSPI.addressQuery(address, RoutingType.MULTICAST, true);
      } catch (ActiveMQSecurityException e) {
         throw ActiveMQAMQPProtocolMessageBundle.BUNDLE.securityErrorCreatingConsumer(e.getMessage());
      } catch (ActiveMQAMQPException e) {
         throw e;
      } catch (Exception e) {
         throw new ActiveMQAMQPInternalErrorException(e.getMessage(), e);
      }

      if (!addressQueryResult.isExists()) {
         throw ActiveMQAMQPProtocolMessageBundle.BUNDLE.sourceAddressDoesntExist();
      }

      final Set<RoutingType> routingTypes = addressQueryResult.getRoutingTypes();

      // Strictly enforce the MULTICAST nature of current address federation support.
      if (!routingTypes.contains(RoutingType.MULTICAST)) {
         throw new ActiveMQAMQPIllegalStateException("Address " + address + " is not configured for MULTICAST support");
      }

      final RoutingType routingType = getRoutingType(source);

      // Recover or create the queue we use to reflect the messages sent to the address to the remote
      QueueQueryResult queueQuery = sessionSPI.queueQuery(queueName, routingType, false);
      if (!queueQuery.isExists()) {
         final QueueConfiguration configuration = new QueueConfiguration(queueName);

         configuration.setAddress(address);
         configuration.setRoutingType(routingType);
         configuration.setAutoCreateAddress(false);
         configuration.setMaxConsumers(-1);
         configuration.setPurgeOnNoConsumers(false);
         configuration.setFilterString(selector);
         configuration.setDurable(true);
         configuration.setAutoCreated(false);
         configuration.setAutoDelete(autoDelete);
         configuration.setAutoDeleteDelay(autoDeleteDelay);
         configuration.setAutoDeleteMessageCount(autoDeleteMsgCount);

         // Try and create it and then later we will validate fully that it matches our expectations
         // since we could lose a race with some other resource creating its own resources.
         queueQuery = sessionSPI.queueQuery(configuration, true);
      }

      if (!queueQuery.getAddress().equals(address))  {
         throw new ActiveMQAMQPIllegalStateException("Requested queue: " + queueName + " for federation of address: " + address +
                                                     ", but it is already mapped to a different address: " + queueQuery.getAddress());
      }

      return (Consumer) sessionSPI.createSender(senderContext, queueName, null, false);
   }

   @Override
   public void close() throws Exception {
      // Currently there isn't anything needed on close of this controller.
   }

   private static RoutingType getRoutingType(Source source) {
      if (source != null) {
         if (source.getCapabilities() != null) {
            for (Symbol capability : source.getCapabilities()) {
               if (TOPIC_CAPABILITY.equals(capability)) {
                  return RoutingType.MULTICAST;
               } else if (QUEUE_CAPABILITY.equals(capability)) {
                  return RoutingType.ANYCAST;
               }
            }
         }
      }

      return ActiveMQDefaultConfiguration.getDefaultRoutingType();
   }
}
