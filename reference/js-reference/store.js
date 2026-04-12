// js/store.js — IndexedDB storage for identity, contacts, and messages.

'use strict';

const DB_NAME = 'reticulum-webclient';
const DB_VERSION = 2;

let db = null;

export async function openDatabase() {
  if (db) return db;

  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);

    req.onupgradeneeded = (event) => {
      const db = event.target.result;

      // Identity store (single row — our keypair)
      if (!db.objectStoreNames.contains('identity')) {
        db.createObjectStore('identity', { keyPath: 'id' });
      }

      // Contacts (discovered identities from announces)
      if (!db.objectStoreNames.contains('contacts')) {
        const store = db.createObjectStore('contacts', { keyPath: 'hash' });
        store.createIndex('name', 'displayName', { unique: false });
      }

      // Messages (LXMF messages)
      if (!db.objectStoreNames.contains('messages')) {
        const store = db.createObjectStore('messages', { keyPath: 'id', autoIncrement: true });
        store.createIndex('contact', 'contactHash', { unique: false });
        store.createIndex('timestamp', 'timestamp', { unique: false });
      }

      // Nodes (announces from non-LXMF destinations on the same mesh:
      // repeater telemetry, heartbeat beacons, auxiliary destinations,
      // anything else that is not lxmf.delivery). Separate from
      // contacts so the Messages UI stays clean, but kept so the user
      // can see the mesh activity in the Nodes panel.
      if (!db.objectStoreNames.contains('nodes')) {
        const store = db.createObjectStore('nodes', { keyPath: 'hash' });
      }
    };

    req.onsuccess = (event) => {
      db = event.target.result;
      resolve(db);
    };

    req.onerror = () => reject(req.error);
  });
}

// ---- Identity --------------------------------------------------------

export async function saveIdentity(identityData) {
  const d = await openDatabase();
  const tx = d.transaction('identity', 'readwrite');
  tx.objectStore('identity').put({ id: 'self', ...identityData });
  return new Promise((resolve, reject) => {
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
}

export async function loadIdentity() {
  const d = await openDatabase();
  const tx = d.transaction('identity', 'readonly');
  const req = tx.objectStore('identity').get('self');
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result || null);
    req.onerror = () => reject(req.error);
  });
}

// ---- Contacts --------------------------------------------------------

export async function saveContact(contact) {
  const d = await openDatabase();
  const tx = d.transaction('contacts', 'readwrite');
  tx.objectStore('contacts').put(contact);
  return new Promise((resolve, reject) => {
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
}

export async function getContact(hash) {
  const d = await openDatabase();
  const tx = d.transaction('contacts', 'readonly');
  const req = tx.objectStore('contacts').get(hash);
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result || null);
    req.onerror = () => reject(req.error);
  });
}

export async function getAllContacts() {
  const d = await openDatabase();
  const tx = d.transaction('contacts', 'readonly');
  const req = tx.objectStore('contacts').getAll();
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function deleteContact(hash) {
  const d = await openDatabase();
  const tx = d.transaction('contacts', 'readwrite');
  tx.objectStore('contacts').delete(hash);
  return new Promise((resolve, reject) => {
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
}

export async function deleteMessagesForContact(contactHash) {
  const d = await openDatabase();
  const tx = d.transaction('messages', 'readwrite');
  const index = tx.objectStore('messages').index('contact');
  const req = index.openCursor(contactHash);
  return new Promise((resolve, reject) => {
    req.onsuccess = (e) => {
      const cursor = e.target.result;
      if (cursor) { cursor.delete(); cursor.continue(); }
    };
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
}

// ---- Messages --------------------------------------------------------

export async function saveMessage(message) {
  const d = await openDatabase();
  const tx = d.transaction('messages', 'readwrite');
  const req = tx.objectStore('messages').add(message);
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);  // returns auto-generated id
    req.onerror = () => reject(tx.error);
  });
}

export async function getMessages(contactHash) {
  const d = await openDatabase();
  const tx = d.transaction('messages', 'readonly');
  const index = tx.objectStore('messages').index('contact');
  const req = index.getAll(contactHash);
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export async function getAllMessages() {
  const d = await openDatabase();
  const tx = d.transaction('messages', 'readonly');
  const req = tx.objectStore('messages').getAll();
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

// Fetch a single message row by its auto-increment id. Returns null
// if the row has been deleted in the meantime.
export async function getMessageById(id) {
  const d = await openDatabase();
  const tx = d.transaction('messages', 'readonly');
  const req = tx.objectStore('messages').get(id);
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result || null);
    req.onerror = () => reject(req.error);
  });
}

// ---- Nodes (non-LXMF announces) -------------------------------------

// Upsert a node row keyed by its destination hash hex.
export async function saveNode(node) {
  const d = await openDatabase();
  const tx = d.transaction('nodes', 'readwrite');
  tx.objectStore('nodes').put(node);
  return new Promise((resolve, reject) => {
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
}

export async function getAllNodes() {
  const d = await openDatabase();
  const tx = d.transaction('nodes', 'readonly');
  const req = tx.objectStore('nodes').getAll();
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result || []);
    req.onerror = () => reject(req.error);
  });
}

export async function deleteNode(hash) {
  const d = await openDatabase();
  const tx = d.transaction('nodes', 'readwrite');
  tx.objectStore('nodes').delete(hash);
  return new Promise((resolve, reject) => {
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
}

export async function deleteAllNodes() {
  const d = await openDatabase();
  const tx = d.transaction('nodes', 'readwrite');
  tx.objectStore('nodes').clear();
  return new Promise((resolve, reject) => {
    tx.oncomplete = resolve;
    tx.onerror = () => reject(tx.error);
  });
}

// Read-modify-write a message row by id. Merges `updates` into the
// existing row and writes it back. Safe to call even if the row has
// been deleted — returns null in that case.
export async function updateMessage(id, updates) {
  const d = await openDatabase();
  const tx = d.transaction('messages', 'readwrite');
  const store = tx.objectStore('messages');
  return new Promise((resolve, reject) => {
    const getReq = store.get(id);
    getReq.onsuccess = () => {
      const row = getReq.result;
      if (!row) { resolve(null); return; }
      Object.assign(row, updates);
      const putReq = store.put(row);
      putReq.onsuccess = () => resolve(row);
      putReq.onerror = () => reject(putReq.error);
    };
    getReq.onerror = () => reject(getReq.error);
  });
}
