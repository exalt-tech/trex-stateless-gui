/**
 * *****************************************************************************
 * Copyright (c) 2016
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************
 */
package com.exalttech.trex.core;

import com.exalttech.trex.remote.exceptions.IncorrectRPCMethodException;
import com.exalttech.trex.remote.exceptions.InvalidRPCResponseException;
import com.exalttech.trex.remote.models.common.RPCError;
import com.exalttech.trex.remote.models.common.RPCRequest;
import com.exalttech.trex.remote.models.params.Params;
import com.exalttech.trex.remote.models.profiles.Profile;
import com.exalttech.trex.ui.models.Port;
import com.exalttech.trex.ui.views.logs.LogType;
import com.exalttech.trex.ui.views.logs.LogsController;
import com.exalttech.trex.util.CompressionUtils;
import com.exalttech.trex.util.Constants;
import com.exalttech.trex.util.Util;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.zip.DataFormatException;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.concurrent.Task;
import javax.xml.bind.DatatypeConverter;
import org.apache.log4j.Logger;
import org.zeromq.ZMQ;

/**
 *
 * @author GeorgeKh
 */
public class ConnectionManager {

    private static final Logger LOG = Logger.getLogger(ConnectionManager.class.getName());
    private static ConnectionManager instance = null;
    private static StringProperty logProperty = new SimpleStringProperty();
    private final static String ASYNC_PASS_STATUS = "Pass";

    private final String MAGIC_STRING = "ABE85CEA";

    /**
     *
     * @return
     */
    public static ConnectionManager getInstance() {
        if (instance == null) {
            instance = new ConnectionManager();
        }
        return instance;
    }

    private String apiH;
    private String clientName;

    private String ip;
    private String rpcPort;
    private String asyncPort;
    private boolean connected = false;

    private ZMQ.Socket requester = null;
    private boolean isReadOnly;
    private Task task;
    ZMQ.Context context;
    private String connectionString;

    /**
     *
     */
    protected ConnectionManager() {
        bindLogProperty();
    }

    /**
     *
     * @return
     */
    public String getClientName() {
        return clientName;
    }

    /**
     *
     * @param clientName
     */
    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    /**
     *
     * @return
     */
    public String getIp() {
        return ip;
    }

    /**
     *
     * @param ip
     */
    public void setIp(String ip) {
        this.ip = ip;
    }

    /**
     *
     * @return
     */
    public boolean isIsReadOnly() {
        return isReadOnly;
    }

    /**
     *
     * @param isReadOnly
     */
    public void setIsReadOnly(boolean isReadOnly) {
        this.isReadOnly = isReadOnly;
    }

    /**
     *
     * @param ip
     * @param rpcPort
     * @param asyncPort
     * @param clientName
     * @param isReadOnly
     * @return
     */
    public boolean initializeConnection(String ip, String rpcPort, String asyncPort, String clientName, boolean isReadOnly) {
        this.ip = ip;
        this.rpcPort = rpcPort;
        this.asyncPort = asyncPort;
        this.clientName = clientName;
        this.isReadOnly = isReadOnly;

        // connect to zmq
        return connectToZMQ();
    }

    /**
     *
     */
    private boolean connectToZMQ() {
        connectionString = "tcp://" + ip + ":" + rpcPort;
        try {
            context = ZMQ.context(1);
            setRequester(context.socket(ZMQ.REQ));
            getRequester().setReceiveTimeOut(3000);

            getRequester().connect(connectionString);
            LogsController.getInstance().appendText(LogType.INFO, "Connecting to TRex:" + connectionString);

        } catch (Exception ex) {
            LOG.error("Invalid hostname", ex);
            return false;
        }
        return true;
    }

    /**
     *
     * @param isAsync
     * @return
     */
    public boolean testConnection(boolean isAsync) {
        if (isAsync) {
            return !Util.isNullOrEmpty(getAsyncResponse());
        } else if (sendRequest("ping") == null) {
            LOG.error("Server is unreachable");
            return false;
        }
        return true;
    }

    /**
     *
     * Send request without Parameters
     *
     * @param cmd
     * @return
     */
    public String sendRequest(String cmd) {
        return sendRequest(cmd, null);
    }

    /**
     *
     * Send request with Parameters
     *
     * @param cmd
     * @param parameters
     * @return
     */
    public String sendRequest(String cmd, String parameters) {

        try {
            String param = parameters;
            ObjectMapper mapper = new ObjectMapper();
            if (parameters != null) {
                String apiHParam = "\"api_h\": \"" + apiH + "\"";
                if ("".equals(parameters)) {
                    param = "{\"api_h\": \"" + apiH + "\"}";
                } else if ("api_sync".equals(cmd)) {
                    param = "{" + parameters + "}";
                } else {
                    param = "{" + apiHParam + " , " + parameters + "}";
                }

            }
            String request = "{   \"id\" : \"aggogxls\",   \"jsonrpc\" : \"2.0\",   \"method\" : \"" + cmd + "\",   \"params\" :" + param + " }";
            LOG.trace("Sending request \n" + Util.toPrettyFormat(request));
            logProperty.setValue("Sending request " + Util.toPrettyFormat(request));
            byte[] reply = getServerRPCResponse(request);

            if (reply != null) {
                String serversResponse = new String(reply, "UTF-8");
                LOG.trace("Received Server response \n" + Util.toPrettyFormat(serversResponse));
                logProperty.setValue("Received Server response " + Util.toPrettyFormat(serversResponse));
                if (serversResponse.contains("error")) {
                    try {
                        String rpcResponse = Util.removeFirstBrackets(serversResponse);
                        RPCError rpcError = mapper.readValue(rpcResponse, RPCError.class);
                        LOG.error(rpcError.getError().getSpecificErr());
                        LogsController.getInstance().appendText(LogType.ERROR, rpcError.getError().getSpecificErr());

                    } catch (IOException ex) {
                        LOG.warn("Error parsing response", ex);
                    }
                }
                return serversResponse;
            }
        } catch (UnsupportedEncodingException ex) {
            LOG.error("Error while sending request", ex);
        }
        return null;

    }

    /**
     *
     * @param method
     * @param params
     * @return
     * @throws JsonProcessingException
     * @throws UnsupportedEncodingException
     * @throws InvalidRPCResponseException
     * @throws IncorrectRPCMethodException
     */
    public String sendRPCRequest(String method, Params params) throws JsonProcessingException, UnsupportedEncodingException, InvalidRPCResponseException, IncorrectRPCMethodException {
        RPCRequest rpcRequest = new RPCRequest();
        ObjectMapper mapper = new ObjectMapper();
        rpcRequest.setId(Util.getRandomID(Constants.RPC_REQUEST_ID_LENGTH));
        rpcRequest.setMethod(method);
        if (params != null) {
            rpcRequest.setParams(params);
        }
        String jsonRequestString = mapper.writeValueAsString(rpcRequest);
        jsonRequestString = Util.tuneJSONParams(jsonRequestString, params, apiH);
        LOG.trace("Sending request \n" + Util.toPrettyFormat(jsonRequestString));
        logProperty.setValue("Sending request " + Util.toPrettyFormat(jsonRequestString));
        byte[] serverResponse = getServerRPCResponse(jsonRequestString);
        return handleResponse(serverResponse, true);
    }

    /**
     *
     * @param profilesList
     * @return
     * @throws JsonProcessingException
     * @throws UnsupportedEncodingException
     * @throws IncorrectRPCMethodException
     * @throws InvalidRPCResponseException
     */
    public String sendAddStreamRequest(Profile[] profilesList) throws JsonProcessingException, UnsupportedEncodingException, IncorrectRPCMethodException, InvalidRPCResponseException {
        List<String> addStreamCommandList = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        RPCRequest rpcRequest = new RPCRequest();
        String jsonRequestString;
        for (int i = 0; i < profilesList.length; i++) {

            rpcRequest.setId(Util.getRandomID(Constants.RPC_REQUEST_ID_LENGTH));
            rpcRequest.setMethod(Constants.ADD_STREAM_METHOD);
            rpcRequest.setParams(profilesList[i]);

            jsonRequestString = mapper.writeValueAsString(rpcRequest);
            jsonRequestString = Util.tuneJSONParams(jsonRequestString, profilesList[i], apiH);
            addStreamCommandList.add(jsonRequestString);

        }
        String requestCommand = Util.toPrettyFormat(addStreamCommandList.toString());
        LOG.info(requestCommand);
        logProperty.setValue("Sending request " + requestCommand);
        byte[] serverResponse = getServerRPCResponse(addStreamCommandList.toString());
        return handleResponse(serverResponse, false);
    }

    /**
     * Send request for port status
     *
     * @param portList
     * @return
     * @throws JsonProcessingException
     * @throws UnsupportedEncodingException
     * @throws IncorrectRPCMethodException
     * @throws InvalidRPCResponseException
     */
    public String sendPortStatusRequest(List<Port> portList) throws JsonProcessingException, UnsupportedEncodingException, IncorrectRPCMethodException, InvalidRPCResponseException {
        List<String> addStreamCommandList = new ArrayList<>();
        ObjectMapper mapper = new ObjectMapper();
        RPCRequest rpcRequest = new RPCRequest();
        String jsonRequestString;
        for (Port port : portList) {
            rpcRequest.setId(Util.getRandomID(Constants.RPC_REQUEST_ID_LENGTH));
            rpcRequest.setMethod(Constants.PORT_STATUS_METHOD);
            rpcRequest.setParams(port.getPortParam());

            jsonRequestString = mapper.writeValueAsString(rpcRequest);
            jsonRequestString = Util.tuneJSONParams(jsonRequestString, port.getPortParam(), apiH);
            addStreamCommandList.add(jsonRequestString);

        }
        String requestCommand = Util.toPrettyFormat(addStreamCommandList.toString());
        LOG.info("Send port status request \n " + requestCommand);
        byte[] serverResponse = getServerRPCResponse(addStreamCommandList.toString());

        return handleResponse(serverResponse, false);
    }

    /**
     * Handle server response
     *
     * @param serverResponse
     * @param writeToLog
     * @return
     * @throws UnsupportedEncodingException
     * @throws IncorrectRPCMethodException
     * @throws InvalidRPCResponseException
     */
    private String handleResponse(byte[] serverResponse, boolean writeToLog) throws UnsupportedEncodingException, IncorrectRPCMethodException, InvalidRPCResponseException {
        if (serverResponse != null) {
            String rpcResponse = new String(serverResponse, "UTF-8");
            LOG.trace("Received Server response \n" + Util.toPrettyFormat(rpcResponse));
            if (writeToLog) {
                logProperty.setValue("Received Server response " + Util.toPrettyFormat(rpcResponse));
            }
            if (rpcResponse.contains("error")) {
                try {
                    rpcResponse = Util.removeFirstBrackets(rpcResponse);
                    RPCError rpcError = new ObjectMapper().readValue(rpcResponse, RPCError.class);
                    LOG.error(rpcError.getError().getSpecificErr());
                    LogsController.getInstance().appendText(LogType.ERROR, rpcError.getError().getSpecificErr());
                    throw new IncorrectRPCMethodException(rpcError.getError().getSpecificErr() + "\n " + Util.toPrettyFormat(rpcResponse));
                } catch (IOException ex) {
                    LOG.warn("Error parsing response", ex);
                }
            }
            return rpcResponse;
        } else {
            throw new InvalidRPCResponseException();
        }
    }

    /**
     *
     * Async request management
     *
     * @return
     */
    public String getAsyncResponse() {

        task = new Task<Void>() {

            @Override
            protected Void call() throws Exception {
                try {
                    ZMQ.Socket subscriber = context.socket(ZMQ.SUB);
                    subscriber.setReceiveTimeOut(5000);
                    subscriber.connect("tcp://" + ip + ":" + asyncPort);
                    subscriber.subscribe(ZMQ.SUBSCRIPTION_ALL);
                    String res;

                    while (!Thread.currentThread().isInterrupted()) {
                        try {
                            res = getDecompressedString(subscriber.recv());
                            if (res != null) {
                                handleAsyncResponse(res);
                                res = null;
                            }
                        } catch (Exception ex) {
                            LOG.error("Possible error while reading the Async request", ex);
                        }
                    }
                } catch (Exception ex) {
                    LOG.error("Possible error while reading the Async request", ex);
                }
                return null;
            }

        };
        new Thread(task).start();
        return ASYNC_PASS_STATUS;
    }

    /**
     * Decompressed response
     *
     * @param data
     * @return
     */
    private String getDecompressedString(byte[] data) {
        // if the length is larger than 8 bytes
        if (data.length > 8) {

            // Take the first 4 bytes
            byte[] magicBytes = Arrays.copyOfRange(data, 0, 4);

            String magicString = DatatypeConverter.printHexBinary(magicBytes);

            /* check MAGIC in the first 4 bytes in case we have it, it is compressed */
            if (magicString.equals(MAGIC_STRING)) {

                // Skip another  4 bytes containing the uncompressed size of the  message
                byte[] compressedData = Arrays.copyOfRange(data, 8, data.length);

                try {
                    return new String(CompressionUtils.decompress(compressedData));
                } catch (IOException | DataFormatException ex) {
                    LOG.error("Failed to decompress data ", ex);
                }

            }

        }
        return new String(data);
    }

    /**
     * Handle async response
     *
     * @param res
     */
    private void handleAsyncResponse(String res) {
        if (res.contains(Constants.TREX_GLOBAL_TAG)) {
            AsyncResponseManager.getInstance().setTrexGlobalResponse(res);
        } else if (res.contains(Constants.TREX_EVENT)) {
            AsyncResponseManager.getInstance().setTRexEventValue(res);
        } else if (res.contains(Constants.TREX_LATENCY)) {
            AsyncResponseManager.getInstance().setTrexLatencyProperty(res);
        }
    }

    /**
     *
     */
    public void disconnectSubscriber() {
        try {
            task.cancel(true);
        } catch (Exception ex) {
            // reclose if task still running
            if (task.isRunning()) {
                task.cancel();
            }
            LOG.error("Possible error while closing the Async request", ex);
        }
    }

    /**
     *
     */
    public void disconnectRequester() {
        setConnected(false);
        requester.disconnect(connectionString);
        requester.close();
    }

    /**
     *
     * @return
     */
    public String getIPAddress() {
        return ip;
    }

    /**
     *
     * @return
     */
    public String getAsyncPort() {
        return asyncPort;
    }

    /**
     *
     * @return
     */
    public String getRpcPort() {
        return rpcPort;
    }

    /**
     *
     * @return
     */
    public boolean isConnected() {
        return this.connected;
    }

    /**
     *
     * @param connected
     */
    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    /**
     * @return the requester
     */
    public ZMQ.Socket getRequester() {
        return requester;
    }

    /**
     * @param requester the requester to set
     */
    public void setRequester(ZMQ.Socket requester) {
        this.requester = requester;
    }

    /**
     *
     * @param request
     * @return
     */
    private byte[] getServerRPCResponse(String request) {
        synchronized (this) {
            try {
                // prepare compression header
                ByteBuffer headerByteBuffer = ByteBuffer.allocate(8);
                headerByteBuffer.put((byte) 0xAB);
                headerByteBuffer.put((byte) 0xE8);
                headerByteBuffer.put((byte) 0x5C);
                headerByteBuffer.put((byte) 0xEA);
                headerByteBuffer.putInt(request.length());
                byte[] headerBytes = headerByteBuffer.array();
                // compress request
                byte[] compressedRequest = CompressionUtils.compress(request.getBytes());
                byte[] finalRequest = concatByteArrays(headerBytes, compressedRequest);
                getRequester().send(finalRequest);
                byte[] serverResponse = getRequester().recv(0);
                // decompressed response
                if (serverResponse != null) {
                    return getDecompressedString(serverResponse).getBytes();
                }
            } catch (IOException ex) {
                LOG.error("Error sending request", ex);
                return null;
            }
            return null;
        }
    }

    private byte[] concatByteArrays(byte[] firstDataArray, byte[] secondDataArray) {
        byte[] concatedDataArray = new byte[firstDataArray.length + secondDataArray.length];
        System.arraycopy(firstDataArray, 0, concatedDataArray, 0, firstDataArray.length);
        System.arraycopy(secondDataArray, 0, concatedDataArray, firstDataArray.length, secondDataArray.length);
        return concatedDataArray;
    }

    /**
     * Add listener to log property
     */
    private void bindLogProperty() {
        logProperty.addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> observable, String oldValue, String newValue) {
                LogsController.getInstance().appendConsoleViewText(newValue);
            }
        });
    }

    void setApiH(String apiH) {
        this.apiH = apiH;
    }

    /**
     *
     * @return
     */
    public String getApiH() {
        return apiH;
    }

}
