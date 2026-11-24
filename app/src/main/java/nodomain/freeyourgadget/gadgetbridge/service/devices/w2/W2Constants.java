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

import java.util.UUID;

/**
 * W2 (Phicomm W2 smartwatch, MT2523) BLE protocol constants.
 * <p>
 * The protocol was reverse engineered and verified on a real device; the byte-level
 * specification lives in {@code docs/Gadgetbridge接入_实现方案.md} and the verified
 * reference implementation is {@code tools/w2_client.py}.
 * <p>
 * Two layers:
 * <ul>
 *     <li>L1 envelope: {@code F0 F0 F0 F1 | be32 LEN | ASCII text | 0x00 | extra...}
 *     where {@code LEN = text.length + 1} (includes the trailing NUL, excludes extra)
 *     and must be &lt;= {@link #MAX_TEXT_LEN}.</li>
 *     <li>L2 frame (appended as "extra", not counted in LEN):
 *     {@code A0 | cur | total | tag(2) | frameLen-7 | cmdId | payloadLen | payload | checksum}
 *     where {@code frameLen = frame[5] + 7} and {@code checksum = sum(frame[0..n-2]) & 0xFF}.</li>
 * </ul>
 * Commands are strictly serialized: write one packet to {@link #UUID_CHAR_WRITE}, then
 * <b>read</b> the response from {@link #UUID_CHAR_READ}. Notifications/CCCD are never used.
 */
public final class W2Constants {

    private W2Constants() {
    }

    /** Service 0x18A0 — the only service we need. */
    public static final UUID UUID_SERVICE = UUID.fromString("000018a0-0000-1000-8000-00805f9b34fb");

    /** Characteristic 0x2AA0 — device to phone, read (polled, never subscribed). */
    public static final UUID UUID_CHAR_READ = UUID.fromString("00002aa0-0000-1000-8000-00805f9b34fb");

    /** Characteristic 0x2AA1 — phone to device, write with response. */
    public static final UUID UUID_CHAR_WRITE = UUID.fromString("00002aa1-0000-1000-8000-00805f9b34fb");

    /** Advertised name prefix. The full name is {@code W2-<MAC>}. */
    public static final String DEVICE_NAME_PREFIX = "W2-";

    /** L1 envelope magic. */
    public static final byte[] ENVELOPE_MAGIC = {(byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF1};

    /** Hard limit enforced by the firmware (0x0814870C / 0x08149834). */
    public static final int MAX_TEXT_LEN = 60;

    /** Hard limit enforced by the firmware (_mmi_bt_recv_cb). */
    public static final int MAX_FRAME_LEN = 120;

    /** Command tag registered by the firmware for the business channel ("EXCD"). */
    public static final String CMD_TAG = "EXCD";

    /** Fixed routing ids used by the vendor protocol. */
    public static final String ROUTE_SENDER = "band_sender";
    public static final String ROUTE_RECEIVER = "band_receiver";

    /** Gap inserted between write and read (the device is a single-channel state machine). */
    public static final int CMD_GAP_MS = 120;

    /** First read of a session: the device sometimes answers with an empty value. */
    public static final int FIRST_READ_RETRIES = 3;

    /** App version string the vendor app sends in the SYNC time command. */
    public static final String APP_VERSION = "1.2";

    /** Platform string the vendor app sends in the SYNC time command. */
    public static final String PLATFORM = "ANDROID";

    /**
     * User id used for the bind/login frames.
     * <p>
     * The vendor capture was produced with 43630099; we replay the same value so the
     * device accepts the bind on an already-provisioned watch. The firmware keeps this id
     * in NVM and answers "user id is NOT matched, login failed" if it differs.
     */
    public static final int DEFAULT_USER_ID = 43630099;

    /** Frame command ids (only the ones used by this stage). */
    public static final int CMD_BIND = 0x01;
    public static final int CMD_LOGIN = 0x03;
    public static final int CMD_STEPS = 0x06;
    public static final int CMD_DATA_REQUEST = 0x20;
    public static final int CMD_DATA_REQUEST_ALT = 0x22;
    public static final int CMD_DATA_BLOCK = 0x21;
    public static final int CMD_STATUS = 0x28;

    /** Frame tag values, taken verbatim from the vendor capture (semantics unverified). */
    public static final int TAG_BIND = 1;
    public static final int TAG_QUERY = 2;
    public static final int TAG_DATA = 3;
    public static final int TAG_SINGLE = 6;

    /** Data types that the firmware is known to answer (from the verified capture). */
    public static final int[] KNOWN_DATA_TYPES = {0x20, 0x25, 0x26};
}
