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
package nodomain.freeyourgadget.gadgetbridge.devices.w2;

import android.bluetooth.le.ScanFilter;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Pattern;

import nodomain.freeyourgadget.gadgetbridge.GBException;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.AbstractBLEDeviceCoordinator;
import nodomain.freeyourgadget.gadgetbridge.entities.DaoSession;
import nodomain.freeyourgadget.gadgetbridge.entities.Device;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.service.DeviceSupport;
import nodomain.freeyourgadget.gadgetbridge.service.devices.w2.W2Constants;
import nodomain.freeyourgadget.gadgetbridge.service.devices.w2.W2DeviceSupport;

/**
 * Coordinator for the Phicomm W2 smartwatch (MT2523).
 * <p>
 * Only the bare minimum is implemented: discovery, pairing and connection. Feature methods
 * (activity, alarms, notifications...) keep the conservative defaults of
 * {@link AbstractBLEDeviceCoordinator} until the corresponding protocol parts are verified.
 */
public class W2Coordinator extends AbstractBLEDeviceCoordinator {

    @Override
    public String getManufacturer() {
        return "Phicomm";
    }

    @Override
    protected Pattern getSupportedDeviceName() {
        // 实测手表在不同状态下广播名不同：BLE 广播常见 "W2-<MAC>"，
        // 而系统配对信息里记录的经典蓝牙名是 "PhicommW2"。两者都要接受，否则扫不到。
        return Pattern.compile("^(W2-|PhicommW2|PHICOMM_W2).*", Pattern.CASE_INSENSITIVE);
    }

    @NonNull
    @Override
    public Collection<? extends ScanFilter> createBLEScanFilters() {
        // W2 的广播包不保证携带 0x18A0 service UUID（实测用 UUID 过滤会完全扫不到设备）。
        // 返回空列表 → 系统上报所有广播，由基类通过 getSupportedDeviceName() 的 "W2-" 前缀匹配。
        return Collections.emptyList();
    }

    @Override
    public int getBondingStyle() {
        // The protocol is plain GATT; the watch never requires LE bonding/encryption.
        return BONDING_STYLE_NONE;
    }

    @Override
    public int getAlarmSlotCount(final GBDevice device) {
        // Alarms are not implemented yet.
        return 0;
    }

    @Override
    public boolean suggestUnbindBeforePair() {
        return false;
    }

    @Override
    protected void deleteDevice(@NonNull final GBDevice gbDevice,
                                @NonNull final Device device,
                                @NonNull final DaoSession session) throws GBException {
        // Nothing device specific is persisted yet.
    }

    @NonNull
    @Override
    public Class<? extends DeviceSupport> getDeviceSupportClass() {
        return W2DeviceSupport.class;
    }

    @Override
    public int getDeviceNameResource() {
        return R.string.devicetype_phicomm_w2;
    }
}
