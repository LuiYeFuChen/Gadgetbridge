/*  Copyright (C) 2026 Gadgetbridge contributors

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.service.devices.w2;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import nodomain.freeyourgadget.gadgetbridge.Logging;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.btle.AbstractBTLEDeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.btle.TransactionBuilder;
import nodomain.freeyourgadget.gadgetbridge.service.btle.actions.SetDeviceStateAction;

/**
 * Device support for the Phicomm W2 smartwatch (MT2523 SoC).
 * <p>
 * <b>Transport model: write-then-read polling, no notifications.</b>
 * Every command is a separate {@link TransactionBuilder} containing
 * {@code write(0x2AA1, packet) -> wait(CMD_GAP_MS) -> read(0x2AA0)}. The queue executes
 * transactions one at a time, so this also gives us the strict serialization the firmware
 * requires (it has a single-channel state machine and silently drops packets when two
 * commands overlap).
 * <p>
 * The response arrives in {@link #onCharacteristicRead(BluetoothGatt,
 * BluetoothGattCharacteristic, int)} (this class is the queue's implicit GattCallback, so no
 * per-transaction {@code setCallback()} is needed). From there the {@link #bootstrapStep} is
 * advanced and the next transaction is queued. This chaining is the mechanism that turns the
 * three independent actions into a sequential request/response dialog.
 * <p>
 * Bootstrap (verified on hardware, see {@code docs/Gadgetbridge接入_实现方案.md} §5.0):
 * <ol>
 *     <li>{@code SYNC REQV} — read the firmware version, also puts the channel in EXCD mode</li>
 *     <li>{@code SYNC <unix> <tz_ms> 1.2 ANDROID} — text time announcement (answered with
 *         {@code ACKY}, but it does <b>not</b> move the watch RTC)</li>
 *     <li>frame tag 2 / cmd 0x01, 6 byte payload — the frame that actually sets the RTC
 *         (capture W6). Does not necessarily answer, so it must not block the sequence.</li>
 *     <li>frame cmd 0x01 (bind, 42 byte payload)</li>
 *     <li>frame cmd 0x03 (login, 42 byte payload)</li>
 * </ol>
 * Only after all of them have been sent does the device accept data requests; skipping any
 * of them makes every later request fail silently.
 */
public class W2DeviceSupport extends AbstractBTLEDeviceSupport {
    private static final Logger LOG = LoggerFactory.getLogger(W2DeviceSupport.class);

    /** Ordered bootstrap steps. */
    private enum Step {
        VERSION,
        TIME,
        /** RTC frame (capture W6): tag 2, cmd 0x01, 6 byte hex date/time payload. */
        CLOCK,
        BIND,
        LOGIN,
        DATA,
        DONE
    }

    private BluetoothGattCharacteristic readCharacteristic;
    private BluetoothGattCharacteristic writeCharacteristic;

    private Step bootstrapStep = Step.VERSION;
    private int emptyReadCount = 0;
    private boolean firstRead = true;
    private int dataTypeIndex = 0;

    public W2DeviceSupport() {
        super(LOG);
        addSupportedService(W2Constants.UUID_SERVICE);
    }

    @Override
    protected TransactionBuilder initializeDevice(TransactionBuilder builder) {
        LOG.info("Initializing W2");

        readCharacteristic = getCharacteristic(W2Constants.UUID_CHAR_READ);
        writeCharacteristic = getCharacteristic(W2Constants.UUID_CHAR_WRITE);

        if (readCharacteristic == null || writeCharacteristic == null) {
            // Characteristics can be missing when services were not discovered yet -- do not
            // pretend we are initialized, just request a reconnect.
            LOG.warn("W2 read/write characteristics are null, requesting reconnect");
            builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.WAITING_FOR_RECONNECT, getContext()));
            return builder;
        }

        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZING, getContext()));

        // Android 默认 MTU 是 23（单次写上限 20 字节）。W2 的首个命令只有 18 字节能写成功，
        // 但 45 字节的对时帧和 98 字节的绑定帧都会写失败（真机日志：write failed: 241）。
        // 必须先协商 MTU，否则 bootstrap 会停在第 2 步。
        builder.requestMtu(512);

        bootstrapStep = Step.VERSION;
        emptyReadCount = 0;
        firstRead = true;
        dataTypeIndex = 0;

        // First command goes into the same transaction that initializes the device; all
        // following ones are chained from onCharacteristicRead().
        appendCurrentStep(builder);

        return builder;
    }

    // ---------------------------------------------------------------------
    // Outgoing commands
    // ---------------------------------------------------------------------

    /**
     * Appends {@code write -> wait -> read} for the current {@link #bootstrapStep} to the builder.
     */
    private void appendCurrentStep(final TransactionBuilder builder) {
        final byte[] packet = buildPacketForCurrentStep();
        if (packet == null) {
            LOG.warn("No packet to send for step {}", bootstrapStep);
            return;
        }
        LOG.debug("W2 TX [{}] {}", bootstrapStep, Logging.formatBytes(packet));
        builder.write(writeCharacteristic, packet);
        builder.wait(W2Constants.CMD_GAP_MS);
        builder.read(readCharacteristic);
    }

    /**
     * Queues {@code write -> wait -> read} for the current step as a new transaction.
     * <p>
     * Uses {@code createTransactionBuilder} rather than {@code performInitialized}, because the
     * device is still in {@link GBDevice.State#INITIALIZING} and {@code performInitialized} would
     * queue the initialization sequence again, looping forever.
     */
    private void queueCurrentStep() {
        final TransactionBuilder builder = createTransactionBuilder("W2 " + bootstrapStep);
        appendCurrentStep(builder);
        try {
            performConnected(builder.getTransaction());
        } catch (final IOException e) {
            LOG.error("Failed to queue W2 step {}", bootstrapStep, e);
        }
    }

    /**
     * Queues a read-only transaction, used to retry when the device answered with an empty value.
     */
    private void queueReadRetry() {
        final TransactionBuilder builder = createTransactionBuilder("W2 re-read");
        builder.read(readCharacteristic);
        try {
            performConnected(builder.getTransaction());
        } catch (final IOException e) {
            LOG.error("Failed to queue W2 re-read", e);
        }
    }

    /**
     * @return the packet for the current step, or {@code null} when there is nothing to send.
     */
    private byte[] buildPacketForCurrentStep() {
        switch (bootstrapStep) {
            case VERSION:
                return Packet.envelope("SYNC REQV", null);
            case TIME:
                return Packet.envelope(buildSyncTimeText(), null);
            case CLOCK:
                return Packet.excd(W2Constants.CMD_CLOCK,
                        buildClockPayload(),
                        W2Constants.TAG_CLOCK);
            case BIND:
                return Packet.excd(W2Constants.CMD_BIND,
                        Packet.bindPayload(W2Constants.DEFAULT_USER_ID, 0L),
                        W2Constants.TAG_BIND);
            case LOGIN:
                return Packet.excd(W2Constants.CMD_LOGIN,
                        Packet.bindPayload(W2Constants.DEFAULT_USER_ID, 0L),
                        W2Constants.TAG_BIND);
            case DATA:
                if (dataTypeIndex < 0 || dataTypeIndex >= W2Constants.KNOWN_DATA_TYPES.length) {
                    return null;
                }
                return Packet.excd(W2Constants.CMD_DATA_REQUEST,
                        new byte[]{(byte) W2Constants.KNOWN_DATA_TYPES[dataTypeIndex]},
                        W2Constants.TAG_DATA);
            default:
                return null;
        }
    }

    private String buildSyncTimeText() {
        final long nowMs = System.currentTimeMillis();
        final long unixSeconds = nowMs / 1000L;
        // The vendor app sends the timezone as a millisecond offset (28800000 for UTC+8),
        // not as minutes. TimeZone#getOffset already returns milliseconds.
        final int tzMillis = TimeZone.getDefault().getOffset(nowMs);
        return "SYNC " + unixSeconds + " " + tzMillis + " "
                + W2Constants.APP_VERSION + " " + W2Constants.PLATFORM;
    }

    /**
     * Builds the 6 byte RTC payload, in the phone's current time zone.
     * <p>
     * Verified layout (capture W6, {@code a0 01 01 00 02 08 01 06 | 1a 0b 18 10 30 0d | 3d}):
     * <pre>
     *  [0] year   - 2000   (0x1a = 26 -> 2026)
     *  [1] month   1..12   (0x0b = 11)
     *  [2] day     1..31   (0x18 = 24)
     *  [3] hour    0..23   (0x10 = 16)
     *  [4] minute  0..59   (0x30 = 48)
     *  [5] second  0..59   (0x0d = 13)
     * </pre>
     * Plain hexadecimal values, <b>not</b> BCD — 0x18 is the 24th in hex, a BCD reading would
     * give 18. The values are taken as-is from a real {@link Calendar}; {@code & 0xFF} only
     * keeps the byte cast explicit, it is not a clamping step.
     *
     * @return the 6 byte payload, never {@code null}.
     */
    private byte[] buildClockPayload() {
        // Calendar.getInstance() already uses TimeZone.getDefault(), which on a phone set to
        // Asia/Shanghai gives the UTC+8 wall clock time -- that is what the watch must show.
        final Calendar now = Calendar.getInstance();
        final int year = now.get(Calendar.YEAR) % 100;          // e.g. 2026 -> 26
        final int month = now.get(Calendar.MONTH) + 1;          // Calendar.MONTH is 0 based
        final int day = now.get(Calendar.DAY_OF_MONTH);
        final int hour = now.get(Calendar.HOUR_OF_DAY);         // 24h, not HOUR (12h)
        final int minute = now.get(Calendar.MINUTE);
        final int second = now.get(Calendar.SECOND);

        final byte[] payload = new byte[6];
        payload[0] = (byte) (year & 0xFF);
        payload[1] = (byte) (month & 0xFF);
        payload[2] = (byte) (day & 0xFF);
        payload[3] = (byte) (hour & 0xFF);
        payload[4] = (byte) (minute & 0xFF);
        payload[5] = (byte) (second & 0xFF);

        // slf4j only understands the "{}" placeholder (no printf conversions), so the date is
        // formatted up front.
        LOG.info("W2 clock payload: {} tz={}",
                String.format(Locale.US, "20%02d-%02d-%02d %02d:%02d:%02d",
                        year, month, day, hour, minute, second),
                TimeZone.getDefault().getID());
        return payload;
    }

    // ---------------------------------------------------------------------
    // Incoming responses
    // ---------------------------------------------------------------------

    @Override
    public boolean onCharacteristicRead(final BluetoothGatt gatt,
                                        final BluetoothGattCharacteristic characteristic,
                                        final int status) {
        if (super.onCharacteristicRead(gatt, characteristic, status)) {
            return true;
        }
        if (!W2Constants.UUID_CHAR_READ.equals(characteristic.getUuid())) {
            return false;
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            LOG.warn("W2 read failed with status {}", status);
            handleEmptyRead();
            return true;
        }

        final byte[] value = characteristic.getValue();
        if (value == null || value.length == 0) {
            handleEmptyRead();
            return true;
        }

        LOG.debug("W2 RX [{}] {}", bootstrapStep, Logging.formatBytes(value));
        firstRead = false;
        emptyReadCount = 0;

        final Response response = Response.parse(value);
        for (final String text : response.texts) {
            LOG.info("W2 response text: {}", text);
        }
        if (response.frame.length > 0) {
            LOG.info("W2 response frame: cmd=0x{} plen={} checksumOk={} payload={}",
                    String.format("%02X", response.cmdId),
                    response.payloadLen,
                    response.checksumOk,
                    Logging.formatBytes(response.payload));
        }

        handleResponse(response);
        return true;
    }

    /**
     * Empty read: the device occasionally answers the very first command with a 0-byte value.
     * Re-read (never re-write) up to {@link W2Constants#FIRST_READ_RETRIES} times.
     */
    private void handleEmptyRead() {
        emptyReadCount++;
        if (firstRead) {
            // Known device quirk: the very first read of a session is occasionally empty.
            LOG.info("W2 first read of session was empty, retrying");
        }
        LOG.warn("W2 empty read ({}) for step {}", emptyReadCount, bootstrapStep);

        // The RTC frame is fire-and-forget: the vendor app does not wait for an answer, and on
        // hardware the reply is often a 0-byte value. Retrying here would burn the retry budget
        // and end in WAITING_FOR_RECONNECT, so advance immediately -- the clock was already set
        // by the write.
        if (bootstrapStep == Step.CLOCK) {
            LOG.info("W2 clock frame got no reply (expected), continuing to bind");
            emptyReadCount = 0;
            advanceTo(Step.BIND);
            return;
        }

        if (emptyReadCount <= W2Constants.FIRST_READ_RETRIES) {
            queueReadRetry();
            return;
        }
        LOG.error("W2 gave up on step {} after {} empty reads", bootstrapStep, emptyReadCount);
        emptyReadCount = 0;
        // Do not lie about being initialized: ask for a reconnect instead.
        final TransactionBuilder builder = createTransactionBuilder("W2 read failure");
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.WAITING_FOR_RECONNECT, getContext()));
        try {
            performConnected(builder.getTransaction());
        } catch (final IOException e) {
            LOG.error("Failed to queue W2 reconnect", e);
        }
    }

    private void handleResponse(final Response response) {
        switch (bootstrapStep) {
            case VERSION:
                LOG.info("W2 firmware version response: {}", response.extrasAsText());
                advanceTo(Step.TIME);
                break;
            case TIME:
                LOG.info("W2 time sync acknowledged: {}", response.extrasAsText());
                advanceTo(Step.CLOCK);
                break;
            case CLOCK:
                // The vendor app does not get a dedicated reply for the RTC frame, so whatever
                // comes back (an ACKY echo, an unrelated frame or nothing at all) must not stop
                // the sequence -- the clock has already been set by the write itself.
                LOG.info("W2 clock frame sent, reply cmd={} text=\"{}\" -- continuing to bind",
                        hexCmdId(response), response.extrasAsText());
                advanceTo(Step.BIND);
                break;
            case BIND:
                LOG.info("W2 bind acknowledged (cmd=0x{})", String.format("%02X", response.cmdId));
                advanceTo(Step.LOGIN);
                break;
            case LOGIN:
                LOG.info("W2 login acknowledged (cmd=0x{}) -- bootstrap complete",
                        String.format("%02X", response.cmdId));
                onBootstrapComplete();
                break;
            case DATA:
                handleDataBlock(response);
                advanceDataType();
                break;
            default:
                break;
        }
    }

    private void advanceTo(final Step next) {
        bootstrapStep = next;
        queueCurrentStep();
    }

    /**
     * @param response a parsed response.
     * @return the command id as two hex digits, or {@code "none"} when the response carried no
     *         frame at all (which is the normal case for the RTC step).
     */
    private static String hexCmdId(final Response response) {
        if (response.frame.length == 0) {
            return "none";
        }
        return String.format(Locale.US, "0x%02X", response.cmdId);
    }

    private void onBootstrapComplete() {
        bootstrapStep = Step.DATA;
        dataTypeIndex = 0;
        // Explicitly mark the device as initialized; from here on performInitialized() is safe.
        final TransactionBuilder builder = createTransactionBuilder("W2 initialized");
        builder.add(new SetDeviceStateAction(getDevice(), GBDevice.State.INITIALIZED, getContext()));
        try {
            performConnected(builder.getTransaction());
        } catch (final IOException e) {
            LOG.error("Failed to mark W2 as initialized", e);
        }
        // Ask the device which data types it has. One request per read/write round.
        queueCurrentStep();
    }

    private void advanceDataType() {
        dataTypeIndex++;
        if (dataTypeIndex >= W2Constants.KNOWN_DATA_TYPES.length) {
            bootstrapStep = Step.DONE;
            LOG.info("W2 stage 1 complete: bootstrap + {} data requests done",
                    W2Constants.KNOWN_DATA_TYPES.length);
            return;
        }
        queueCurrentStep();
    }

    /**
     * Handles a data block (cmd 0x21).
     * <p>
     * NOTE: the payload layout of the individual data types has not been verified on hardware
     * yet (only types 0x20 / 0x25 / 0x26 are known to return data at all). We log the raw block
     * so the field layout can be derived from a real capture, and deliberately do not push
     * unverified numbers into the UI.
     */
    private void handleDataBlock(final Response response) {
        if (response.payload.length == 0) {
            LOG.info("W2 data type 0x{} returned no payload",
                    String.format("%02X", W2Constants.KNOWN_DATA_TYPES[dataTypeIndex]));
            return;
        }
        LOG.info("W2 data type 0x{} -> {} byte payload: {}",
                String.format("%02X", response.payload[0] & 0xFF),
                response.payload.length,
                Logging.formatBytes(response.payload));
    }

    @Override
    public boolean useAutoConnect() {
        return false;
    }

    @Override
    public void dispose() {
        bootstrapStep = Step.VERSION;
        emptyReadCount = 0;
        firstRead = true;
        super.dispose();
    }

    // ---------------------------------------------------------------------
    // Protocol codec
    // ---------------------------------------------------------------------

    /**
     * Stateless encoder for the two protocol layers. Kept as a nested class so the whole wire
     * format lives next to the state machine that drives it.
     */
    static final class Packet {

        private Packet() {
        }

        /**
         * Builds an L1 envelope.
         *
         * @param text  ASCII text, without the trailing NUL.
         * @param extra bytes appended after the NUL, not counted in LEN; may be {@code null}.
         * @return the complete envelope.
         */
        static byte[] envelope(final String text, final byte[] extra) {
            final byte[] textBytes = text.getBytes(StandardCharsets.US_ASCII);
            final int len = textBytes.length + 1;
            if (len > W2Constants.MAX_TEXT_LEN) {
                throw new IllegalArgumentException("W2 text too long: " + len + " > "
                        + W2Constants.MAX_TEXT_LEN);
            }
            final ByteArrayOutputStream out = new ByteArrayOutputStream(
                    8 + len + (extra == null ? 0 : extra.length));
            out.write(W2Constants.ENVELOPE_MAGIC, 0, W2Constants.ENVELOPE_MAGIC.length);
            out.write((len >>> 24) & 0xFF);
            out.write((len >>> 16) & 0xFF);
            out.write((len >>> 8) & 0xFF);
            out.write(len & 0xFF);
            out.write(textBytes, 0, textBytes.length);
            out.write(0x00);
            if (extra != null && extra.length > 0) {
                out.write(extra, 0, extra.length);
            }
            return out.toByteArray();
        }

        /**
         * Builds an L2 frame.
         *
         * @param cmdId   command id.
         * @param payload payload, may be empty.
         * @param tag     value of frame bytes [3..4] (big endian). Semantics unverified, values
         *                are taken verbatim from the vendor capture.
         * @return the complete frame, {@code frame[5] + 7} bytes long.
         */
        static byte[] frame(final int cmdId, final byte[] payload, final int tag) {
            final int frameLen = 8 + payload.length + 1;
            if (frameLen > W2Constants.MAX_FRAME_LEN) {
                throw new IllegalArgumentException("W2 frame too long: " + frameLen);
            }
            final byte[] frame = new byte[frameLen];
            frame[0] = (byte) 0xA0;
            frame[1] = 0x01; // current packet
            frame[2] = 0x01; // total packets
            frame[3] = (byte) ((tag >> 8) & 0xFF);
            frame[4] = (byte) (tag & 0xFF);
            frame[5] = (byte) (frameLen - 7);
            frame[6] = (byte) cmdId;
            frame[7] = (byte) payload.length;
            System.arraycopy(payload, 0, frame, 8, payload.length);
            int checksum = 0;
            for (int i = 0; i < frameLen - 1; i++) {
                checksum = (checksum + (frame[i] & 0xFF)) & 0xFF;
            }
            frame[frameLen - 1] = (byte) checksum;
            return frame;
        }

        /**
         * Builds a complete business packet: envelope with {@code EXCD} routing text plus frame.
         *
         * @param cmdId   command id.
         * @param payload payload, may be empty.
         * @param tag     frame tag.
         * @return the bytes to write to {@link W2Constants#UUID_CHAR_WRITE}.
         */
        static byte[] excd(final int cmdId, final byte[] payload, final int tag) {
            final byte[] frame = frame(cmdId, payload, tag);
            // Six fields: the command tag, sender, receiver, two zeros and the data length.
            // The fifth byte is a separator which the firmware overwrites with 0x00, so the
            // text must be "EXCD " with a real space.
            final String text = W2Constants.CMD_TAG + " "
                    + W2Constants.ROUTE_SENDER + " "
                    + W2Constants.ROUTE_RECEIVER + " 0 0 "
                    + frame.length + " ";
            return envelope(text, frame);
        }

        /**
         * Builds the 42 byte bind/login payload.
         * <p>
         * Layout (reverse engineered byte by byte from the vendor capture):
         * <pre>
         *  0..7   user id, 8 ASCII digits        ("43630099")
         *  8..11  zeroes
         * 12..19  64 bit timestamp, big endian
         * 20..21  0x76 0x84
         * 22..41  nickname, UTF-16 <b>big</b> endian, 20 bytes ("Xiaomi 13 ")
         * </pre>
         *
         * @param userId user id, at most 8 decimal digits.
         * @param timestamp 64 bit timestamp; the vendor capture used an opaque value whose
         *                  semantics are unknown, 0 is accepted by the device.
         */
        static byte[] bindPayload(final int userId, final long timestamp) {
            final byte[] payload = new byte[42];
            final String id = String.format("%08d", userId & 0xFFFFFFFFL);
            final byte[] idBytes = id.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(idBytes, 0, payload, 0, Math.min(8, idBytes.length));
            for (int i = 0; i < 8; i++) {
                payload[12 + i] = (byte) ((timestamp >>> (56 - 8 * i)) & 0xFF);
            }
            payload[20] = 0x76;
            payload[21] = (byte) 0x84;
            final byte[] nick = "Xiaomi 13 ".getBytes(StandardCharsets.UTF_16BE);
            final int nickLen = Math.min(20, nick.length);
            System.arraycopy(nick, 0, payload, 22, nickLen);
            return payload;
        }
    }

    /**
     * Parsed representation of one response read.
     * <p>
     * A response may contain several concatenated envelopes (long blocks are split by the
     * device), and the routing header lives in the envelope's {@code extra} part, in front of
     * the 0xA0 frame -- unlike requests, where the routing text is part of the envelope text.
     */
    static final class Response {
        final List<String> texts = new ArrayList<>();
        final List<byte[]> extras = new ArrayList<>();
        byte[] frame = new byte[0];
        int cmdId = -1;
        int payloadLen = -1;
        byte[] payload = new byte[0];
        boolean checksumOk = false;

        /**
         * Parses a raw read value.
         *
         * @param raw bytes as returned by the read action.
         * @return the parsed response, never {@code null}.
         */
        static Response parse(final byte[] raw) {
            final Response response = new Response();
            final List<Integer> offsets = new ArrayList<>();
            for (int i = 0; i + 4 <= raw.length; i++) {
                if (raw[i] == W2Constants.ENVELOPE_MAGIC[0]
                        && raw[i + 1] == W2Constants.ENVELOPE_MAGIC[1]
                        && raw[i + 2] == W2Constants.ENVELOPE_MAGIC[2]
                        && raw[i + 3] == W2Constants.ENVELOPE_MAGIC[3]) {
                    offsets.add(i);
                }
            }
            for (int k = 0; k < offsets.size(); k++) {
                final int start = offsets.get(k);
                final int end = (k + 1 < offsets.size()) ? offsets.get(k + 1) : raw.length;
                if (end - start < 8) {
                    continue;
                }
                final int len = ((raw[start + 4] & 0xFF) << 24)
                        | ((raw[start + 5] & 0xFF) << 16)
                        | ((raw[start + 6] & 0xFF) << 8)
                        | (raw[start + 7] & 0xFF);
                final int textStart = start + 8;
                int textEnd = textStart + len;
                if (len <= 0 || textEnd > end) {
                    textEnd = Math.min(end, textStart);
                }
                response.texts.add(asciiUpToNul(raw, textStart, textEnd));
                final int extraStart = Math.min(end, start + 8 + Math.max(len, 0));
                final int extraLen = Math.max(0, end - extraStart);
                final byte[] extra = new byte[extraLen];
                System.arraycopy(raw, extraStart, extra, 0, extraLen);
                response.extras.add(extra);
            }
            response.frame = assembleFrame(response.extras);
            response.parseFrame();
            return response;
        }

        /**
         * Concatenates the frame bytes of all envelopes, dropping the routing header that
         * precedes the 0xA0 magic in each of them.
         *
         * @param extras per-envelope extra parts.
         * @return the assembled frame (possibly empty).
         */
        private static byte[] assembleFrame(final List<byte[]> extras) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (final byte[] extra : extras) {
                int start = -1;
                for (int i = 0; i < extra.length; i++) {
                    if (extra[i] == (byte) 0xA0) {
                        start = i;
                        break;
                    }
                }
                if (start >= 0) {
                    out.write(extra, start, extra.length - start);
                }
            }
            return out.toByteArray();
        }

        private static String asciiUpToNul(final byte[] raw, final int from, final int to) {
            final StringBuilder sb = new StringBuilder();
            for (int i = from; i < to; i++) {
                final int b = raw[i] & 0xFF;
                if (b == 0) {
                    break;
                }
                sb.append((char) b);
            }
            return sb.toString();
        }

        private void parseFrame() {
            if (frame.length < 9) {
                return;
            }
            final int frameLen = (frame[5] & 0xFF) + 7;
            if (frameLen > frame.length || frameLen < 9) {
                return;
            }
            cmdId = frame[6] & 0xFF;
            payloadLen = frame[7] & 0xFF;
            final int payloadEnd = Math.min(8 + payloadLen, frameLen - 1);
            payload = new byte[Math.max(0, payloadEnd - 8)];
            System.arraycopy(frame, 8, payload, 0, payload.length);
            int checksum = 0;
            for (int i = 0; i < frameLen - 1; i++) {
                checksum = (checksum + (frame[i] & 0xFF)) & 0xFF;
            }
            checksumOk = checksum == (frame[frameLen - 1] & 0xFF);
        }

        /**
         * @return the envelope texts joined with a space, for logging of VERN/ACKY replies.
         */
        String extrasAsText() {
            final StringBuilder sb = new StringBuilder();
            for (final String text : texts) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(text);
            }
            for (final byte[] extra : extras) {
                if (extra.length == 0) {
                    continue;
                }
                final String s = asciiUpToNul(extra, 0, extra.length);
                if (!s.isEmpty() && !s.startsWith(W2Constants.ROUTE_SENDER)) {
                    sb.append(' ').append(s);
                }
            }
            return sb.toString();
        }
    }
}
