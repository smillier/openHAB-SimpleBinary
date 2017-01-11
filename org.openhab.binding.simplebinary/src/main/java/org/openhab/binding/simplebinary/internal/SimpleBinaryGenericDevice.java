/**
 * Copyright (c) 2010-2016, openHAB.org and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.openhab.binding.simplebinary.internal;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.BufferUnderflowException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.openhab.binding.simplebinary.internal.SimpleBinaryDeviceState.DeviceStates;
import org.openhab.binding.simplebinary.internal.SimpleBinaryGenericBindingProvider.SimpleBinaryBindingConfig;
import org.openhab.binding.simplebinary.internal.SimpleBinaryGenericBindingProvider.SimpleBinaryInfoBindingConfig;
import org.openhab.core.events.EventPublisher;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generic device class
 *
 * @author Vita Tucek
 * @since 1.9.0
 */
public class SimpleBinaryGenericDevice implements SimpleBinaryIDevice {
    private static final Logger logger = LoggerFactory.getLogger(SimpleBinaryGenericDevice.class);

    /** device name ex.: port, port1, tcp, tcp1, ... */
    protected final String deviceName;
    /** device ID ex.: , COM1, /dev/ttyS1, 192.168.1.1, ... */
    protected final String deviceID;
    /** defines maximum resend count */
    public final int MAX_RESEND_COUNT = 2;

    protected EventPublisher eventPublisher;
    /** item config */
    protected Map<String, SimpleBinaryBindingConfig> itemsConfig;

    /** flag that device is connected */
    protected boolean connected = false;
    /** queue for commands */
    protected final BlockingDeque<SimpleBinaryItemData> commandQueue = new LinkedBlockingDeque<SimpleBinaryItemData>();
    /** Used pool control ex.: OnChange, OnScan */
    protected final SimpleBinaryPoolControl poolControl;
    /** State of socket */
    public SimpleBinaryPortState portState = new SimpleBinaryPortState();
    /** State of connected slave devices */
    public SimpleBinaryDeviceStateCollection devicesStates;
    /** Lock for process commands to prevent run it twice **/
    protected final Lock lock = new ReentrantLock();

    public class ProcessDataResult {
        public static final int DATA_NOT_COMPLETED = -1;
        public static final int PROCESSING_ERROR = -2;
        public static final int INVALID_CRC = -3;
        public static final int BAD_CONFIG = -4;
        public static final int NO_VALID_ADDRESS = -5;
        public static final int NO_VALID_ADDRESS_REWIND = -6;
        public static final int UNKNOWN_MESSAGE = -7;
        public static final int UNKNOWN_MESSAGE_REWIND = -8;
    }

    /**
     * Constructor
     *
     * @param deviceName
     * @param simpleBinaryPoolControl
     */
    public SimpleBinaryGenericDevice(String deviceName, String deviceID,
            SimpleBinaryPoolControl simpleBinaryPoolControl) {
        this.deviceName = deviceName;
        this.deviceID = deviceID;
        this.poolControl = simpleBinaryPoolControl;
    }

    /**
     * Method to set binding configuration
     *
     * @param eventPublisher
     * @param itemsConfig
     * @param itemsInfoConfig
     */
    @Override
    public void setBindingData(EventPublisher eventPublisher, Map<String, SimpleBinaryBindingConfig> itemsConfig,
            Map<String, SimpleBinaryInfoBindingConfig> itemsInfoConfig) {
        this.eventPublisher = eventPublisher;
        this.itemsConfig = itemsConfig;

        this.portState.setBindingData(eventPublisher, itemsInfoConfig, this.deviceName);
        this.devicesStates = new SimpleBinaryDeviceStateCollection(deviceName, itemsInfoConfig, eventPublisher);
    }

    /**
     * Method to clear inner binding configuration
     */
    @Override
    public void unsetBindingData() {
        this.eventPublisher = null;
        this.itemsConfig = null;
        this.devicesStates = null;
    }

    /**
     * Check if port is opened
     *
     * @return
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * Open
     *
     * @see org.openhab.binding.simplebinary.internal.SimpleBinaryIDevice#open()
     */
    @Override
    public Boolean open() {
        logger.warn("{} - Opening... cannot open generic device", toString());

        return false;
    }

    /**
     * Close
     *
     * @see org.openhab.binding.simplebinary.internal.SimpleBinaryIDevice#close()
     */
    @Override
    public void close() {
        logger.warn("{} - Closing... cannot close generic device", toString());

        connected = false;
    }

    /**
     * Reconnect device
     */
    private void reconnect() {
        logger.info("{}: Trying to reconnect", toString());

        close();
        open();
    }

    /**
     * Send command into device channel
     *
     * @throws InterruptedException
     *
     * @see org.openhab.binding.simplebinary.internal.SimpleBinaryIDevice#sendData(java.lang.String,
     *      org.openhab.core.types.Command,
     *      org.openhab.binding.simplebinary.internal.SimpleBinaryGenericBindingProvider.SimpleBinaryBindingConfig)
     */
    @Override
    public void sendData(String itemName, Command command, SimpleBinaryBindingConfig config)
            throws InterruptedException {
        // compile data
        SimpleBinaryItem data = SimpleBinaryProtocol.compileDataFrame(itemName, command, config);

        sendData(data);
    }

    /**
     * Add compiled data item to sending queue
     *
     * @param data
     * @throws InterruptedException
     */
    public void sendData(SimpleBinaryItemData data) throws InterruptedException {
        if (data != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}: Adding command into queue", toString());
            }

            // add data
            commandQueue.put(data);

            processCommandQueue();
        } else {
            logger.warn("{}: Nothing to send. Empty data", toString());
        }
    }

    /**
     * Add compiled data item to sending queue at first place
     *
     * @param data
     * @throws InterruptedException
     */
    public void sendDataPriority(SimpleBinaryItemData data) throws InterruptedException {
        if (data != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("{}: Adding priority command into queue", toString());
            }

            // add data
            commandQueue.putFirst(data);

            processCommandQueue();
        } else {
            logger.warn("{}: Nothing to send. Empty data", toString());
        }
    }

    /**
     * Prepare request to check if device with specific address has new data
     *
     * @param deviceAddress Device address
     * @param forceAllDataAsNew Flag to force send all data from slave
     * @throws InterruptedException
     */
    protected void offerNewDataCheck(int deviceAddress, boolean forceAllDataAsNew) throws InterruptedException {
        SimpleBinaryItemData data = SimpleBinaryProtocol.compileNewDataFrame(deviceAddress, forceAllDataAsNew);

        if (logger.isDebugEnabled()) {
            logger.debug("{} - offerNewDataCheck() device={} force={}", toString(), deviceAddress, forceAllDataAsNew);
        }
        // check if packet already exist
        if (!dataInQueue(data)) {
            commandQueue.putLast(data);
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("{} - offerNewDataCheck() - allready in queue", toString());
            }
        }
    }

    /**
     * Put "check new data" packet of specified device in front of command queue
     *
     * @param deviceAddress Device address
     * @throws InterruptedException
     */
    protected void offerNewDataCheckPriority(int deviceAddress) throws InterruptedException {
        offerNewDataCheckPriority(deviceAddress, false);
    }

    /**
     * Put "check new data" packet of specified device in front of command queue
     *
     * @param deviceAddress Device address
     * @param forceAllDataAsNew Flag to force send all data from slave
     * @throws InterruptedException
     */
    protected void offerNewDataCheckPriority(int deviceAddress, boolean forceAllDataAsNew) throws InterruptedException {
        SimpleBinaryItemData data = SimpleBinaryProtocol.compileNewDataFrame(deviceAddress, forceAllDataAsNew);

        commandQueue.putFirst(data);
    }

    /**
     * Prepare request for read data of specific item
     *
     * @param itemConfig
     * @throws InterruptedException
     */
    protected void sendReadData(SimpleBinaryBindingConfig itemConfig) throws InterruptedException {
        SimpleBinaryItemData data = SimpleBinaryProtocol.compileReadDataFrame(itemConfig);

        // check if packet already exist
        if (!dataInQueue(data)) {
            commandQueue.putLast(data);
        }
    }

    /**
     * Check if data packet is not already in queue
     *
     * @param item
     * @return
     */
    protected boolean dataInQueue(SimpleBinaryItemData item) {
        if (commandQueue.isEmpty()) {
            return false;
        }

        for (SimpleBinaryItemData qitem : commandQueue) {
            if (qitem.getData().length != item.getData().length) {
                break;
            }

            for (int i = 0; i < qitem.getData().length; i++) {
                if (qitem.getData()[i] != item.getData()[i]) {
                    break;
                }

                if (i == qitem.getData().length - 1) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Check if queue has data for specified device and send it
     *
     */
    protected void processCommandQueue(int thisDeviceOnly) {
        if (logger.isDebugEnabled()) {
            logger.debug("{} - Processing commandQueue - length {}. Thread={}", toString(), commandQueue.size(),
                    Thread.currentThread().getId());
        }

        // no reply expected
        if (!canSend()) {
            if (logger.isDebugEnabled()) {
                logger.debug("{} - Processing commandQueue - waiting", this.toString());
            }
            return;
        }

        if (!lock.tryLock()) {
            if (logger.isDebugEnabled()) {
                logger.debug("{} - CommandQueue locked. Leaving processCommandQueue.", toString());
            }
            return;
        }

        SimpleBinaryItemData dataToSend = null;

        try {
            // queue is empty -> exit
            if (commandQueue.isEmpty()) {
                return;
            }

            for (SimpleBinaryItemData i : commandQueue) {
                if (i.deviceId == thisDeviceOnly) {
                    commandQueue.removeFirstOccurrence(i);
                    dataToSend = i;
                    break;
                }
            }
        } catch (Exception e) {
            logger.error("{} - Processing commandQueue(int) - error: {}", this.toString(), e.getMessage());
        } finally {
            lock.unlock();
        }

        if (dataToSend != null) {
            sendDataOut(dataToSend);
        }
    }

    /**
     * Check if queue is not empty and send data to device
     *
     */
    protected void processCommandQueue() {
        if (logger.isDebugEnabled()) {
            logger.debug("{} - Processing commandQueue - length {}. Thread={}", toString(), commandQueue.size(),
                    Thread.currentThread().getId());
        }

        // no reply expected
        if (!canSend()) {
            if (logger.isDebugEnabled()) {
                logger.debug("{} - Processing commandQueue - waiting", this.toString());
            }
            return;
        }

        if (!lock.tryLock()) {
            if (logger.isDebugEnabled()) {
                logger.debug("{} - CommandQueue locked. Leaving processCommandQueue.", toString());
            }
            return;
        }

        SimpleBinaryItemData dataToSend = null;

        try {
            // queue is empty -> exit
            if (commandQueue.isEmpty()) {
                return;
            }

            // check first command in queue
            SimpleBinaryItemData firstdata = commandQueue.take();
            // state of command device
            DeviceStates state = this.devicesStates.getDeviceState(firstdata.deviceId);

            if (logger.isDebugEnabled()) {
                logger.debug("{} - Processing commandQueue - first command deviceID={}", this.toString(),
                        firstdata.deviceId);
            }

            // check if device responds and there is lot of commands
            if (state != DeviceStates.NOT_RESPONDING || (commandQueue.isEmpty())) {
                dataToSend = firstdata;
                if (logger.isDebugEnabled()) {
                    if (commandQueue.isEmpty()) {
                        logger.debug("{} - Processing commandQueue - only one command. Command will be sent.",
                                this.toString(), firstdata.deviceId);
                    } else {
                        logger.debug(
                                "{} - Processing commandQueue - deviceID={} - device responding. Command will be sent.",
                                this.toString(), firstdata.deviceId);
                    }
                }
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug(
                            "{} - Processing commandQueue - deviceID={} - device NOT responding. Command queue will be reordered.",
                            this.toString(), firstdata.deviceId);

                    String commands = firstdata.deviceId + "/" + Integer.toHexString(firstdata.messageId & 0xFF);
                    for (SimpleBinaryItemData i : commandQueue) {
                        commands += ";" + i.deviceId + "/" + Integer.toHexString(i.messageId & 0xFF);
                    }

                    logger.debug("{} - Processing commandQueue - before reorder queue(deviceID/messageID)={}",
                            this.toString(), commands);
                }
                // reorder queue - all commands from dead device put at the end of the queue
                List<SimpleBinaryItemData> deadData = new ArrayList<SimpleBinaryItemData>();
                List<SimpleBinaryItemData> otherData = new ArrayList<SimpleBinaryItemData>();

                SimpleBinaryItemData data = firstdata;
                deadData.add(data);

                // over all items until item isn't same as first one
                do {
                    data = commandQueue.take();

                    if (data.deviceId == firstdata.deviceId) {
                        deadData.add(data);
                    } else {
                        otherData.add(data);
                        // commandQueue.offer(data);
                    }

                } while (!commandQueue.isEmpty());

                // TODO: at begin put "check new data"??? - but only if ONCHANGE. What if ONSCAN???

                if (logger.isDebugEnabled()) {
                    logger.debug("{} - Processing commandQueue - otherData.size()={},deadData.size()={}",
                            this.toString(), otherData.size(), deadData.size());
                }

                // first add commands from non dead device
                commandQueue.addAll(otherData);
                // add dead device data
                commandQueue.addAll(deadData);

                if (logger.isDebugEnabled()) {
                    String commands = "";
                    for (SimpleBinaryItemData i : commandQueue) {
                        if (!commands.isEmpty()) {
                            commands += ";";
                        }
                        commands += i.deviceId + "/" + Integer.toHexString(i.messageId & 0xFF);
                    }

                    logger.debug("{} - Processing commandQueue - after reorder queue(deviceID/messageID)={}",
                            this.toString(), commands);
                }

                // put first command (no matter if device not responding)
                dataToSend = commandQueue.take();

                if (logger.isDebugEnabled()) {
                    logger.debug("{} - Processing commandQueue - after reorder first command will be sent. DeviceID={}",
                            this.toString(), dataToSend.deviceId);
                }
            }
        } catch (Exception e) {
            logger.error("{} - Processing commandQueue - error: {}", this.toString(), e.getMessage());
        } finally {
            lock.unlock();
        }

        if (dataToSend != null) {
            sendDataOut(dataToSend);
        }
    }

    protected boolean canSend() {
        return true;
    }

    /**
     * Write data into device stream
     *
     * @param data
     *            Item data with compiled packet
     * @return
     *         Return true when data were sent
     */
    protected boolean sendDataOut(SimpleBinaryItemData data) {

        logger.warn("{} - Generic device can't send data", this.toString());

        return false;
    }

    /**
     * Resend last sent data
     *
     * @throws InterruptedException
     */
    protected void resendData(SimpleBinaryItemData lastData) throws InterruptedException {
        if (lastData != null) {
            if (lastData.getResendCounter() < MAX_RESEND_COUNT) {
                lastData.incrementResendCounter();
                sendDataPriority(lastData);
            } else {
                logger.warn("{} - Device {} - Max resend attempts reached.", this.toString(), lastData.getDeviceId());
                // set state
                devicesStates.setDeviceState(this.deviceName, lastData.getDeviceId(), DeviceStates.RESPONSE_ERROR);
            }
        }
    }

    /**
     * Print communication information
     *
     */
    protected void printCommunicationInfo(SimpleBinaryByteBuffer inBuffer, SimpleBinaryItemData lastSentData) {
        try {
            // content of input buffer
            int position = inBuffer.position();
            inBuffer.rewind();
            byte[] data = new byte[inBuffer.limit()];
            inBuffer.get(data);
            inBuffer.position(position);
            logger.info("{} - Data in input buffer: {}", toString(),
                    SimpleBinaryProtocol.arrayToString(data, data.length));

            if (lastSentData != null) {
                // last data out
                logger.info("{} - Last sent data: {}", toString(),
                        SimpleBinaryProtocol.arrayToString(lastSentData.getData(), lastSentData.getData().length));
            }
        } catch (ModeChangeException e) {
            logger.error(e.getMessage());
        }
    }

    /**
     * @see org.openhab.binding.simplebinary.internal.SimpleBinaryIDevice#checkNewData()
     */
    @Override
    public void checkNewData() {

        if (!isConnected()) {
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("{} - checkNewData() in mode {} is called", toString(), poolControl);
        }

        if (poolControl == SimpleBinaryPoolControl.ONSCAN) {
            for (Map.Entry<String, SimpleBinaryBindingConfig> item : itemsConfig.entrySet()) {
                if (item.getValue().device.equals(this.deviceName)) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("{} - checkNewData() item={} direction={} ", toString(),
                                item.getValue().item.getName(), item.getValue().direction);
                    }
                    // input direction only
                    if (item.getValue().direction < 2) {
                        try {
                            this.sendReadData(item.getValue());
                        } catch (InterruptedException e) {
                            logger.error("{} - checkNewData() error: {} ", toString(), e.getMessage());
                        }
                    }
                }
            }
        } else if (poolControl == SimpleBinaryPoolControl.ONCHANGE) {
            // create devices map with device address and state
            Map<Integer, DeviceStates> deviceItems = new HashMap<Integer, DeviceStates>();
            // fill new created map - every device has one record
            for (Map.Entry<String, SimpleBinaryBindingConfig> item : itemsConfig.entrySet()) {
                if (item.getValue().device.equals(this.deviceName)) {
                    if (!deviceItems.containsKey(item.getValue().busAddress)) {
                        // for device retrieve his state
                        deviceItems.put(item.getValue().busAddress,
                                this.devicesStates.getDeviceState(item.getValue().busAddress));
                    }
                }
            }

            // take every device and create for him command depend on his state
            for (Map.Entry<Integer, DeviceStates> device : deviceItems.entrySet()) {
                boolean forceAllValues = device.getValue() == DeviceStates.UNKNOWN
                        || device.getValue() == DeviceStates.NOT_RESPONDING
                        || device.getValue() == DeviceStates.RESPONSE_ERROR;

                if (logger.isDebugEnabled()) {
                    logger.debug("{} - checkNewData() device={} force={}", toString(), device.getKey(), forceAllValues);
                }

                try {
                    if (forceAllValues) {
                        this.offerNewDataCheckPriority(device.getKey(), forceAllValues);
                    } else {
                        this.offerNewDataCheck(device.getKey(), forceAllValues);
                    }
                } catch (InterruptedException e) {
                    logger.error("{} - checkNewData() error: {} ", toString(), e.getMessage());
                }
            }

            deviceItems = null;
        }

        processCommandQueue();
    }

    protected int getDeviceID(SimpleBinaryByteBuffer inBuffer) {
        try {
            // flip buffer
            inBuffer.flip();

            int receivedID = inBuffer.get();
            inBuffer.rewind();

            return receivedID;

        } catch (ModeChangeException ex) {
            logger.error("{} - Bad operation: {}", this.toString(), ex.getMessage());
        } finally {
            try {
                inBuffer.compact();
            } catch (ModeChangeException ex) {
                logger.error("{} - Bad operation: {}", this.toString(), ex.getMessage());
            }
        }

        return -1;
    }

    protected boolean checkDeviceID(SimpleBinaryByteBuffer inBuffer, int expectedID) {
        return expectedID == getDeviceID(inBuffer);
    }

    protected int verifyDataOnly(SimpleBinaryByteBuffer inBuffer) {
        try {
            // flip buffer
            inBuffer.flip();

            if (logger.isDebugEnabled()) {
                logger.debug("{} - Verifying data, lenght={} bytes", toString(), inBuffer.limit());
            }

            int receivedID = inBuffer.get();
            inBuffer.rewind();
            // decompile income message
            SimpleBinaryMessage itemData = SimpleBinaryProtocol.decompileData(inBuffer, itemsConfig, deviceName, true);

            // is decompiled
            if (itemData != null) {
                return receivedID;
            }
        } catch (Exception ex) {
            logger.error("{} - Verify data error: {}", toString(), ex.getMessage());
        } finally {
            try {
                inBuffer.compact();

                if (logger.isDebugEnabled()) {
                    logger.debug("{} - Verify data OK, lenght={} bytes", toString(), inBuffer.position());
                }
            } catch (ModeChangeException ex) {
                logger.error("{} - Bad operation: {}", this.toString(), ex.getMessage());
            }
        }
        return -1;
    }

    /**
     * Process incoming data
     *
     * @param inBuffer Buffer with receiver data
     * @param lastSentData Last data sent to device
     *
     * @return Return device ID or error code when lower than 0
     */
    protected int processData(SimpleBinaryByteBuffer inBuffer, SimpleBinaryItemData lastSentData) {

        int receivedID = -1;

        try {
            // flip buffer
            inBuffer.flip();

            if (logger.isDebugEnabled()) {
                logger.debug("{} - Reading input buffer, lenght={} bytes", toString(), inBuffer.limit());
            }

            receivedID = inBuffer.get();
            inBuffer.rewind();
            // decompile income message
            SimpleBinaryMessage itemData = SimpleBinaryProtocol.decompileData(inBuffer, itemsConfig, deviceName);

            // is decompiled
            if (itemData != null) {
                // process data
                processDecompiledData(itemData, lastSentData);

                // compact buffer
                inBuffer.compact();

                return receivedID;
            } else {
                logger.warn("{} - Cannot decompile incoming data", toString());
                // rewind at start position (wait for next bytes)
                inBuffer.rewind();
                // compact buffer
                inBuffer.compact();

                return ProcessDataResult.DATA_NOT_COMPLETED;
            }
        } catch (BufferUnderflowException ex) {
            logger.warn("{} - Buffer underflow while reading: {}", toString(), ex.getMessage());
            // print details
            printCommunicationInfo(inBuffer, lastSentData);

            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            ex.printStackTrace(pw);
            logger.warn("Underflow stacktrace: {}", sw.toString());

            logger.warn("Buffer mode {}, remaining={} bytes, limit={} bytes", inBuffer.getMode().toString(),
                    inBuffer.remaining(), inBuffer.limit());

            try {
                // rewind buffer
                inBuffer.rewind();
                logger.warn("Buffer mode {}, remaining after rewind {} bytes", inBuffer.getMode().toString(),
                        inBuffer.remaining());
                // compact buffer
                inBuffer.compact();
            } catch (ModeChangeException exep) {
                logger.error(exep.getMessage());
            }

            return ProcessDataResult.PROCESSING_ERROR;

        } catch (NoValidCRCException ex) {
            logger.error("{} - Invalid CRC while reading: {}", this.toString(), ex.getMessage());
            // print details
            printCommunicationInfo(inBuffer, lastSentData);
            // compact buffer
            try {
                inBuffer.compact();
            } catch (ModeChangeException e) {
                logger.error(e.getMessage());
            }

            try {
                resendData(lastSentData);
            } catch (InterruptedException e) {
                logger.error(e.getMessage());
            }

            return ProcessDataResult.INVALID_CRC;

        } catch (NoValidItemInConfig ex) {
            logger.error("{} - Item not found in items config: {}", this.toString(), ex.getMessage());
            // print details
            printCommunicationInfo(inBuffer, lastSentData);
            // compact buffer
            try {
                inBuffer.compact();
            } catch (ModeChangeException e) {
                logger.error(e.getMessage());
            }

            // set state
            devicesStates.setDeviceState(this.deviceName, receivedID, DeviceStates.DATA_ERROR);

            return ProcessDataResult.BAD_CONFIG;

        } catch (UnknownMessageException ex) {
            logger.error("{} - Income unknown message: {}", this.toString(), ex.getMessage());
            // print details
            printCommunicationInfo(inBuffer, lastSentData);

            // only 4 bytes (minus 1 after delete first byte) is not enough to have complete message
            if (inBuffer.limit() < 5) {
                // clear buffer
                inBuffer.clear();

                logger.warn("{} - Income unknown message: input buffer cleared", this.toString());

                // set state
                devicesStates.setDeviceState(this.deviceName, receivedID, DeviceStates.DATA_ERROR);

                return ProcessDataResult.UNKNOWN_MESSAGE;
            } else {
                logger.warn("{} - Income unknown message : {} bytes in buffer. Triyng to find correct message. ",
                        this.toString(), inBuffer.limit());
                try {
                    // delete first byte
                    inBuffer.rewind();
                    inBuffer.get();
                    inBuffer.compact();
                } catch (ModeChangeException e) {
                    logger.error(e.getMessage());
                }

                return ProcessDataResult.UNKNOWN_MESSAGE_REWIND;
            }

        } catch (ModeChangeException ex) {
            logger.error("{} - Bad operation: {}", this.toString(), ex.getMessage());

            inBuffer.initialize();

            // set state
            devicesStates.setDeviceState(this.deviceName, receivedID, DeviceStates.DATA_ERROR);

            return ProcessDataResult.PROCESSING_ERROR;

        } catch (Exception ex) {
            logger.error("{} - Reading incoming data error: {}", this.toString(), ex.getMessage());
            // print details
            printCommunicationInfo(inBuffer, lastSentData);
            inBuffer.clear();

            // set state
            devicesStates.setDeviceState(this.deviceName, receivedID, DeviceStates.DATA_ERROR);

            return ProcessDataResult.PROCESSING_ERROR;
        }
    }

    /**
     * @param itemData
     * @throws Exception
     *
     */
    protected void processDecompiledData(SimpleBinaryMessage itemData, SimpleBinaryItemData lastSentData)
            throws Exception {
        // message type control
        if (itemData instanceof SimpleBinaryItem) {
            if (logger.isTraceEnabled()) {
                logger.trace("{} - Incoming data", toString());
            }

            int deviceId = ((SimpleBinaryItem) itemData).getDeviceId();

            // set state
            devicesStates.setDeviceState(this.deviceName, deviceId, DeviceStates.CONNECTED);

            State state = ((SimpleBinaryItem) itemData).getState();

            if (state == null) {
                logger.warn("{} - Device {} Incoming data - Unknown item state", toString(), deviceId);
            } else {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} - Device {} Incoming data - item:{}/state:{}", toString(), deviceId,
                            ((SimpleBinaryItem) itemData).name, state);
                }

                if (eventPublisher != null) {
                    eventPublisher.postUpdate(((SimpleBinaryItem) itemData).name, state);
                }
            }

            if (lastSentData != null) {
                // if data income on request "check new data" send it again for new check
                if (lastSentData.getMessageType() == SimpleBinaryMessageType.CHECKNEWDATA) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("{} - Device {} Repeat CHECKNEWDATA command", toString(), deviceId);
                    }
                    // send new request immediately and without "force all data as new"
                    offerNewDataCheckPriority(lastSentData.deviceId);
                }
            }
        } else if (itemData instanceof SimpleBinaryMessage) {
            if (logger.isDebugEnabled()) {
                logger.debug("{} - Device {} Incoming control message", toString(), itemData.getDeviceId());
            }

            if (logger.isDebugEnabled() && lastSentData != null && lastSentData.getItemAddress() >= 0) {
                logger.debug("{} - Device {} ItemAddress={}", toString(), itemData.getDeviceId(),
                        lastSentData.getItemAddress());
            }

            // set state
            devicesStates.setDeviceState(this.deviceName, itemData.getDeviceId(), DeviceStates.CONNECTED);

            if (itemData.getMessageType() == SimpleBinaryMessageType.OK) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} - Device {} for item {} report data OK", toString(), itemData.getDeviceId(),
                            (lastSentData != null && lastSentData.getItemAddress() >= 0) ? lastSentData.getItemAddress()
                                    : "???");
                }

            } else if (itemData.getMessageType() == SimpleBinaryMessageType.RESEND) {
                if (lastSentData != null) {
                    logger.info("{} - Device {} for item {} request resend data", toString(), itemData.getDeviceId(),
                            lastSentData.getItemAddress());

                    resendData(lastSentData);
                } else {
                    logger.warn("{} - Device {} request resend data. But nothing to resend.", toString(),
                            itemData.getDeviceId());
                }
            } else if (itemData.getMessageType() == SimpleBinaryMessageType.NODATA) {
                if (logger.isDebugEnabled()) {
                    logger.debug("{} - Device {} answer no new data", toString(), itemData.getDeviceId());
                }
            } else if (itemData.getMessageType() == SimpleBinaryMessageType.UNKNOWN_DATA) {
                logger.warn("{} - Device {} report unknown data", toString(), itemData.getDeviceId());
                // last data out
                if (logger.isDebugEnabled() && lastSentData != null) {
                    logger.debug("{} - Last sent data: {}", toString(),
                            SimpleBinaryProtocol.arrayToString(lastSentData.getData(), lastSentData.getData().length));
                }
                // set state
                devicesStates.setDeviceState(this.deviceName, itemData.getDeviceId(), DeviceStates.DATA_ERROR);
            } else if (itemData.getMessageType() == SimpleBinaryMessageType.UNKNOWN_ADDRESS) {
                logger.warn("{} - Device {} for item {} report unknown address", toString(), itemData.getDeviceId(),
                        (lastSentData != null && lastSentData.getItemAddress() >= 0) ? lastSentData.getItemAddress()
                                : "???");
                // last data out
                if (logger.isDebugEnabled() && lastSentData != null) {
                    logger.debug("{} - Last sent data: {}", toString(),
                            SimpleBinaryProtocol.arrayToString(lastSentData.getData(), lastSentData.getData().length));
                }
                // set state
                devicesStates.setDeviceState(this.deviceName, itemData.getDeviceId(), DeviceStates.DATA_ERROR);
            } else if (itemData.getMessageType() == SimpleBinaryMessageType.SAVING_ERROR) {
                logger.warn("{} - Device {} for item {} report saving data error", toString(), itemData.getDeviceId(),
                        (lastSentData != null && lastSentData.getItemAddress() >= 0) ? lastSentData.getItemAddress()
                                : "???");
                // last data out
                if (logger.isDebugEnabled() && lastSentData != null) {
                    logger.debug("{} - Last sent data: {}", toString(),
                            SimpleBinaryProtocol.arrayToString(lastSentData.getData(), lastSentData.getData().length));
                }
                // set state
                devicesStates.setDeviceState(this.deviceName, itemData.getDeviceId(), DeviceStates.DATA_ERROR);
            } else if (itemData.getMessageType() == SimpleBinaryMessageType.HI) {
                if (logger.isDebugEnabled() && lastSentData != null) {
                    logger.debug("{} - Device {} says Hi", toString(), itemData.getDeviceId());
                }
            } else {
                logger.warn("{} - Device {} - Unsupported message type received: {}", toString(),
                        itemData.getDeviceId(), itemData.getMessageType().toString());

                // set state
                devicesStates.setDeviceState(this.deviceName, itemData.getDeviceId(), DeviceStates.DATA_ERROR);
            }
        }
    }

    @Override
    public String toString() {
        return "DeviceID " + deviceID;
    }
}
