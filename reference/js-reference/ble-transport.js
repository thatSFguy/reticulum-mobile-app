// js/ble-transport.js — Web Bluetooth NUS (Nordic UART Service) transport.
//
// Provides a byte-stream interface over BLE GATT. Handles device
// discovery, connect/disconnect, notification subscription, and
// write chunking for the NUS TX characteristic.

'use strict';

const NUS_SERVICE_UUID = '6e400001-b5a3-f393-e0a9-e50e24dcca9e';
const NUS_TX_UUID      = '6e400002-b5a3-f393-e0a9-e50e24dcca9e'; // write (phone → device)
const NUS_RX_UUID      = '6e400003-b5a3-f393-e0a9-e50e24dcca9e'; // notify (device → phone)

export class BleTransport {
  constructor() {
    this.device = null;
    this.server = null;
    this.service = null;
    this.txChar = null;  // write to device
    this.rxChar = null;  // notifications from device
    this.mtu = 20;       // effective write size (MTU - 3), updated after connect
    this._onReceive = null;
    this._onDisconnect = null;
    this._onLog = null;
  }

  get connected() {
    return this.server !== null && this.server.connected;
  }

  _log(msg) {
    if (this._onLog) this._onLog(msg);
  }

  // Request a BLE device that advertises NUS, connect, and subscribe.
  async connect() {
    if (!navigator.bluetooth) {
      throw new Error('Web Bluetooth not supported in this browser');
    }

    this._log('Requesting BLE device with NUS service...');
    this.device = await navigator.bluetooth.requestDevice({
      filters: [{ services: [NUS_SERVICE_UUID] }],
      optionalServices: [NUS_SERVICE_UUID],
    });

    if (!this.device) throw new Error('No device selected');
    this._log(`Selected: ${this.device.name || 'unnamed'} (${this.device.id})`);

    // Listen for disconnect
    this.device.addEventListener('gattserverdisconnected', () => {
      this._log('BLE disconnected');
      this._cleanup();
      if (this._onDisconnect) this._onDisconnect();
    });

    // Connect GATT
    this._log('Connecting GATT...');
    this.server = await this.device.gatt.connect();

    // Discover NUS service
    this._log('Discovering NUS service...');
    this.service = await this.server.getPrimaryService(NUS_SERVICE_UUID);

    // Get characteristics
    this.txChar = await this.service.getCharacteristic(NUS_TX_UUID);
    this.rxChar = await this.service.getCharacteristic(NUS_RX_UUID);

    // Subscribe to notifications (device → phone)
    this.rxChar.addEventListener('characteristicvaluechanged', (event) => {
      const value = new Uint8Array(event.target.value.buffer);
      if (this._onReceive) this._onReceive(value);
    });
    await this.rxChar.startNotifications();

    this._log(`Connected to ${this.device.name || 'RNode'}`);
  }

  async disconnect() {
    if (this.device && this.device.gatt.connected) {
      this.device.gatt.disconnect();
    }
    this._cleanup();
  }

  _cleanup() {
    this.server = null;
    this.service = null;
    this.txChar = null;
    this.rxChar = null;
  }

  // Write bytes to the device (phone → device via NUS TX characteristic).
  // Chunks data into MTU-sized pieces if needed.
  async write(data) {
    if (!this.txChar) throw new Error('Not connected');
    const bytes = data instanceof Uint8Array ? data : new Uint8Array(data);

    // Write in chunks of MTU size. Web Bluetooth handles MTU
    // negotiation internally; writeValueWithoutResponse is limited
    // to the negotiated MTU - 3. Use 20 bytes as safe default.
    const chunkSize = this.mtu;
    for (let offset = 0; offset < bytes.length; offset += chunkSize) {
      const chunk = bytes.subarray(offset, Math.min(offset + chunkSize, bytes.length));
      await this.txChar.writeValueWithoutResponse(chunk);
    }
  }
}
