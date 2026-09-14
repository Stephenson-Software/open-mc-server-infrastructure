package com.openmc.webapp.rcon;

import java.io.*;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class RconClient implements AutoCloseable {
    
    private static final int SERVERDATA_AUTH = 3;
    private static final int SERVERDATA_AUTH_RESPONSE = 2;
    private static final int SERVERDATA_EXECCOMMAND = 2;
    private static final int SERVERDATA_RESPONSE_VALUE = 0;
    
    private final Socket socket;
    private final DataOutputStream out;
    private final DataInputStream in;
    private int requestId = 0;
    
    public RconClient(String host, int port, String password) throws IOException {
        // A bounded connect: a Service with no endpoints (the wrapper scaled to
        // zero) drops the SYN rather than refusing it, and an unbounded connect
        // would hang the dashboard for the kernel's full retry window.
        socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(host, port), 3000);
        try {
            socket.setSoTimeout(5000);
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());

            // Authenticate
            sendPacket(SERVERDATA_AUTH, password);
            RconPacket response = receivePacket();
            if (response.getRequestId() == -1) {
                throw new IOException("Authentication failed");
            }
        } catch (IOException e) {
            // Construction failed after the socket was opened. Close it so the
            // file descriptor isn't leaked: the caller's try-with-resources
            // never runs close() on an instance whose constructor threw.
            try {
                socket.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }
    
    public String sendCommand(String command) throws IOException {
        sendPacket(SERVERDATA_EXECCOMMAND, command);
        RconPacket response = receivePacket();
        return response.getPayload();
    }
    
    private void sendPacket(int type, String payload) throws IOException {
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        int packetSize = 10 + payloadBytes.length; // 4 (id) + 4 (type) + payload + 2 (null terminators)
        
        ByteBuffer buffer = ByteBuffer.allocate(packetSize + 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(packetSize);
        buffer.putInt(++requestId);
        buffer.putInt(type);
        buffer.put(payloadBytes);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        
        out.write(buffer.array());
        out.flush();
    }
    
    private RconPacket receivePacket() throws IOException {
        int size = readInt();
        int id = readInt();
        int type = readInt();
        
        byte[] payloadBytes = new byte[size - 10];
        in.readFully(payloadBytes);
        
        // Read the two null terminators
        in.readByte();
        in.readByte();
        
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        return new RconPacket(id, type, payload);
    }
    
    private int readInt() throws IOException {
        byte[] bytes = new byte[4];
        in.readFully(bytes);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }
    
    @Override
    public void close() throws IOException {
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }
    
    private static class RconPacket {
        private final int requestId;
        private final int type;
        private final String payload;
        
        public RconPacket(int requestId, int type, String payload) {
            this.requestId = requestId;
            this.type = type;
            this.payload = payload;
        }
        
        public int getRequestId() {
            return requestId;
        }
        
        public int getType() {
            return type;
        }
        
        public String getPayload() {
            return payload;
        }
    }
}
