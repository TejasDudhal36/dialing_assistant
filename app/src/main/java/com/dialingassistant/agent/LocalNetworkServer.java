package com.dialingassistant.agent;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoWSD;

import java.io.IOException;
import java.security.KeyStore;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;

/**
 * Local HTTPS + WebSocket server running on {@link #PORT}.
 *
 * Endpoints
 * ─────────
 *  GET  /ping               → {"app":"agent-android"}
 *  POST /cmd/dial           ← {"number":"..."}
 *  POST /cmd/hangup
 *  POST /cmd/answer
 *  POST /cmd/sms            ← {"to":"...","body":"...","cmdId":"..."}
 *  POST /cmd/wifi           ← {"value":0|1}
 *  POST /cmd/bluetooth      ← {"value":0|1}
 *  POST /cmd/hotspot        ← {"value":0|1}
 *  POST /cmd/call_volume    ← {"value":0|1}  (down / up)
 *  POST /cmd/music          ← {"value":0|1|2|3|4|5|6}
 *                              (prev / toggle / next / vol- / vol+ / play / pause)
 *  GET  /ws  (WebSocket upgrade)
 *
 * WebSocket events pushed to all connected clients
 * ──────────────────────────────────────────────────
 *  {"type":"call_state",         "data":{...}}
 *  {"type":"incoming_call",      "data":{...}}
 *  {"type":"call_history_update","data":{...}}
 *  {"type":"device_state",       "data":{...}}
 *  {"type":"notification_add",   "data":{...}}
 *  {"type":"notification_remove","data":{"id":"..."}}
 *  {"type":"sms_thread",         "data":{...}}
 *  {"type":"sms_message",        "data":{...}}
 */
public class LocalNetworkServer extends NanoWSD {

    public static final int PORT = 8765;
    private static final String TAG = "LocalNetworkServer";

    // Active WebSocket connections
    private static final Set<NanoWSD.WebSocket> clients =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Callback interface so the server can tell the service what to do
    public interface CommandHandler {
        void onDial(String number);
        void onHangup();
        void onAnswer();
        void onSms(String to, String body, String cmdId);
        void onWifi(int value);
        void onBluetooth(int value);
        void onHotspot(int value);
        void onCallVolume(int value);
        void onMusic(int value);
    }

    private final CommandHandler handler;

    public LocalNetworkServer(Context context, CommandHandler handler) throws Exception {
        super(PORT);
        this.handler = handler;

        // Set up TLS
        LocalCertManager certMgr = new LocalCertManager(context);
        KeyStore ks = certMgr.getKeyStore();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "agentlocal".toCharArray());

        SSLContext sslCtx = SSLContext.getInstance("TLS");
        sslCtx.init(kmf.getKeyManagers(), null, null);
        SSLServerSocketFactory ssf = sslCtx.getServerSocketFactory();
        makeSecure(ssf, null);
    }

    // ── HTTP routing ─────────────────────────────────────────────────────────

    @Override
    public Response serve(IHTTPSession session) {
        // Let NanoWSD handle WebSocket upgrades
        if (isWebsocketRequested(session)) {
            return super.serve(session);
        }

        String uri = session.getUri();
        Method method = session.getMethod();

        // CORS headers for all responses
        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.put("Access-Control-Allow-Headers", "Content-Type");

        if (Method.OPTIONS.equals(method)) {
            Response r = newFixedLengthResponse(Response.Status.OK, "text/plain", "");
            addHeaders(r, headers);
            return r;
        }

        if (Method.GET.equals(method) && "/ping".equals(uri)) {
            return jsonResponse(Response.Status.OK,
                    "{\"app\":\"agent-android\"}", headers);
        }

        if (Method.POST.equals(method) && uri.startsWith("/cmd/")) {
            return handleCommand(uri, session, headers);
        }

        return jsonResponse(Response.Status.NOT_FOUND,
                "{\"error\":\"Not found\"}", headers);
    }

    private Response handleCommand(String uri, IHTTPSession session,
                                   Map<String, String> headers) {
        JSONObject body = readBody(session);
        try {
            switch (uri) {
                case "/cmd/dial": {
                    String number = body != null ? body.optString("number", "") : "";
                    if (!number.isEmpty()) handler.onDial(number);
                    break;
                }
                case "/cmd/hangup":
                    handler.onHangup();
                    break;
                case "/cmd/answer":
                    handler.onAnswer();
                    break;
                case "/cmd/sms": {
                    if (body != null) {
                        handler.onSms(
                                body.optString("to", ""),
                                body.optString("body", ""),
                                body.optString("cmdId", ""));
                    }
                    break;
                }
                case "/cmd/wifi":
                    if (body != null) handler.onWifi(body.optInt("value", 0));
                    break;
                case "/cmd/bluetooth":
                    if (body != null) handler.onBluetooth(body.optInt("value", 0));
                    break;
                case "/cmd/hotspot":
                    if (body != null) handler.onHotspot(body.optInt("value", 0));
                    break;
                case "/cmd/call_volume":
                    if (body != null) handler.onCallVolume(body.optInt("value", 0));
                    break;
                case "/cmd/music":
                    if (body != null) handler.onMusic(body.optInt("value", 1));
                    break;
                default:
                    return jsonResponse(Response.Status.NOT_FOUND,
                            "{\"error\":\"Unknown command\"}", headers);
            }
            return jsonResponse(Response.Status.OK, "{\"ok\":true}", headers);
        } catch (Exception e) {
            Log.e(TAG, "Command error: " + e.getMessage());
            return jsonResponse(Response.Status.INTERNAL_ERROR,
                    "{\"error\":\"" + e.getMessage() + "\"}", headers);
        }
    }

    // ── WebSocket ────────────────────────────────────────────────────────────

    @Override
    protected WebSocket openWebSocket(IHTTPSession handshake) {
        return new NanoWSD.WebSocket(handshake) {
            @Override
            protected void onOpen() {
                clients.add(this);
                Log.d(TAG, "WS client connected. total=" + clients.size());
            }

            @Override
            protected void onClose(WebSocketFrame.CloseCode code, String reason,
                                   boolean initiatedByRemote) {
                clients.remove(this);
                Log.d(TAG, "WS client disconnected. total=" + clients.size());
            }

            @Override
            protected void onMessage(WebSocketFrame message) {
                // Client-to-server messages not used currently
            }

            @Override
            protected void onPong(WebSocketFrame pong) {}

            @Override
            protected void onException(IOException e) {
                clients.remove(this);
            }
        };
    }

    // ── Broadcast ────────────────────────────────────────────────────────────

    /**
     * Broadcast a typed event to all connected WebSocket clients.
     * Safe to call from any thread.
     */
    public static void broadcast(String type, JSONObject data) {
        if (clients.isEmpty()) return;
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", type);
            msg.put("data", data != null ? data : new JSONObject());
            String text = msg.toString();
            for (NanoWSD.WebSocket ws : clients) {
                try {
                    ws.send(text);
                } catch (Exception ignored) {
                    clients.remove(ws);
                }
            }
        } catch (JSONException e) {
            Log.e(TAG, "broadcast JSON error: " + e.getMessage());
        }
    }

    /** Convenience: broadcast with a plain Map instead of JSONObject. */
    public static void broadcast(String type, Map<String, Object> data) {
        broadcast(type, mapToJson(data));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private JSONObject readBody(IHTTPSession session) {
        try {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);
            String body = files.get("postData");
            if (body != null && !body.isEmpty()) return new JSONObject(body);
        } catch (Exception ignored) {}
        return null;
    }

    private Response jsonResponse(Response.Status status, String json,
                                  Map<String, String> extraHeaders) {
        Response r = newFixedLengthResponse(status, "application/json", json);
        addHeaders(r, extraHeaders);
        return r;
    }

    private void addHeaders(Response r, Map<String, String> headers) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            r.addHeader(e.getKey(), e.getValue());
        }
    }

    private static JSONObject mapToJson(Map<String, Object> map) {
        if (map == null) return new JSONObject();
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, Object> e : map.entrySet()) {
                obj.put(e.getKey(), e.getValue() == null ? JSONObject.NULL : e.getValue());
            }
            return obj;
        } catch (JSONException ex) {
            return new JSONObject();
        }
    }
}
