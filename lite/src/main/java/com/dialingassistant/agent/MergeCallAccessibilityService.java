package com.dialingassistant.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.ArrayList;
import java.util.List;

/**
 * Call-routing merge helper.
 *
 * When the web "Route" command places a second call, {@link CallBackgroundService}
 * arms this service via {@link #requestMerge()}. It then watches the system in-call
 * screen and, as soon as the carrier exposes a tappable "Merge" control — which only
 * happens once the routed party answers — it clicks it to join the two calls into a
 * conference.
 *
 * Why accessibility and not TelecomManager: merging two carrier calls is
 * {@code android.telecom.Call.conference()}, which is only reachable from the default
 * dialer's bound InCallService. This app is intentionally NOT the default dialer, so
 * an accessibility click on the system UI is the only way to trigger the merge.
 *
 * One-time setup: Settings → Accessibility → (this service) → ON.
 */
public class MergeCallAccessibilityService extends AccessibilityService {

    private static final String TAG = "MergeCallA11y";

    // Substrings identifying the merge BUTTON, matched case-insensitively against a
    // node's text or content-description. On ColorOS the real clickable control is a
    // Button whose contentDescription is like "Not selectedMerge" (the visible "Merge"
    // is a separate NON-clickable label). NOTE: "conference" is deliberately NOT here —
    // "Conference call" is the POST-merge success label, not the button.
    private static final String[] MERGE_SUBSTRINGS = { "merge", "combine" };

    // Substrings that only appear ONCE the calls are already joined — used as the
    // positive success signal (and to avoid tapping the conference title by mistake).
    private static final String[] CONFERENCE_SUBSTRINGS = { "conference", "participant" };

    // Only trust nodes coming from the telephony in-call UI — never systemui, the
    // keyguard, or the launcher (on this ColorOS device getRootInActiveWindow() often
    // returns the keyguard window during a call, which is where the earlier phantom
    // "merge" click came from). Matched as a substring of the window root's package.
    private static final String[] INCALL_PACKAGES = {
            "incallui", "dialer", "telecom", "phone", "contacts", "oplus", "coloros", "oppo"
    };

    // Set true to log every clickable node across the in-call window(s) on each scan.
    // Leave on until the merge matcher is confirmed on-device, then flip off.
    private static final boolean DIAG = true;

    // Keep looking for the merge button this long after being armed. If the routed
    // party never answers, the button never appears and we quietly give up.
    private static final long DEFAULT_TIMEOUT_MS = 60_000L;
    private static final long POLL_INTERVAL_MS   = 500L;
    // Don't re-tap the merge button faster than this — gives the merge time to take
    // effect (the button then disappears) before we'd consider tapping again.
    private static final long TAP_COOLDOWN_MS    = 1_500L;

    // House-first routing (iOS): the target is placed as the SECOND leg only after the
    // FIRST leg (the house number) actually connects. We can't get an "answered" event
    // without being the default dialer, so we infer connection from the in-call UI — a
    // running call-duration timer with no "calling/dialing" text. If we can't confirm
    // within this window we place the call anyway (degrades to the legacy immediate dial).
    private static final long CONNECT_FALLBACK_MS = 15_000L;
    private static final java.util.regex.Pattern CALL_TIMER_RE =
            java.util.regex.Pattern.compile("\\b\\d{1,2}:\\d{2}(:\\d{2})?\\b");
    private static final String[] DIALING_SUBSTRINGS = { "calling", "dialing", "connecting", "ringing" };

    private static volatile MergeCallAccessibilityService sInstance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = this::attemptMerge;

    private volatile boolean mergePending = false;
    private long deadlineMs = 0L;
    private long lastTapMs = 0L;
    private boolean tappedOnce = false;

    // When non-null we are in a house-first episode: run this (which places the second
    // call) once the first leg is detected connected, THEN fall through to merge watching.
    private volatile Runnable onFirstLegConnected = null;
    private boolean firstLegPlaced = false;
    private long connectFallbackAtMs = 0L;

    // ── Public API ──────────────────────────────────────────────────────────

    public static boolean isAvailable() {
        return sInstance != null;
    }

    /**
     * Arm the merge watcher for one routing episode. Returns false if the user has
     * not enabled the accessibility service (so the caller can report that back).
     */
    public static boolean requestMerge() {
        MergeCallAccessibilityService svc = sInstance;
        if (svc == null) {
            Log.w(TAG, "requestMerge: service not enabled");
            return false;
        }
        svc.arm(DEFAULT_TIMEOUT_MS);
        return true;
    }

    /**
     * House-first variant: wait until the current (first) call actually connects, then
     * run {@code onConnected} to place the second leg, then watch for the merge button.
     * Used by the iOS house-first routed dial, where the first leg is still ringing when
     * the route command arrives (so we must NOT place the second call immediately).
     */
    public static boolean requestMergeAfterConnect(Runnable onConnected) {
        MergeCallAccessibilityService svc = sInstance;
        if (svc == null) {
            Log.w(TAG, "requestMergeAfterConnect: service not enabled");
            return false;
        }
        svc.armWaitConnect(onConnected, DEFAULT_TIMEOUT_MS);
        return true;
    }

    private void arm(long timeoutMs) {
        handler.post(() -> {
            onFirstLegConnected = null;   // legacy path: no connect-wait
            firstLegPlaced = false;
            mergePending = true;
            deadlineMs = System.currentTimeMillis() + timeoutMs;
            lastTapMs = 0L;
            tappedOnce = false;
            reportStatus("routing");
            handler.removeCallbacks(pollRunnable);
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
            Log.d(TAG, "armed for merge, timeout=" + timeoutMs + "ms");
        });
    }

    private void armWaitConnect(Runnable onConnected, long timeoutMs) {
        handler.post(() -> {
            onFirstLegConnected = onConnected;
            firstLegPlaced = false;
            connectFallbackAtMs = System.currentTimeMillis() + CONNECT_FALLBACK_MS;
            mergePending = true;
            // The connect-wait gets its own budget on top of the merge-watch window.
            deadlineMs = System.currentTimeMillis() + CONNECT_FALLBACK_MS + timeoutMs;
            lastTapMs = 0L;
            tappedOnce = false;
            reportStatus("routing");
            handler.removeCallbacks(pollRunnable);
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
            Log.d(TAG, "armed for connect-then-merge");
        });
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────

    @Override
    protected void onServiceConnected() {
        sInstance = this;
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 100;
        setServiceInfo(info);
        Log.d(TAG, "Service connected");
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        handler.removeCallbacks(pollRunnable);
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // The in-call UI content changes when the routed call connects; re-scan
        // immediately rather than waiting for the next poll tick.
        if (mergePending) attemptMerge();
    }

    // ── Merge flow ──────────────────────────────────────────────────────────

    private void attemptMerge() {
        if (!mergePending) return;

        if (System.currentTimeMillis() > deadlineMs) {
            Log.w(TAG, tappedOnce ? "merge timed out — button was tapped but never confirmed"
                                  : "merge timed out — no merge button appeared");
            finish(tappedOnce ? "clicked_unconfirmed" : "timeout");
            return;
        }

        // Gather the roots of every in-call window. On ColorOS the dialer's in-call UI
        // is NOT the active window during a call (the keyguard/systemui is), so a single
        // getRootInActiveWindow() misses it — we must walk getWindows().
        List<AccessibilityNodeInfo> roots = collectInCallRoots();
        if (DIAG) dumpCandidates(roots);

        // House-first pre-step: hold the second leg until the first call connects. There
        // is no merge button to look for until that second call exists, so we return each
        // tick until either the first leg connects or the connect-wait budget elapses.
        if (onFirstLegConnected != null && !firstLegPlaced) {
            boolean connected = isCallConnected(roots);
            boolean fallback = System.currentTimeMillis() >= connectFallbackAtMs;
            if (connected || fallback) {
                firstLegPlaced = true;
                Runnable r = onFirstLegConnected;
                onFirstLegConnected = null;
                Log.d(TAG, "first leg " + (connected ? "connected" : "connect-wait timed out")
                        + " → placing routed second call");
                reportStatus(connected ? "connected" : "connect_fallback");
                if (r != null) handler.post(r);
            }
            handler.removeCallbacks(pollRunnable);
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
            return;
        }

        // Positive success signal: a "Conference call" / "N participants" node means the
        // two calls are already joined (whether we merged them or the operator did). Stop
        // here — never tap the conference title (that opens the manage-conference screen).
        for (AccessibilityNodeInfo root : roots) {
            if (findConferenceNode(root) != null) {
                Log.d(TAG, "conference detected — calls joined");
                finish("merged");
                return;
            }
        }

        AccessibilityNodeInfo mergeBtn = null;
        for (AccessibilityNodeInfo root : roots) {
            mergeBtn = findMergeButton(root);
            if (mergeBtn != null) break;
        }

        long now = System.currentTimeMillis();
        if (mergeBtn != null) {
            // The merge control is present. Tap it (respecting a cooldown so a
            // still-visible button isn't hammered every poll while the merge lands).
            if (now - lastTapMs >= TAP_COOLDOWN_MS) {
                tapNode(mergeBtn);
                lastTapMs = now;
                tappedOnce = true;
            }
        }

        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    /** Roots of all windows whose package looks like the telephony in-call UI. */
    private List<AccessibilityNodeInfo> collectInCallRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo win : getWindows()) {
                if (win == null) continue;
                AccessibilityNodeInfo root = win.getRoot();
                if (root == null) continue;
                CharSequence pkg = root.getPackageName();
                if (pkg != null && isInCallPackage(pkg.toString())) {
                    roots.add(root);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "collectInCallRoots failed: " + e.getMessage());
        }
        // Fallback: if nothing matched (unknown OEM package), also try the active window.
        if (roots.isEmpty()) {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null) roots.add(active);
        }
        return roots;
    }

    private boolean isInCallPackage(String pkg) {
        String lower = pkg.toLowerCase();
        // Explicitly exclude the shells that host the keyguard/notification windows.
        if (lower.contains("systemui") || lower.contains("launcher")) return false;
        for (String p : INCALL_PACKAGES) {
            if (lower.contains(p)) return true;
        }
        return false;
    }

    /** Diagnostic: log every clickable node so we can identify the real merge button. */
    private void dumpCandidates(List<AccessibilityNodeInfo> roots) {
        for (AccessibilityNodeInfo root : roots) {
            Log.d(TAG, "── window pkg=" + root.getPackageName() + " ──");
            logClickables(root, 0);
        }
    }

    private void logClickables(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 40) return;
        CharSequence txt = node.getText();
        CharSequence desc = node.getContentDescription();
        if (node.isClickable() || (txt != null && txt.length() > 0) || (desc != null && desc.length() > 0)) {
            Log.d(TAG, "  node text=" + txt + " desc=" + desc
                    + " id=" + node.getViewIdResourceName()
                    + " class=" + node.getClassName()
                    + " clickable=" + node.isClickable()
                    + " enabled=" + node.isEnabled());
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            logClickables(node.getChild(i), depth + 1);
        }
    }

    private void finish(String status) {
        mergePending = false;
        onFirstLegConnected = null;
        handler.removeCallbacks(pollRunnable);
        reportStatus(status);
    }

    // ── Connect detection (house-first pre-step) ──────────────────────────────

    /**
     * Connected = a running call-duration timer is visible somewhere in the in-call UI
     * AND no "calling/dialing/ringing" label is present. Before the far side answers the
     * dialer shows those labels (and usually no ticking timer); once answered a MM:SS
     * timer starts. OEM-variable, so a fallback timeout backs this up (see attemptMerge).
     */
    private boolean isCallConnected(List<AccessibilityNodeInfo> roots) {
        boolean timer = false;
        for (AccessibilityNodeInfo root : roots) {
            if (hasDialingText(root)) return false;              // still ringing / dialing
            if (!timer && findTimerNode(root) != null) timer = true;
        }
        return timer;
    }

    /** DFS for a node whose text is a ticking call timer (MM:SS past 0:00). */
    private AccessibilityNodeInfo findTimerNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        CharSequence t = node.getText();
        if (t != null) {
            java.util.regex.Matcher m = CALL_TIMER_RE.matcher(t.toString().trim());
            if (m.find()) {
                String hit = m.group();
                if (!"0:00".equals(hit) && !"00:00".equals(hit)) return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo f = findTimerNode(node.getChild(i));
            if (f != null) return f;
        }
        return null;
    }

    /** DFS for any "calling/dialing/connecting/ringing" label (call not yet connected). */
    private boolean hasDialingText(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (mentionsAny(node.getText(), DIALING_SUBSTRINGS)
                || mentionsAny(node.getContentDescription(), DIALING_SUBSTRINGS)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (hasDialingText(node.getChild(i))) return true;
        }
        return false;
    }

    private boolean mentionsAny(CharSequence cs, String[] terms) {
        if (cs == null) return false;
        String s = cs.toString().toLowerCase();
        for (String term : terms) {
            if (s.contains(term)) return true;
        }
        return false;
    }

    // ── Node search ─────────────────────────────────────────────────────────

    /**
     * Finds the real, tappable merge control. Prefers a node that is itself clickable
     * and enabled and whose OWN text/desc mentions merge (the ColorOS Button with
     * contentDescription "Not selectedMerge"). Falls back to a merge-labelled node's
     * nearest clickable ancestor for other OEM layouts.
     */
    private AccessibilityNodeInfo findMergeButton(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo direct = findClickableMention(root);
        if (direct != null) return direct;

        AccessibilityNodeInfo label = findMentionNode(root);
        if (label == null) return null;
        AccessibilityNodeInfo ancestor = nearestClickableAncestor(label);
        return (ancestor != null && ancestor.isEnabled()) ? ancestor : null;
    }

    /** DFS for a clickable+enabled node whose own text/desc mentions a merge term. */
    private AccessibilityNodeInfo findClickableMention(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isClickable() && node.isEnabled()
                && (mentionsMerge(node.getContentDescription()) || mentionsMerge(node.getText()))) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findClickableMention(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    /** DFS for any node whose text/desc mentions a merge term. */
    private AccessibilityNodeInfo findMentionNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (mentionsMerge(node.getText()) || mentionsMerge(node.getContentDescription())) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findMentionNode(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private boolean mentionsMerge(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().toLowerCase();
        for (String term : MERGE_SUBSTRINGS) {
            if (s.contains(term)) return true;
        }
        return false;
    }

    /** DFS for a node indicating an active conference (the merge-success signal). */
    private AccessibilityNodeInfo findConferenceNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (mentionsConference(node.getText()) || mentionsConference(node.getContentDescription())) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findConferenceNode(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private boolean mentionsConference(CharSequence cs) {
        if (cs == null) return false;
        String s = cs.toString().toLowerCase();
        for (String term : CONFERENCE_SUBSTRINGS) {
            if (s.contains(term)) return true;
        }
        return false;
    }

    private AccessibilityNodeInfo nearestClickableAncestor(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isClickable()) return node;
        AccessibilityNodeInfo parent = node.getParent();
        int depth = 0;
        while (parent != null && depth < 6) {
            if (parent.isClickable()) return parent;
            parent = parent.getParent();
            depth++;
        }
        return null;
    }

    /**
     * Taps the merge button every way we can, logging the outcome of each so we can see
     * which one actually lands on this device: (1) ACTION_CLICK on the node, (2) on its
     * clickable ancestor, (3) a real dispatched touch at the button centre with a
     * completion/cancellation callback.
     */
    private void tapNode(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        Log.d(TAG, "merge button: desc=" + node.getContentDescription() + " bounds=" + bounds
                + " clickable=" + node.isClickable() + " enabled=" + node.isEnabled());

        boolean click1 = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        AccessibilityNodeInfo anc = nearestClickableAncestor(node);
        boolean click2 = anc != null && anc != node && anc.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        Log.d(TAG, "ACTION_CLICK node=" + click1 + " ancestor=" + click2);

        if (!bounds.isEmpty()) {
            Path path = new Path();
            path.moveTo(bounds.exactCenterX(), bounds.exactCenterY());
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, 90))
                    .build();
            boolean dispatched = dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) { Log.d(TAG, "gesture COMPLETED"); }
                @Override public void onCancelled(GestureDescription g) { Log.w(TAG, "gesture CANCELLED"); }
            }, null);
            Log.d(TAG, "gesture dispatched=" + dispatched + " at "
                    + bounds.exactCenterX() + "," + bounds.exactCenterY());
        }
    }

    // ── Status reporting ────────────────────────────────────────────────────

    /** Publishes progress under device_controls/route_status for the web app to show. */
    private void reportStatus(String status) {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user == null) return;
            new FirestoreManager(user.getUid()).updateDeviceControlField("route_status", status);
        } catch (Exception e) {
            Log.w(TAG, "reportStatus failed: " + e.getMessage());
        }
    }
}
