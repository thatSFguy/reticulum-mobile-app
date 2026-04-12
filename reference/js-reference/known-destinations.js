// js/known-destinations.js — lookup table mapping well-known
// Reticulum destination name_hashes to the canonical service name
// they belong to, so the Nodes view can show "rlr.telemetry"
// instead of "fd68805f2ea3…" for announces whose payload we cannot
// decode into a human label on our own.
//
// Each key is the lower-case hex of SHA256(name)[:10] — the 10-byte
// truncated hash Reticulum uses as the announce's name_hash field.
// Pre-computed at authoring time so there is no runtime hashing.
//
// Adding a new entry: the name hash can be regenerated with
//   node -e "console.log(require('crypto').createHash('sha256').update('NAME_HERE', 'utf8').digest('hex').substring(0, 20))"
//
// `name` is the canonical full name (app_name + '.' + aspects),
// `label` is a human-readable service description for UI surfaces.

'use strict';

export const KNOWN_DESTINATIONS = {
  '6ec60bc318e2c0f0d908': { name: 'lxmf.delivery',                   label: 'LXMF delivery' },
  'e03a09b77ac21b22258e': { name: 'lxmf.propagation',                label: 'LXMF propagation node' },
  '213e6311bcec54ab4fde': { name: 'nomadnetwork.node',               label: 'NomadNet node' },
  '0ad8bff9ff75737c058e': { name: 'nomadnetwork.gossip',             label: 'NomadNet gossip' },
  '28f44518c0b20af50215': { name: 'nomadnetwork.gossip.conversation', label: 'NomadNet gossip channel' },
  '9efb9c771eeb5ae90ea6': { name: 'rnstransport.broadcasts',         label: 'RNS transport broadcast' },
  '4848a053c16415bed6c8': { name: 'rnstransport.remote.management',  label: 'RNS remote management' },
  '3eea23374d2a3aedf2cc': { name: 'rlr.telemetry',                   label: 'RLR telemetry beacon' },
};

// Look up a name_hash in the table. Accepts either a hex string or
// a Uint8Array / number[]. Returns { name, label } or null.
export function lookupDestination(nameHash) {
  if (!nameHash) return null;
  let hex;
  if (typeof nameHash === 'string') {
    hex = nameHash.toLowerCase();
  } else if (nameHash instanceof Uint8Array || Array.isArray(nameHash)) {
    hex = Array.from(nameHash).map(b => b.toString(16).padStart(2, '0')).join('');
  } else {
    return null;
  }
  return KNOWN_DESTINATIONS[hex.substring(0, 20)] || null;
}
