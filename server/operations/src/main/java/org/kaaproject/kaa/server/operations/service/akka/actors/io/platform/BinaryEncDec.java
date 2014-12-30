/*
 * Copyright 2014 CyberVision, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kaaproject.kaa.server.operations.service.akka.actors.io.platform;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.kaaproject.kaa.common.Constants;
import org.kaaproject.kaa.server.operations.pojo.Base64Util;
import org.kaaproject.kaa.server.operations.pojo.sync.ClientSync;
import org.kaaproject.kaa.server.operations.pojo.sync.ClientSyncMetaData;
import org.kaaproject.kaa.server.operations.pojo.sync.ConfigurationClientSync;
import org.kaaproject.kaa.server.operations.pojo.sync.ConfigurationServerSync;
import org.kaaproject.kaa.server.operations.pojo.sync.EndpointAttachRequest;
import org.kaaproject.kaa.server.operations.pojo.sync.EndpointAttachResponse;
import org.kaaproject.kaa.server.operations.pojo.sync.EndpointDetachRequest;
import org.kaaproject.kaa.server.operations.pojo.sync.EndpointDetachResponse;
import org.kaaproject.kaa.server.operations.pojo.sync.EndpointVersionInfo;
import org.kaaproject.kaa.server.operations.pojo.sync.Event;
import org.kaaproject.kaa.server.operations.pojo.sync.EventClassFamilyVersionInfo;
import org.kaaproject.kaa.server.operations.pojo.sync.EventClientSync;
import org.kaaproject.kaa.server.operations.pojo.sync.EventListenersRequest;
import org.kaaproject.kaa.server.operations.pojo.sync.EventListenersResponse;
import org.kaaproject.kaa.server.operations.pojo.sync.EventServerSync;
import org.kaaproject.kaa.server.operations.pojo.sync.LogClientSync;
import org.kaaproject.kaa.server.operations.pojo.sync.LogEntry;
import org.kaaproject.kaa.server.operations.pojo.sync.LogServerSync;
import org.kaaproject.kaa.server.operations.pojo.sync.Notification;
import org.kaaproject.kaa.server.operations.pojo.sync.NotificationClientSync;
import org.kaaproject.kaa.server.operations.pojo.sync.NotificationServerSync;
import org.kaaproject.kaa.server.operations.pojo.sync.NotificationType;
import org.kaaproject.kaa.server.operations.pojo.sync.ProfileClientSync;
import org.kaaproject.kaa.server.operations.pojo.sync.ProfileServerSync;
import org.kaaproject.kaa.server.operations.pojo.sync.RedirectServerSync;
import org.kaaproject.kaa.server.operations.pojo.sync.ServerSync;
import org.kaaproject.kaa.server.operations.pojo.sync.SubscriptionCommand;
import org.kaaproject.kaa.server.operations.pojo.sync.SubscriptionCommandType;
import org.kaaproject.kaa.server.operations.pojo.sync.SubscriptionType;
import org.kaaproject.kaa.server.operations.pojo.sync.SyncResponseStatus;
import org.kaaproject.kaa.server.operations.pojo.sync.SyncStatus;
import org.kaaproject.kaa.server.operations.pojo.sync.Topic;
import org.kaaproject.kaa.server.operations.pojo.sync.TopicState;
import org.kaaproject.kaa.server.operations.pojo.sync.UserAttachNotification;
import org.kaaproject.kaa.server.operations.pojo.sync.UserAttachRequest;
import org.kaaproject.kaa.server.operations.pojo.sync.UserClientSync;
import org.kaaproject.kaa.server.operations.pojo.sync.UserDetachNotification;
import org.kaaproject.kaa.server.operations.pojo.sync.UserServerSync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is an implementation of {@link PlatformEncDec} that
 * uses internal binary protocol for data serialization.
 * 
 * @author Andrew Shvayka
 * 
 */
@KaaPlatformProtocol
public class BinaryEncDec implements PlatformEncDec {
    
    private static final int EVENT_SEQ_NUMBER_REQUEST_OPTION = 0x02;
    private static final int CONFIGURATION_HASH_OPTION = 0x02;
    public static final short PROTOCOL_VERSION = 1;
    public static final int MIN_SUPPORTED_VERSION = 1;
    public static final int MAX_SUPPORTED_VERSION = 1;

    // General constants
    private static final Logger LOG = LoggerFactory.getLogger(BinaryEncDec.class);
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final ByteBuffer EMPTY_BUFFER = ByteBuffer.wrap(new byte[0]);
    private static final int DEFAULT_BUFFER_SIZE = 128;
    private static final int SIZE_OF_INT = 4;
    private static final int EXTENSIONS_COUNT_POSITION = 6;
    private static final int MIN_SIZE_OF_MESSAGE_HEADER = 8;
    private static final int MIN_SIZE_OF_EXTENSION_HEADER = 8;
    private static final byte SUCCESS = 0x00;
    
    static final int PADDING_SIZE = 4;
    // Options
    static final byte FAILURE = 0x01;
    static final byte RESYNC = 0x01;
    static final byte NOTHING = 0x00;
    
    private static final byte USER_SYNC_ENDPOINT_ID_OPTION = 0x01;
    private static final short EVENT_DATA_IS_EMPTY_OPTION = (short) 0x02;
    private static final int CLIENT_EVENT_DATA_IS_PRESENT_OPTION = 0x02;
    private static final int CLIENT_META_SYNC_APP_TOKEN_OPTION = 0x08;
    private static final int CLIENT_META_SYNC_PROFILE_HASH_OPTION = 0x04;
    private static final int CLIENT_META_SYNC_KEY_HASH_OPTION = 0x02;
    private static final int CLIENT_META_SYNC_TIMEOUT_OPTION = 0x01;


    // Notification types
    static final byte SYSTEM = 0x00;
    static final byte CUSTOM = 0x01;
    // Subscription types
    static final byte MANDATORY = 0x00;
    static final byte OPTIONAL = 0x01;

    // Extension constants
    static final byte META_DATA_EXTENSION_ID = 1;
    static final byte PROFILE_EXTENSION_ID = 2;
    static final byte USER_EXTENSION_ID = 3;
    static final byte LOGGING_EXTENSION_ID = 4;
    static final byte CONFIGURATION_EXTENSION_ID = 5;
    static final byte NOTIFICATION_EXTENSION_ID = 6;
    static final byte EVENT_EXTENSION_ID = 7;

    // Meta data constants
    private static final int PUBLIC_KEY_HASH_SIZE = 20;
    private static final int PROFILE_HASH_SIZE = 20;
    private static final int CONFIGURATION_HASH_SIZE = 20;
    private static final int TOPIC_LIST_HASH_SIZE = 20;

    // Profile client sync fields
    private static final byte CONF_SCHEMA_VERSION_FIELD_ID = 0;
    private static final byte PROFILE_SCHEMA_VERSION_FIELD_ID = 1;
    private static final byte SYSTEM_NOTIFICATION_SCHEMA_VERSION_FIELD_ID = 2;
    private static final byte USER_NOTIFICATION_SCHEMA_VERSION_FIELD_ID = 3;
    private static final byte LOG_SCHEMA_VERSION_FIELD_ID = 4;
    private static final byte EVENT_FAMILY_VERSIONS_COUNT_FIELD_ID = 5;
    private static final byte PUBLIC_KEY_FIELD_ID = 6;
    private static final byte ACCESS_TOKEN_FIELD_ID = 7;

    // User client sync fields
    private static final byte USER_ATTACH_FIELD_ID = 0;
    private static final byte ENDPOINT_ATTACH_FIELD_ID = 1;
    private static final byte ENDPOINT_DETACH_FIELD_ID = 2;

    // User server sync fields
    private static final byte USER_ATTACH_RESPONSE_FIELD_ID = 0;
    private static final byte USER_ATTACH_NOTIFICATION_FIELD_ID = 1;
    private static final byte USER_DETACH_NOTIFICATION_FIELD_ID = 2;
    private static final byte ENDPOINT_ATTACH_RESPONSE_FIELD_ID = 3;
    private static final byte ENDPOINT_DETACH_RESPONSE_FIELD_ID = 4;

    // Event client sync fields
    private static final byte EVENT_LISTENERS_FIELD_ID = 0;
    private static final byte EVENT_LIST_FIELD_ID = 1;

    // Event server sync fields
    private static final byte EVENT_LISTENERS_RESPONSE_FIELD_ID = 0;
    private static final byte EVENT_LIST_RESPONSE_FIELD_ID = 1;

    // Notification client sync fields
    private static final byte NF_TOPIC_STATES_FIELD_ID = 0;
    private static final byte NF_UNICAST_LIST_FIELD_ID = 1;
    private static final byte NF_SUBSCRIPTION_ADD_FIELD_ID = 2;
    private static final byte NF_SUBSCRIPTION_REMOVE_FIELD_ID = 3;

    // Notification server sync fields
    private static final byte NF_NOTIFICATIONS_FIELD_ID = 0;
    private static final byte NF_TOPICS_FIELD_ID = 1;

    /*
     * (non-Javadoc)
     *
     * @see
     * org.kaaproject.kaa.server.operations.service.akka.actors.io.platform.
     * PlatformEncDec#getId()
     */
    @Override
    public int getId() {
        return Constants.KAA_PLATFORM_PROTOCOL_BINARY_ID;
    }

    @Override
    public ClientSync decode(byte[] data) throws PlatformEncDecException {
        if (LOG.isTraceEnabled()) {
            LOG.trace("Decoding binary data {}", Arrays.toString(data));
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        if (buf.remaining() < MIN_SIZE_OF_MESSAGE_HEADER) {
            throw new PlatformEncDecException(MessageFormat.format("Message header is to small {0} to be kaa binary message!",
                    buf.capacity()));
        }

        int protocolId = buf.getInt();
        if (protocolId != Constants.KAA_PLATFORM_PROTOCOL_AVRO_ID) {
            throw new PlatformEncDecException(MessageFormat.format("Unknown protocol id {0}!", protocolId));
        }

        int protocolVersion = getIntFromUnsignedShort(buf);
        if (protocolVersion < MIN_SUPPORTED_VERSION || protocolVersion > MAX_SUPPORTED_VERSION) {
            throw new PlatformEncDecException(MessageFormat.format("Can't decode data using protocol version {0}!", protocolVersion));
        }

        int extensionsCount = getIntFromUnsignedShort(buf);
        LOG.trace("received data for protocol id {} and version {} that contain {} extensions", protocolId, protocolVersion,
                extensionsCount);
        ClientSync sync = parseExtensions(buf, protocolVersion, extensionsCount);
        LOG.trace("Decoded binary data {}", sync);
        return sync;
    }

    @Override
    public byte[] encode(ServerSync sync) throws PlatformEncDecException {
        LOG.trace("Encoding server sync {}", sync);
        GrowingByteBuffer buf = new GrowingByteBuffer(DEFAULT_BUFFER_SIZE);
        buf.putInt(Constants.KAA_PLATFORM_PROTOCOL_AVRO_ID);
        buf.putShort(PROTOCOL_VERSION);
        buf.putShort(NOTHING); // will be updated later
        encodeMetaData(buf, sync);
        short extensionCount = 1;

        if (sync.getProfileSync() != null) {
            encode(buf, sync.getProfileSync());
            extensionCount++;
        }
        if (sync.getUserSync() != null) {
            encode(buf, sync.getUserSync());
            extensionCount++;
        }
        if (sync.getLogSync() != null) {
            encode(buf, sync.getLogSync());
            extensionCount++;
        }
        if (sync.getConfigurationSync() != null) {
            encode(buf, sync.getConfigurationSync());
            extensionCount++;
        }
        if (sync.getNotificationSync() != null) {
            encode(buf, sync.getNotificationSync());
            extensionCount++;
        }
        if (sync.getEventSync() != null) {
            encode(buf, sync.getEventSync());
            extensionCount++;
        }

        if (sync.getRedirectSync() != null) {
            encode(buf, sync.getRedirectSync());
            extensionCount++;
        }

        buf.putShort(EXTENSIONS_COUNT_POSITION, extensionCount);
        byte[] result =  buf.toByteArray();
        if (LOG.isTraceEnabled()) {
            LOG.trace("Encoded binary data {}", result);
        }
        return result;
    }

    private void buildExtensionHeader(GrowingByteBuffer buf, byte extensionId, byte optionA, byte optionB, byte optionC, int length) {
        buf.put(extensionId);
        buf.put(optionA);
        buf.put(optionB);
        buf.put(optionC);
        buf.putInt(length);
    }

    private void encodeMetaData(GrowingByteBuffer buf, ServerSync sync) {
        buildExtensionHeader(buf, META_DATA_EXTENSION_ID, NOTHING, NOTHING, NOTHING, 4);
        buf.putInt(sync.getRequestId());
    }

    private void encode(GrowingByteBuffer buf, ProfileServerSync profileSync) {
        buildExtensionHeader(buf, PROFILE_EXTENSION_ID, NOTHING, NOTHING,
                (profileSync.getResponseStatus() == SyncResponseStatus.RESYNC ? RESYNC : NOTHING), 0);
    }

    private void encode(GrowingByteBuffer buf, UserServerSync userSync) {
        buildExtensionHeader(buf, USER_EXTENSION_ID, NOTHING, NOTHING, NOTHING, 0);
        int extPosition = buf.position();
        if (userSync.getUserAttachResponse() != null) {
            buf.put(USER_ATTACH_RESPONSE_FIELD_ID);
            buf.put(NOTHING);
            buf.put(userSync.getUserAttachResponse().getResult() == SyncStatus.SUCCESS ? SUCCESS : FAILURE);
            buf.put(NOTHING);
        }
        if (userSync.getUserAttachNotification() != null) {
            UserAttachNotification nf = userSync.getUserAttachNotification();
            buf.put(USER_ATTACH_NOTIFICATION_FIELD_ID);
            buf.put((byte) nf.getUserExternalId().length());
            buf.putShort((short) nf.getEndpointAccessToken().length());
            putUTF(buf, nf.getUserExternalId());
            putUTF(buf, nf.getEndpointAccessToken());
        }
        if (userSync.getEndpointDetachResponses() != null) {
            UserDetachNotification nf = userSync.getUserDetachNotification();
            buf.put(USER_DETACH_NOTIFICATION_FIELD_ID);
            buf.put(NOTHING);
            buf.putShort((short) nf.getEndpointAccessToken().length());
        }
        if (userSync.getEndpointAttachResponses() != null) {
            buf.put(ENDPOINT_ATTACH_RESPONSE_FIELD_ID);
            buf.put(NOTHING);
            buf.putShort((short) userSync.getEndpointAttachResponses().size());
            for (EndpointAttachResponse response : userSync.getEndpointAttachResponses()) {
                buf.put(response.getResult() == SyncStatus.SUCCESS ? SUCCESS : FAILURE);
                if (response.getEndpointKeyHash() != null) {
                    buf.put(USER_SYNC_ENDPOINT_ID_OPTION);
                } else {
                    buf.put(NOTHING);
                }
                buf.putShort((short) Integer.valueOf(response.getRequestId()).intValue());
                if (response.getEndpointKeyHash() != null) {
                    putUTF(buf, response.getEndpointKeyHash());
                }
            }
        }
        if (userSync.getEndpointDetachResponses() != null) {
            buf.put(ENDPOINT_DETACH_RESPONSE_FIELD_ID);
            buf.put(NOTHING);
            buf.putShort((short) userSync.getEndpointDetachResponses().size());
            for (EndpointDetachResponse response : userSync.getEndpointDetachResponses()) {
                buf.put(response.getResult() == SyncStatus.SUCCESS ? SUCCESS : FAILURE);
                buf.put(NOTHING);
                buf.putShort((short) Integer.valueOf(response.getRequestId()).intValue());
            }
        }
        buf.putInt(extPosition - SIZE_OF_INT, buf.position() - extPosition);
    }

    private void encode(GrowingByteBuffer buf, LogServerSync logSync) {
        buildExtensionHeader(buf, USER_EXTENSION_ID, NOTHING, NOTHING, NOTHING, 4);
        buf.putShort((short) Integer.valueOf(logSync.getRequestId()).intValue());
        buf.put(logSync.getResult() == SyncStatus.SUCCESS ? SUCCESS : FAILURE);
        buf.put(NOTHING);
    }

    private void encode(GrowingByteBuffer buf, ConfigurationServerSync configurationSync) {
        int option = 0;
        if (configurationSync.getConfSchemaBody() != null) {
            option &= 0x01;
        }
        if (configurationSync.getConfDeltaBody() != null) {
            option &= 0x02;
        }
        buildExtensionHeader(buf, CONFIGURATION_EXTENSION_ID, NOTHING, NOTHING, (byte) option, 0);
        int extPosition = buf.position();

        buf.putInt(configurationSync.getAppStateSeqNumber());
        if (configurationSync.getConfSchemaBody() != null) {
            buf.putInt(configurationSync.getConfSchemaBody().array().length);
        }
        if (configurationSync.getConfDeltaBody() != null) {
            buf.putInt(configurationSync.getConfDeltaBody().array().length);
        }
        buf.put(configurationSync.getConfSchemaBody().array());
        buf.put(configurationSync.getConfDeltaBody().array());

        buf.putInt(extPosition - SIZE_OF_INT, buf.position() - extPosition);
    }

    private void encode(GrowingByteBuffer buf, NotificationServerSync notificationSync) {
        buildExtensionHeader(buf, NOTIFICATION_EXTENSION_ID, NOTHING, NOTHING, NOTHING, 0);
        int extPosition = buf.position();

        buf.putInt(notificationSync.getAppStateSeqNumber());
        if (notificationSync.getNotifications() != null) {
            buf.put(NF_NOTIFICATIONS_FIELD_ID);
            buf.put(NOTHING);
            buf.putShort((short) notificationSync.getNotifications().size());
            for (Notification nf : notificationSync.getNotifications()) {
                buf.putInt(nf.getSeqNumber());
                buf.put(nf.getType() == NotificationType.SYSTEM ? SYSTEM : CUSTOM);
                buf.put(NOTHING);
                buf.putShort(nf.getUid() != null ? (short) nf.getUid().length() : (short) 0);
                buf.putInt(nf.getBody().array().length);
                long topicId = nf.getTopicId() != null ? Long.valueOf(nf.getTopicId()) : 0l;
                buf.putLong(topicId);
                putUTF(buf, nf.getUid());
                put(buf, nf.getBody().array());
            }
        }
        if (notificationSync.getAvailableTopics() != null) {
            buf.put(NF_TOPICS_FIELD_ID);
            buf.put(NOTHING);
            buf.putShort((short) notificationSync.getAvailableTopics().size());
            for (Topic t : notificationSync.getAvailableTopics()) {
                buf.putLong(Long.parseLong(t.getId()));
                buf.putShort(t.getSubscriptionType() == SubscriptionType.MANDATORY ? MANDATORY : OPTIONAL);
                buf.put(NOTHING);
                buf.put((byte) t.getName().getBytes(UTF8).length);
                putUTF(buf, t.getName());
            }
        }

        buf.putInt(extPosition - SIZE_OF_INT, buf.position() - extPosition);
    }

    private void encode(GrowingByteBuffer buf, EventServerSync eventSync) {
        byte option = 0;
        if (eventSync.getEventSequenceNumberResponse() != null) {
            option = 1;
        }
        buildExtensionHeader(buf, EVENT_EXTENSION_ID, NOTHING, NOTHING, option, 0);
        int extPosition = buf.position();

        if (eventSync.getEventSequenceNumberResponse() != null) {
            buf.putInt(eventSync.getEventSequenceNumberResponse().getSeqNum());
        }

        if (eventSync.getEventListenersResponses() != null) {
            buf.put(EVENT_LISTENERS_RESPONSE_FIELD_ID);
            buf.put(NOTHING);
            buf.putShort((short) eventSync.getEventListenersResponses().size());
            for (EventListenersResponse response : eventSync.getEventListenersResponses()) {
                buf.putShort((short) response.getRequestId());
                buf.putShort(response.getResult() == SyncStatus.SUCCESS ? SUCCESS : FAILURE);
                if (response.getListeners() != null) {
                    buf.putInt(response.getListeners().size());
                    for (String listner : response.getListeners()) {
                        buf.put(Base64Util.decode(listner));
                    }
                } else {
                    buf.putInt(0);
                }
            }
        }
        if (eventSync.getEvents() != null) {
            buf.put(EVENT_LIST_RESPONSE_FIELD_ID);
            buf.put(NOTHING);
            buf.putShort((short) eventSync.getEvents().size());
            for (Event event : eventSync.getEvents()) {
                boolean eventDataIsEmpty = event.getEventData() == null || event.getEventData().array().length == 0;
                if (!eventDataIsEmpty) {
                    buf.putShort(EVENT_DATA_IS_EMPTY_OPTION);
                } else {
                    buf.putShort(NOTHING);
                }
                buf.putShort((short) event.getEventClassFQN().length());
                if (!eventDataIsEmpty) {
                    buf.putInt(event.getEventData().array().length);
                }
                buf.put(Base64Util.decode(event.getSource()));
                putUTF(buf, event.getEventClassFQN());
                if (!eventDataIsEmpty) {
                    put(buf, event.getEventData().array());
                }
            }
        }

        buf.putInt(extPosition - SIZE_OF_INT, buf.position() - extPosition);
    }

    private void encode(GrowingByteBuffer buf, RedirectServerSync redirectSync) {
        buildExtensionHeader(buf, EVENT_EXTENSION_ID, NOTHING, NOTHING, NOTHING, 4);
        buf.putInt(redirectSync.getDnsName().hashCode());
    }

    public void putUTF(GrowingByteBuffer buf, String str) {
        put(buf, str.getBytes(UTF8));
    }

    private void put(GrowingByteBuffer buf, byte[] data) {
        buf.put(data);
        int padding = data.length % BinaryEncDec.PADDING_SIZE;
        if (padding > 0) {
            padding = PADDING_SIZE - padding;
            for (int i = 0; i < padding; i++) {
                buf.put(NOTHING);
            }
        }
    }

    private ClientSync parseExtensions(ByteBuffer buf, int protocolVersion, int extensionsCount) throws PlatformEncDecException {
        ClientSync sync = new ClientSync();
        for (short extPos = 0; extPos < extensionsCount; extPos++) {
            if (buf.remaining() < MIN_SIZE_OF_EXTENSION_HEADER) {
                throw new PlatformEncDecException(MessageFormat.format(
                        "Extension header is to small. Available {0}, current possition is {1}!", buf.remaining(), buf.position()));
            }
            int extMetaData = buf.getInt();
            byte type = (byte) ((extMetaData & 0xFF000000) >> 24);
            int options = extMetaData & 0x00FFFFFF;
            int payloadLength = buf.getInt();
            if (buf.remaining() < payloadLength) {
                throw new PlatformEncDecException(MessageFormat.format(
                        "Extension payload is to small. Available {0}, expected {1} current possition is {2}!", buf.remaining(),
                        payloadLength, buf.position()));
            }
            switch (type) {
            case META_DATA_EXTENSION_ID:
                parseClientSyncMetaData(sync, buf, options, payloadLength);
                break;
            case PROFILE_EXTENSION_ID:
                parseProfileClientSync(sync, buf, options, payloadLength);
                break;
            case USER_EXTENSION_ID:
                parseUserClientSync(sync, buf, options, payloadLength);
                break;
            case LOGGING_EXTENSION_ID:
                parseLogClientSync(sync, buf, options, payloadLength);
                break;
            case CONFIGURATION_EXTENSION_ID:
                parseConfigurationClientSync(sync, buf, options, payloadLength);
                break;
            case NOTIFICATION_EXTENSION_ID:
                parseNotificationClientSync(sync, buf, options, payloadLength);
                break;
            case EVENT_EXTENSION_ID:
                parseEventClientSync(sync, buf, options, payloadLength);
                break;
            default:
                break;
            }
        }
        return validate(sync);
    }

    private void parseClientSyncMetaData(ClientSync sync, ByteBuffer buf, int options, int payloadLength) throws PlatformEncDecException {
        sync.setRequestId(buf.getInt());
        ClientSyncMetaData md = new ClientSyncMetaData();
        if (hasOption(options, CLIENT_META_SYNC_TIMEOUT_OPTION)) {
            md.setTimeout((long) buf.getInt());
        }
        if (hasOption(options, CLIENT_META_SYNC_KEY_HASH_OPTION)) {
            md.setEndpointPublicKeyHash(getNewByteBuffer(buf, PUBLIC_KEY_HASH_SIZE));
        }
        if (hasOption(options, CLIENT_META_SYNC_PROFILE_HASH_OPTION)) {
            md.setProfileHash(getNewByteBuffer(buf, PROFILE_HASH_SIZE));
        }
        if (hasOption(options, CLIENT_META_SYNC_APP_TOKEN_OPTION)) {
            md.setApplicationToken(getUTF8String(buf, Constants.APP_TOKEN_SIZE));
        }
        sync.setClientSyncMetaData(md);
    }

    private void parseProfileClientSync(ClientSync sync, ByteBuffer buf, int options, int payloadLength) {
        int payloadLimitPosition = buf.position() + payloadLength;
        ProfileClientSync profileSync = new ProfileClientSync();
        profileSync.setProfileBody(getNewByteBuffer(buf, buf.getInt()));
        profileSync.setVersionInfo(new EndpointVersionInfo());
        while (buf.position() < payloadLimitPosition) {
            byte fieldId = buf.get();
            // reading unused reserved field
            buf.get();
            switch (fieldId) {
            case CONF_SCHEMA_VERSION_FIELD_ID:
                profileSync.getVersionInfo().setConfigVersion(getIntFromUnsignedShort(buf));
                break;
            case PROFILE_SCHEMA_VERSION_FIELD_ID:
                profileSync.getVersionInfo().setProfileVersion(getIntFromUnsignedShort(buf));
                break;
            case SYSTEM_NOTIFICATION_SCHEMA_VERSION_FIELD_ID:
                profileSync.getVersionInfo().setSystemNfVersion(getIntFromUnsignedShort(buf));
                break;
            case USER_NOTIFICATION_SCHEMA_VERSION_FIELD_ID:
                profileSync.getVersionInfo().setUserNfVersion(getIntFromUnsignedShort(buf));
                break;
            case LOG_SCHEMA_VERSION_FIELD_ID:
                profileSync.getVersionInfo().setLogSchemaVersion(getIntFromUnsignedShort(buf));
                break;
            case EVENT_FAMILY_VERSIONS_COUNT_FIELD_ID:
                profileSync.getVersionInfo().setEventFamilyVersions(parseEventFamilyVersionList(buf, getIntFromUnsignedShort(buf)));
                break;
            case PUBLIC_KEY_FIELD_ID:
                profileSync.setEndpointPublicKey(getNewByteBuffer(buf, getIntFromUnsignedShort(buf)));
                break;
            case ACCESS_TOKEN_FIELD_ID:
                profileSync.setEndpointAccessToken(getUTF8String(buf));
            }
        }
        sync.setProfileSync(profileSync);
    }

    private void parseUserClientSync(ClientSync sync, ByteBuffer buf, int options, int payloadLength) {
        int payloadLimitPosition = buf.position() + payloadLength;
        UserClientSync userSync = new UserClientSync();
        while (buf.position() < payloadLimitPosition) {
            byte fieldId = buf.get();
            switch (fieldId) {
            case USER_ATTACH_FIELD_ID:
                userSync.setUserAttachRequest(parseUserAttachRequest(buf));
                break;
            case ENDPOINT_ATTACH_FIELD_ID:
                userSync.setEndpointAttachRequests(parseEndpointAttachRequests(buf));
                break;
            case ENDPOINT_DETACH_FIELD_ID:
                userSync.setEndpointDetachRequests(parseEndpointDetachRequests(buf));
                break;
            }
        }
        sync.setUserSync(userSync);
    }

    private void parseLogClientSync(ClientSync sync, ByteBuffer buf, int options, int payloadLength) {
        LogClientSync logSync = new LogClientSync();
        logSync.setRequestId(getIntFromUnsignedShort(buf));
        int size = getIntFromUnsignedShort(buf);
        List<LogEntry> logs = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            logs.add(new LogEntry(getNewByteBuffer(buf, buf.getInt())));
        }
        logSync.setLogEntries(logs);
        sync.setLogSync(logSync);
    }

    private void parseConfigurationClientSync(ClientSync sync, ByteBuffer buf, int options, int payloadLength) {
        ConfigurationClientSync confSync = new ConfigurationClientSync();
        confSync.setAppStateSeqNumber(buf.getInt());
        if (hasOption(options, CONFIGURATION_HASH_OPTION)) {
            confSync.setConfigurationHash(getNewByteBuffer(buf, CONFIGURATION_HASH_SIZE));
        }
        sync.setConfigurationSync(confSync);
    }

    private void parseEventClientSync(ClientSync sync, ByteBuffer buf, int options, int payloadLength) {
        EventClientSync eventSync = new EventClientSync();
        if (hasOption(options, EVENT_SEQ_NUMBER_REQUEST_OPTION)) {
            eventSync.setSeqNumberRequest(true);
        }
        int payloadLimitPosition = buf.position() + payloadLength;
        while (buf.position() < payloadLimitPosition) {
            byte fieldId = buf.get();
            // reading unused reserved field
            buf.get();
            switch (fieldId) {
            case EVENT_LISTENERS_FIELD_ID:
                eventSync.setEventListenersRequests(parseListenerRequests(buf));
                break;
            case EVENT_LIST_FIELD_ID:
                eventSync.setEvents(parseEvents(buf));
                break;
            default:
                break;
            }
        }
        sync.setEventSync(eventSync);
    }

    private void parseNotificationClientSync(ClientSync sync, ByteBuffer buf, int options, int payloadLength) {
        int payloadLimitPosition = buf.position() + payloadLength - TOPIC_LIST_HASH_SIZE;
        NotificationClientSync nfSync = new NotificationClientSync();
        nfSync.setAppStateSeqNumber(buf.getInt());
        while (buf.position() < payloadLimitPosition) {
            byte fieldId = buf.get();
            // reading unused reserved field
            buf.get();
            switch (fieldId) {
            case NF_TOPIC_STATES_FIELD_ID:
                nfSync.setTopicStates(parseTopicStates(buf));
                break;
            case NF_UNICAST_LIST_FIELD_ID:
                nfSync.setAcceptedUnicastNotifications(parseUnicastIds(buf));
                break;
            case NF_SUBSCRIPTION_ADD_FIELD_ID:
                parseSubscriptionCommands(nfSync, buf, true);
                break;
            case NF_SUBSCRIPTION_REMOVE_FIELD_ID:
                parseSubscriptionCommands(nfSync, buf, false);
                break;
            }
        }
        nfSync.setTopicListHash(getNewByteBuffer(buf, TOPIC_LIST_HASH_SIZE));
        sync.setNotificationSync(nfSync);
    }

    private void parseSubscriptionCommands(NotificationClientSync nfSync, ByteBuffer buf, boolean add) {
        int count = getIntFromUnsignedShort(buf);
        if (nfSync.getSubscriptionCommands() == null) {
            nfSync.setSubscriptionCommands(new ArrayList<SubscriptionCommand>());
        }
        List<SubscriptionCommand> commands = new ArrayList<SubscriptionCommand>();
        for (int i = 0; i < count; i++) {
            long topicId = buf.getLong();
            commands.add(new SubscriptionCommand(String.valueOf(topicId), add ? SubscriptionCommandType.ADD
                    : SubscriptionCommandType.REMOVE));
        }
        nfSync.getSubscriptionCommands().addAll(commands);
    }

    private List<TopicState> parseTopicStates(ByteBuffer buf) {
        int count = getIntFromUnsignedShort(buf);
        List<TopicState> topicStates = new ArrayList<TopicState>(count);
        for (int i = 0; i < count; i++) {
            long topicId = buf.getLong();
            int seqNumber = buf.getInt();
            topicStates.add(new TopicState(String.valueOf(topicId), seqNumber));
        }
        return topicStates;
    }

    private List<String> parseUnicastIds(ByteBuffer buf) {
        int count = getIntFromUnsignedShort(buf);
        List<String> uids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int uidLength = buf.getInt();
            uids.add(getUTF8String(buf, uidLength));
        }
        return uids;
    }

    private List<EventListenersRequest> parseListenerRequests(ByteBuffer buf) {
        int requestsCount = getIntFromUnsignedShort(buf);
        List<EventListenersRequest> requests = new ArrayList<>(requestsCount);
        for (int i = 0; i < requestsCount; i++) {
            int requestId = getIntFromUnsignedShort(buf);
            int fqnCount = getIntFromUnsignedShort(buf);
            List<String> fqns = new ArrayList<>(fqnCount);
            for (int j = 0; j < fqnCount; j++) {
                int fqnLength = getIntFromUnsignedShort(buf);
                // reserved
                buf.getShort();
                fqns.add(getUTF8String(buf, fqnLength));
            }
            requests.add(new EventListenersRequest(requestId, fqns));
        }
        return requests;
    }

    private List<Event> parseEvents(ByteBuffer buf) {
        int eventsCount = getIntFromUnsignedShort(buf);
        List<Event> events = new ArrayList<>(eventsCount);
        for (int i = 0; i < eventsCount; i++) {
            Event event = new Event();
            event.setSeqNum(buf.getInt());
            int eventOptions = getIntFromUnsignedShort(buf);
            int fqnLength = getIntFromUnsignedShort(buf);
            int dataSize = 0;
            if (hasOption(eventOptions, CLIENT_EVENT_DATA_IS_PRESENT_OPTION)) {
                dataSize = buf.getInt();
            }
            if (hasOption(eventOptions, 0x01)) {
                event.setTarget(Base64Util.encode(getNewByteArray(buf, PUBLIC_KEY_HASH_SIZE)));
            }
            event.setEventClassFQN(getUTF8String(buf, fqnLength));
            if (dataSize > 0) {
                event.setEventData(getNewByteBuffer(buf, dataSize));
            } else {
                event.setEventData(EMPTY_BUFFER);
            }
            events.add(event);
        }
        return events;
    }

    private List<EndpointAttachRequest> parseEndpointAttachRequests(ByteBuffer buf) {
        // reserved
        buf.get();
        int count = getIntFromUnsignedShort(buf);
        List<EndpointAttachRequest> requests = new ArrayList<EndpointAttachRequest>(count);
        for (int i = 0; i < count; i++) {
            int requestId = getIntFromUnsignedShort(buf);
            String accessToken = getUTF8String(buf);
            requests.add(new EndpointAttachRequest(requestId, accessToken));
        }
        return requests;
    }

    private List<EndpointDetachRequest> parseEndpointDetachRequests(ByteBuffer buf) {
        // reserved
        buf.get();
        int count = getIntFromUnsignedShort(buf);
        List<EndpointDetachRequest> requests = new ArrayList<EndpointDetachRequest>(count);
        for (int i = 0; i < count; i++) {
            int requestId = getIntFromUnsignedShort(buf);
            // reserved
            buf.getShort();
            requests.add(new EndpointDetachRequest(requestId, Base64Util.encode(getNewByteArray(buf, PUBLIC_KEY_HASH_SIZE))));
        }
        return requests;
    }

    private UserAttachRequest parseUserAttachRequest(ByteBuffer buf) {
        int extIdLength = buf.get() & 0xFF;
        int tokenLength = getIntFromUnsignedShort(buf);
        String userExternalId = getUTF8String(buf, extIdLength);
        String userAccessToken = getUTF8String(buf, tokenLength);
        return new UserAttachRequest(userExternalId, userAccessToken);
    }

    private static List<EventClassFamilyVersionInfo> parseEventFamilyVersionList(ByteBuffer buf, int count) {
        List<EventClassFamilyVersionInfo> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int version = getIntFromUnsignedShort(buf);
            result.add(new EventClassFamilyVersionInfo(getUTF8String(buf), version));
        }
        return result;
    }

    private static int getIntFromUnsignedShort(ByteBuffer buf) {
        // handle unsigned integers from client
        return (int) buf.getChar();
    }

    private static boolean hasOption(int options, int option) {
        return (options & option) > 0;
    }

    private static String getUTF8String(ByteBuffer buf) {
        return getUTF8String(buf, getIntFromUnsignedShort(buf));
    }

    private static String getUTF8String(ByteBuffer buf, int size) {
        return new String(getNewByteArray(buf, size), UTF8);
    }

    private static byte[] getNewByteArray(ByteBuffer buf, int size, boolean withPadding) {
        byte[] array = new byte[size];
        buf.get(array);
        if (withPadding) {
            handlePadding(buf, size);
        }
        return array;
    }

    private static void handlePadding(ByteBuffer buf, int size) {
        int padding = size % PADDING_SIZE;
        if (padding > 0) {
            buf.position(buf.position() + (PADDING_SIZE - padding));
        }
    }

    private static byte[] getNewByteArray(ByteBuffer buf, int size) {
        return getNewByteArray(buf, size, true);
    }

    private static ByteBuffer getNewByteBuffer(ByteBuffer buf, int size) {
        return getNewByteBuffer(buf, size, true);
    }

    private static ByteBuffer getNewByteBuffer(ByteBuffer buf, int size, boolean withPadding) {
        return ByteBuffer.wrap(getNewByteArray(buf, size, withPadding));
    }

    private ClientSync validate(ClientSync sync) throws PlatformEncDecException {
        if (sync.getClientSyncMetaData() == null) {
            throw new PlatformEncDecException(MessageFormat.format("Input data does not have client sync meta data: {0}!", sync));
        }
        return sync;
    }
}
