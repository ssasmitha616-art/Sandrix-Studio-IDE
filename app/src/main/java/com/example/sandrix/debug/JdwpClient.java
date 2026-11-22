package com.example.sandrix.debug;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-Java binary JDWP (Java Debug Wire Protocol) TCP client for Sandrix Studio.
 *
 * <p>Implements the official JDWP handshake, command/reply packet correlation, VM ID sizes,
 * class/method/thread enumeration, line-table resolution, breakpoint &amp; step event requests,
 * thread suspend/resume, stack frame inspection, and string/primitive variable decoding.</p>
 */
public final class JdwpClient implements AutoCloseable {

    private static final byte[] HANDSHAKE = "JDWP-Handshake".getBytes(StandardCharsets.US_ASCII);
    private static final byte FLAG_REPLY = (byte) 0x80;

    // JDWP Command Sets & Commands
    private static final int CS_VIRTUAL_MACHINE = 1;
    private static final int CMD_VM_VERSION = 1;
    private static final int CMD_VM_ALL_CLASSES = 3;
    private static final int CMD_VM_ALL_THREADS = 4;
    private static final int CMD_VM_IDSIZES = 7;
    private static final int CMD_VM_RESUME = 9;

    private static final int CS_REFERENCE_TYPE = 2;
    private static final int CMD_RT_METHODS = 5;

    private static final int CS_METHOD = 6;
    private static final int CMD_METHOD_LINE_TABLE = 1;
    private static final int CMD_METHOD_VARIABLE_TABLE = 2;

    private static final int CS_OBJECT_REF = 9;
    private static final int CMD_OBJ_REF_TYPE = 1;

    private static final int CS_STRING_REF = 10;
    private static final int CMD_STR_VALUE = 1;

    private static final int CS_THREAD_REF = 11;
    private static final int CMD_THREAD_NAME = 1;
    private static final int CMD_THREAD_SUSPEND = 2;
    private static final int CMD_THREAD_RESUME = 3;
    private static final int CMD_THREAD_FRAMES = 6;

    private static final int CS_EVENT_REQUEST = 15;
    private static final int CMD_ER_SET = 1;
    private static final int CMD_ER_CLEAR = 2;

    private static final int CS_STACK_FRAME = 16;
    private static final int CMD_SF_GET_VALUES = 1;
    private static final int CMD_SF_SET_VALUES = 2;

    private static final int CS_EVENT = 64;
    private static final int CMD_EVENT_COMPOSITE = 100;

    // Event Kinds
    public static final byte EVENT_SINGLE_STEP = 1;
    public static final byte EVENT_BREAKPOINT = 2;
    public static final byte EVENT_VM_DEATH = 99;

    // Step depth / size
    public static final int STEP_SIZE_LINE = 1;
    public static final int STEP_DEPTH_INTO = 0;
    public static final int STEP_DEPTH_OVER = 1;
    public static final int STEP_DEPTH_OUT = 2;

    // Suspend policies
    public static final byte SUSPEND_ALL = 2;

    public interface EventListener {
        void onBreakpointOrStep(byte eventKind, int requestId, long threadId, long classId, long methodId, long codeIndex);
        void onDisconnected(String reason);
    }

    public static final class IdSizes {
        public final int fieldIdSize;
        public final int methodIdSize;
        public final int objectIdSize;
        public final int referenceTypeIdSize;
        public final int frameIdSize;

        public IdSizes(int fieldIdSize, int methodIdSize, int objectIdSize, int referenceTypeIdSize, int frameIdSize) {
            this.fieldIdSize = fieldIdSize;
            this.methodIdSize = methodIdSize;
            this.objectIdSize = objectIdSize;
            this.referenceTypeIdSize = referenceTypeIdSize;
            this.frameIdSize = frameIdSize;
        }
    }

    public static final class ClassInfo {
        public final byte refTypeTag;
        public final long typeId;
        public final String signature;

        public ClassInfo(byte refTypeTag, long typeId, String signature) {
            this.refTypeTag = refTypeTag;
            this.typeId = typeId;
            this.signature = signature;
        }
    }

    public static final class MethodInfo {
        public final long methodId;
        public final String name;
        public final String signature;

        public MethodInfo(long methodId, String name, String signature) {
            this.methodId = methodId;
            this.name = name;
            this.signature = signature;
        }
    }

    public static final class LineEntry {
        public final long codeIndex;
        public final int lineNumber;

        public LineEntry(long codeIndex, int lineNumber) {
            this.codeIndex = codeIndex;
            this.lineNumber = lineNumber;
        }
    }

    public static final class VariableSlot {
        public final long codeIndex;
        public final String name;
        public final String signature;
        public final int length;
        public final int slot;

        public VariableSlot(long codeIndex, String name, String signature, int length, int slot) {
            this.codeIndex = codeIndex;
            this.name = name;
            this.signature = signature;
            this.length = length;
            this.slot = slot;
        }
    }

    public static final class RemoteFrame {
        public final long frameId;
        public final byte refTypeTag;
        public final long classId;
        public final long methodId;
        public final long codeIndex;

        public RemoteFrame(long frameId, byte refTypeTag, long classId, long methodId, long codeIndex) {
            this.frameId = frameId;
            this.refTypeTag = refTypeTag;
            this.classId = classId;
            this.methodId = methodId;
            this.codeIndex = codeIndex;
        }
    }

    public static final class Packet {
        public final int id;
        public final byte flags;
        public final int commandSet;
        public final int command;
        public final int errorCode;
        public final byte[] data;

        public Packet(int id, byte flags, int commandSet, int command, int errorCode, byte[] data) {
            this.id = id;
            this.flags = flags;
            this.commandSet = commandSet;
            this.command = command;
            this.errorCode = errorCode;
            this.data = data != null ? data : new byte[0];
        }

        public boolean isReply() {
            return (flags & FLAG_REPLY) != 0;
        }

        public byte[] toWireBytes() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream(11 + data.length);
            DataOutputStream dos = new DataOutputStream(out);
            dos.writeInt(11 + data.length);
            dos.writeInt(id);
            dos.writeByte(flags);
            if (isReply()) {
                dos.writeShort(errorCode);
            } else {
                dos.writeByte(commandSet);
                dos.writeByte(command);
            }
            dos.write(data);
            dos.flush();
            return out.toByteArray();
        }
    }

    private final AtomicInteger nextPacketId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<Packet>> pendingReplies = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    private Socket socket;
    private DataInputStream input;
    private DataOutputStream output;
    private volatile boolean connected = false;
    private IdSizes idSizes = new IdSizes(8, 8, 8, 8, 8);
    private EventListener eventListener;
    private Thread readerThread;

    public void setEventListener(EventListener listener) {
        this.eventListener = listener;
    }

    public boolean isConnected() {
        return connected && socket != null && !socket.isClosed();
    }

    public IdSizes getIdSizes() {
        return idSizes;
    }

    public void connect(String host, int port, int timeoutMs) throws IOException {
        close();
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setTcpNoDelay(true);

        DataInputStream dis = new DataInputStream(s.getInputStream());
        DataOutputStream dos = new DataOutputStream(s.getOutputStream());

        dos.write(HANDSHAKE);
        dos.flush();

        byte[] reply = new byte[HANDSHAKE.length];
        dis.readFully(reply);
        if (!Arrays.equals(HANDSHAKE, reply)) {
            s.close();
            throw new IOException("Invalid JDWP handshake response from " + host + ":" + port);
        }

        this.socket = s;
        this.input = dis;
        this.output = dos;
        this.connected = true;

        readerThread = new Thread(this::readLoop, "SandrixJdwpReader");
        readerThread.setDaemon(true);
        readerThread.start();

        this.idSizes = fetchIdSizes();
    }

    public String fetchVmVersion() throws Exception {
        Packet reply = sendCommand(CS_VIRTUAL_MACHINE, CMD_VM_VERSION, new byte[0]);
        ByteBuffer buf = ByteBuffer.wrap(reply.data);
        String description = readJdwpString(buf);
        int major = buf.getInt();
        int minor = buf.getInt();
        String vmVersion = readJdwpString(buf);
        String vmName = readJdwpString(buf);
        return vmName + " (" + vmVersion + ", JDWP " + major + "." + minor + ") - " + description;
    }

    public IdSizes fetchIdSizes() throws IOException {
        try {
            Packet reply = sendCommand(CS_VIRTUAL_MACHINE, CMD_VM_IDSIZES, new byte[0]);
            ByteBuffer buf = ByteBuffer.wrap(reply.data);
            return new IdSizes(
                    buf.getInt(),
                    buf.getInt(),
                    buf.getInt(),
                    buf.getInt(),
                    buf.getInt());
        } catch (Exception e) {
            throw new IOException("Failed to query JDWP ID sizes: " + e.getMessage(), e);
        }
    }

    public List<Long> fetchAllThreads() throws Exception {
        Packet reply = sendCommand(CS_VIRTUAL_MACHINE, CMD_VM_ALL_THREADS, new byte[0]);
        ByteBuffer buf = ByteBuffer.wrap(reply.data);
        int count = buf.getInt();
        List<Long> threads = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            threads.add(readSizedId(buf, idSizes.objectIdSize));
        }
        return threads;
    }

    public String fetchThreadName(long threadId) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeSizedId(payload, threadId, idSizes.objectIdSize);
        Packet reply = sendCommand(CS_THREAD_REF, CMD_THREAD_NAME, payload.toByteArray());
        return readJdwpString(ByteBuffer.wrap(reply.data));
    }

    public List<ClassInfo> fetchAllClasses() throws Exception {
        Packet reply = sendCommand(CS_VIRTUAL_MACHINE, CMD_VM_ALL_CLASSES, new byte[0]);
        ByteBuffer buf = ByteBuffer.wrap(reply.data);
        int count = buf.getInt();
        List<ClassInfo> list = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            byte tag = buf.get();
            long id = readSizedId(buf, idSizes.referenceTypeIdSize);
            String sig = readJdwpString(buf);
            buf.getInt(); // status
            list.add(new ClassInfo(tag, id, sig));
        }
        return list;
    }

    public List<MethodInfo> fetchMethods(long classId) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeSizedId(payload, classId, idSizes.referenceTypeIdSize);
        Packet reply = sendCommand(CS_REFERENCE_TYPE, CMD_RT_METHODS, payload.toByteArray());
        ByteBuffer buf = ByteBuffer.wrap(reply.data);
        int count = buf.getInt();
        List<MethodInfo> list = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            long methodId = readSizedId(buf, idSizes.methodIdSize);
            String name = readJdwpString(buf);
            String sig = readJdwpString(buf);
            buf.getInt(); // modBits
            list.add(new MethodInfo(methodId, name, sig));
        }
        return list;
    }

    public List<LineEntry> fetchLineTable(long classId, long methodId) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeSizedId(payload, classId, idSizes.referenceTypeIdSize);
        writeSizedId(payload, methodId, idSizes.methodIdSize);
        Packet reply = sendCommand(CS_METHOD, CMD_METHOD_LINE_TABLE, payload.toByteArray());
        ByteBuffer buf = ByteBuffer.wrap(reply.data);
        buf.getLong(); // start
        buf.getLong(); // end
        int count = buf.getInt();
        List<LineEntry> entries = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            long idx = buf.getLong();
            int line = buf.getInt();
            entries.add(new LineEntry(idx, line));
        }
        return entries;
    }

    public List<VariableSlot> fetchVariableTable(long classId, long methodId) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeSizedId(payload, classId, idSizes.referenceTypeIdSize);
        writeSizedId(payload, methodId, idSizes.methodIdSize);
        Packet reply = sendCommand(CS_METHOD, CMD_METHOD_VARIABLE_TABLE, payload.toByteArray());
        if (reply.errorCode != 0) {
            return Collections.emptyList();
        }
        ByteBuffer buf = ByteBuffer.wrap(reply.data);
        buf.getInt(); // argCnt
        int slots = buf.getInt();
        List<VariableSlot> list = new ArrayList<>(Math.max(0, slots));
        for (int i = 0; i < slots; i++) {
            long codeIndex = buf.getLong();
            String name = readJdwpString(buf);
            String sig = readJdwpString(buf);
            int length = buf.getInt();
            int slot = buf.getInt();
            list.add(new VariableSlot(codeIndex, name, sig, length, slot));
        }
        return list;
    }

    public List<RemoteFrame> fetchThreadFrames(long threadId, int startFrame, int length) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeSizedId(payload, threadId, idSizes.objectIdSize);
        writeInt(payload, startFrame);
        writeInt(payload, length);
        Packet reply = sendCommand(CS_THREAD_REF, CMD_THREAD_FRAMES, payload.toByteArray());
        ByteBuffer buf = ByteBuffer.wrap(reply.data);
        int count = buf.getInt();
        List<RemoteFrame> frames = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            long frameId = readSizedId(buf, idSizes.frameIdSize);
            byte tag = buf.get();
            long cid = readSizedId(buf, idSizes.referenceTypeIdSize);
            long mid = readSizedId(buf, idSizes.methodIdSize);
            long codeIndex = buf.getLong();
            frames.add(new RemoteFrame(frameId, tag, cid, mid, codeIndex));
        }
        return frames;
    }

    public int setLocationBreakpoint(byte refTypeTag, long classId, long methodId, long codeIndex) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(EVENT_BREAKPOINT);
        payload.write(SUSPEND_ALL);
        writeInt(payload, 1); // 1 modifier: LocationOnly (7)
        payload.write(7);
        payload.write(refTypeTag);
        writeSizedId(payload, classId, idSizes.referenceTypeIdSize);
        writeSizedId(payload, methodId, idSizes.methodIdSize);
        ByteBuffer idxBuf = ByteBuffer.allocate(8).putLong(codeIndex);
        payload.write(idxBuf.array());

        Packet reply = sendCommand(CS_EVENT_REQUEST, CMD_ER_SET, payload.toByteArray());
        return ByteBuffer.wrap(reply.data).getInt();
    }

    public int setStepRequest(long threadId, int stepDepth) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(EVENT_SINGLE_STEP);
        payload.write(SUSPEND_ALL);
        writeInt(payload, 1); // 1 modifier: Step (10)
        payload.write(10);
        writeSizedId(payload, threadId, idSizes.objectIdSize);
        writeInt(payload, STEP_SIZE_LINE);
        writeInt(payload, stepDepth);
        Packet reply = sendCommand(CS_EVENT_REQUEST, CMD_ER_SET, payload.toByteArray());
        return ByteBuffer.wrap(reply.data).getInt();
    }

    public void clearEventRequest(byte eventKind, int requestId) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(eventKind);
        writeInt(payload, requestId);
        sendCommand(CS_EVENT_REQUEST, CMD_ER_CLEAR, payload.toByteArray());
    }

    public List<String> fetchFrameValues(long threadId, long frameId, List<VariableSlot> slots) throws Exception {
        if (slots == null || slots.isEmpty()) return Collections.emptyList();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeSizedId(payload, threadId, idSizes.objectIdSize);
        writeSizedId(payload, frameId, idSizes.frameIdSize);
        writeInt(payload, slots.size());
        for (VariableSlot slot : slots) {
            writeInt(payload, slot.slot);
            byte sigByte = slot.signature != null && !slot.signature.isEmpty()
                    ? (byte) slot.signature.charAt(0)
                    : (byte) 'L';
            payload.write(sigByte);
        }
        Packet reply = sendCommand(CS_STACK_FRAME, CMD_SF_GET_VALUES, payload.toByteArray());
        if (reply.errorCode != 0) {
            return Collections.emptyList();
        }
        ByteBuffer buf = ByteBuffer.wrap(reply.data);
        int count = buf.getInt();
        List<String> values = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            values.add(readTaggedValueAsString(buf));
        }
        return values;
    }

    public String fetchStringValue(long stringObjId) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeSizedId(payload, stringObjId, idSizes.objectIdSize);
        Packet reply = sendCommand(CS_STRING_REF, CMD_STR_VALUE, payload.toByteArray());
        if (reply.errorCode != 0) return "<String@" + stringObjId + ">";
        return readJdwpString(ByteBuffer.wrap(reply.data));
    }

    public void suspendThread(long threadId) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeSizedId(payload, threadId, idSizes.objectIdSize);
        sendCommand(CS_THREAD_REF, CMD_THREAD_SUSPEND, payload.toByteArray());
    }

    public void resumeThread(long threadId) throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeSizedId(payload, threadId, idSizes.objectIdSize);
        sendCommand(CS_THREAD_REF, CMD_THREAD_RESUME, payload.toByteArray());
    }

    public void resumeVm() throws Exception {
        sendCommand(CS_VIRTUAL_MACHINE, CMD_VM_RESUME, new byte[0]);
    }

    public Packet sendCommand(int commandSet, int command, byte[] data) throws Exception {
        if (!isConnected()) {
            throw new IOException("JDWP client is not connected.");
        }
        int id = nextPacketId.getAndIncrement();
        Packet pkt = new Packet(id, (byte) 0, commandSet, command, 0, data);
        CompletableFuture<Packet> future = new CompletableFuture<>();
        pendingReplies.put(id, future);
        byte[] wire = pkt.toWireBytes();
        synchronized (writeLock) {
            output.write(wire);
            output.flush();
        }
        try {
            return future.get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingReplies.remove(id);
            throw e;
        }
    }

    private void readLoop() {
        try {
            while (connected && input != null) {
                int len = input.readInt();
                if (len < 11 || len > 16 * 1024 * 1024) {
                    throw new IOException("Invalid JDWP packet length: " + len);
                }
                int id = input.readInt();
                byte flags = input.readByte();
                int b1 = input.readUnsignedByte();
                int b2 = input.readUnsignedByte();
                byte[] payload = new byte[len - 11];
                input.readFully(payload);

                if ((flags & FLAG_REPLY) != 0) {
                    int errCode = ((b1 & 0xFF) << 8) | (b2 & 0xFF);
                    Packet reply = new Packet(id, flags, 0, 0, errCode, payload);
                    CompletableFuture<Packet> future = pendingReplies.remove(id);
                    if (future != null) {
                        future.complete(reply);
                    }
                } else {
                    if (b1 == CS_EVENT && b2 == CMD_EVENT_COMPOSITE) {
                        dispatchCompositeEvent(payload);
                    }
                }
            }
        } catch (EOFException ignored) {
            handleDisconnect("JDWP connection closed by target VM");
        } catch (Exception e) {
            if (connected) {
                handleDisconnect("JDWP disconnected: " + e.getMessage());
            }
        }
    }

    private void dispatchCompositeEvent(byte[] payload) {
        if (payload == null || payload.length < 5) return;
        ByteBuffer buf = ByteBuffer.wrap(payload);
        buf.get(); // suspendPolicy
        int count = buf.getInt();
        for (int i = 0; i < count && buf.hasRemaining(); i++) {
            byte kind = buf.get();
            if (kind == EVENT_SINGLE_STEP || kind == EVENT_BREAKPOINT) {
                int reqId = buf.getInt();
                long threadId = readSizedId(buf, idSizes.objectIdSize);
                buf.get(); // typeTag
                long cid = readSizedId(buf, idSizes.referenceTypeIdSize);
                long mid = readSizedId(buf, idSizes.methodIdSize);
                long idx = buf.getLong();
                if (eventListener != null) {
                    eventListener.onBreakpointOrStep(kind, reqId, threadId, cid, mid, idx);
                }
            } else if (kind == EVENT_VM_DEATH) {
                if (buf.remaining() >= 4) buf.getInt();
                handleDisconnect("Target VM terminated (VM_DEATH)");
                return;
            } else {
                break;
            }
        }
    }

    private void handleDisconnect(String reason) {
        connected = false;
        for (CompletableFuture<Packet> f : pendingReplies.values()) {
            f.completeExceptionally(new IOException(reason));
        }
        pendingReplies.clear();
        if (eventListener != null) {
            eventListener.onDisconnected(reason);
        }
    }

    private String readTaggedValueAsString(ByteBuffer buf) {
        if (!buf.hasRemaining()) return "?";
        byte tag = buf.get();
        switch (tag) {
            case 'B': return String.valueOf(buf.get());
            case 'C': return "'" + buf.getChar() + "'";
            case 'S': return String.valueOf(buf.getShort());
            case 'I': return String.valueOf(buf.getInt());
            case 'J': return String.valueOf(buf.getLong());
            case 'F': return String.valueOf(buf.getFloat());
            case 'D': return String.valueOf(buf.getDouble());
            case 'Z': return buf.get() != 0 ? "true" : "false";
            case 'V': return "void";
            case 's': {
                long objId = readSizedId(buf, idSizes.objectIdSize);
                if (objId == 0L) return "null";
                try {
                    return "\"" + fetchStringValue(objId) + "\"";
                } catch (Exception e) {
                    return "String@" + Long.toHexString(objId);
                }
            }
            default: {
                long objId = readSizedId(buf, idSizes.objectIdSize);
                return objId == 0L ? "null" : ("Object@" + Long.toHexString(objId));
            }
        }
    }

    public static String readJdwpString(ByteBuffer buf) {
        int len = buf.getInt();
        if (len <= 0 || len > buf.remaining()) return "";
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static long readSizedId(ByteBuffer buf, int size) {
        long val = 0L;
        for (int i = 0; i < size; i++) {
            val = (val << 8) | (buf.get() & 0xFFL);
        }
        return val;
    }

    public static void writeSizedId(ByteArrayOutputStream out, long id, int size) {
        for (int i = size - 1; i >= 0; i--) {
            out.write((int) ((id >>> (8 * i)) & 0xFFL));
        }
    }

    public static void writeInt(ByteArrayOutputStream out, int val) {
        out.write((val >>> 24) & 0xFF);
        out.write((val >>> 16) & 0xFF);
        out.write((val >>> 8) & 0xFF);
        out.write(val & 0xFF);
    }

    @Override
    public void close() {
        connected = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        input = null;
        output = null;
        for (CompletableFuture<Packet> f : pendingReplies.values()) {
            f.cancel(true);
        }
        pendingReplies.clear();
    }
}
