// js/crypto.js — Reticulum encryption: ECDH + HKDF + Token (Fernet-variant).
//
// Encrypt: ephemeral X25519 ECDH → HKDF → AES-256-CBC + HMAC-SHA256
// Token format: iv(16) + aes_ciphertext + hmac(32)
// Wire format: ephemeral_pubkey(32) + token

'use strict';

import { x25519 } from '@noble/curves/ed25519';
import { concatBytes } from './announce.js';

// ---- HKDF (HMAC-SHA256) using Web Crypto API -------------------------

export async function hkdfDerive(ikm, salt, info, length) {
  const keyMaterial = await crypto.subtle.importKey(
    'raw', ikm, 'HKDF', false, ['deriveBits']
  );
  const bits = await crypto.subtle.deriveBits(
    { name: 'HKDF', hash: 'SHA-256', salt, info },
    keyMaterial, length * 8
  );
  return new Uint8Array(bits);
}

// ---- Token (modified Fernet) -----------------------------------------
// Key split: first 32 bytes = signing key (HMAC), last 32 bytes = encryption key (AES-256)
// Token = iv(16) + aes_ciphertext + hmac(32)
//
// Note: Web Crypto's AES-CBC applies PKCS#7 padding automatically on
// both encrypt and decrypt. We do not (and must not) pad/unpad manually,
// or we would double-pad outbound ciphertext and double-unpad inbound
// plaintext. Reticulum's on-wire format is exactly what Web Crypto
// produces: iv + raw AES-CBC(PKCS#7(plaintext)) + hmac.

export async function tokenEncrypt(derivedKey, plaintext) {
  const signingKey    = derivedKey.subarray(0, 32);
  const encryptionKey = derivedKey.subarray(32, 64);

  const iv = new Uint8Array(16);
  crypto.getRandomValues(iv);

  const aesKey = await crypto.subtle.importKey('raw', encryptionKey, 'AES-CBC', false, ['encrypt']);
  const cipherBuf = await crypto.subtle.encrypt({ name: 'AES-CBC', iv }, aesKey, plaintext);
  const ciphertext = new Uint8Array(cipherBuf);

  const hmacKey = await crypto.subtle.importKey('raw', signingKey, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const hmacData = concatBytes([iv, ciphertext]);
  const hmacBuf = await crypto.subtle.sign('HMAC', hmacKey, hmacData);
  const hmac = new Uint8Array(hmacBuf);

  return concatBytes([iv, ciphertext, hmac]);
}

export async function tokenDecrypt(derivedKey, token) {
  if (token.length < 48) throw new Error('Token too short');

  const signingKey    = derivedKey.subarray(0, 32);
  const encryptionKey = derivedKey.subarray(32, 64);

  const iv         = token.subarray(0, 16);
  const ciphertext = token.subarray(16, token.length - 32);
  const hmac       = token.subarray(token.length - 32);

  const hmacKey = await crypto.subtle.importKey('raw', signingKey, { name: 'HMAC', hash: 'SHA-256' }, false, ['verify']);
  const hmacData = concatBytes([iv, ciphertext]);
  const valid = await crypto.subtle.verify('HMAC', hmacKey, hmac, hmacData);
  if (!valid) throw new Error('HMAC verification failed');

  const aesKey = await crypto.subtle.importKey('raw', encryptionKey, 'AES-CBC', false, ['decrypt']);
  const plainBuf = await crypto.subtle.decrypt({ name: 'AES-CBC', iv }, aesKey, ciphertext);
  return new Uint8Array(plainBuf);
}

// ---- Reticulum encrypt/decrypt (ECDH + HKDF + Token) ----------------

// Encrypt plaintext for a recipient's X25519 public key.
// Returns: ephemeral_pubkey(32) + token
export async function encrypt(plaintext, recipientEncPubKey, recipientIdentityHash) {
  // Generate ephemeral X25519 keypair
  const ephPriv = x25519.utils.randomPrivateKey();
  const ephPub  = x25519.getPublicKey(ephPriv);

  // ECDH shared secret
  const shared = x25519.getSharedSecret(ephPriv, recipientEncPubKey);

  // HKDF derive 64 bytes (32 signing + 32 encryption)
  const derived = await hkdfDerive(shared, recipientIdentityHash, new Uint8Array(0), 64);

  // Token encrypt
  const token = await tokenEncrypt(derived, plaintext);

  return concatBytes([ephPub, token]);
}

// Decrypt ciphertext using one of several candidate X25519 private
// keys. Callers pass the keys in try-order: typically the current
// ratchet private key first, then the long-term identity private
// key as a fallback for senders who don't track our ratchet.
// ciphertext = ephemeral_pubkey(32) + token
export async function decrypt(ciphertext, ourEncPrivKeys, ourIdentityHash) {
  if (ciphertext.length < 32 + 48) throw new Error('Ciphertext too short');

  const ephPub = ciphertext.subarray(0, 32);
  const token  = ciphertext.subarray(32);

  const candidates = Array.isArray(ourEncPrivKeys) ? ourEncPrivKeys : [ourEncPrivKeys];
  let lastError = null;
  for (const priv of candidates) {
    if (!priv) continue;
    try {
      const shared = x25519.getSharedSecret(priv, ephPub);
      const derived = await hkdfDerive(shared, ourIdentityHash, new Uint8Array(0), 64);
      return await tokenDecrypt(derived, token);
    } catch (e) {
      lastError = e;
    }
  }
  throw lastError || new Error('Decryption failed with all candidate keys');
}
