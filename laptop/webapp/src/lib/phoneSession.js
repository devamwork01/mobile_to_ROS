// Which phone session a control event belongs to. The server assigns a new device id on every
// (re)connect and notices a dead socket only at its next ping (up to ~40 s), so after a Wi-Fi blip
// the old session's phone_disconnected arrives after the new session's phone_connected.

/** True when a phone_disconnected for `eventId` ends the session the dashboard shows (`currentId`). */
export function isCurrentPhone(currentId, eventId) {
  if (currentId == null || eventId == null) return true;
  return currentId === eventId;
}
