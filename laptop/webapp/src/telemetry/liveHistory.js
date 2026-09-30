// The app's single rolling history, fed by every record from the telemetry store.
// Import this module early (App.jsx does) so history starts filling before any panel mounts.
import { createHistory } from "./history.js";
import { subscribeAll } from "./store.js";

export const history = createHistory();
subscribeAll((r) => history.push(r, Date.now()));
