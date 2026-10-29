package dev.muguet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

final class TouchControls extends View {
    static final class Input {
        final HashSet<String> keys = new HashSet<>();
        int buttons;
        boolean horn;
    }
    private static final class Button {
        final float x, y, radius;
        final String label, action;
        Button(float x, float y, float radius, String label, String action) {
            this.x = x; this.y = y; this.radius = radius; this.label = label; this.action = action;
        }
    }
    private static final class Grip {
        int button;
        float startX, startY, x, y;
        Grip(int button, float x, float y) { this.button = button; startX = this.x = x; startY = this.y = y; }
    }
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Button> buttons = new ArrayList<>();
    private final HashMap<Integer, Grip> grips = new HashMap<>();
    private final HashMap<String, Long> keyUntil = new HashMap<>();
    private long fireUntil;
    private final float lookSensitivity;
    private final float density;
    private final float hudScale, hudOpacity;
    private final boolean compactHud;
    private float left, top, right, bottom;
    private boolean inCar, aim, nav;
    private static final float RADIUS = 58;

    // lookSensitivity scales camera drag look and select-wheel drag; 1 matches the shipped tuning.
    TouchControls(Context context, float lookSensitivity) {
        super(context);
        this.lookSensitivity = lookSensitivity;
        density = getResources().getDisplayMetrics().density;
        JSONObject config;
        try {
            File file = new File(context.getFilesDir(), "config.json");
            config = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            config = new JSONObject();
        }
        hudScale = clamp((float) config.optDouble("nightlane_hud_scale", 0.85), 0.75f, 1.20f);
        hudOpacity = clamp((float) config.optDouble("nightlane_hud_opacity", 0.16), 0.12f, 0.55f);
        compactHud = config.optBoolean("nightlane_compact_hud", true);
        setFocusable(false);
    }
    private static float clamp(float value, float low, float high) {
        return Float.isFinite(value) ? Math.max(low, Math.min(high, value)) : low;
    }
    void setInCar(boolean value) { release(); inCar = value; layoutButtons(); invalidate(); }
    void release() { grips.clear(); keyUntil.clear(); fireUntil = 0; aim = false; invalidate(); }
    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) { release(); layoutButtons(); }
    @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) { release(); layoutButtons(); return super.onApplyWindowInsets(insets); }
    private void layoutButtons() {
        WindowInsets insets = getRootWindowInsets();
        android.graphics.Insets safe = android.graphics.Insets.NONE;
        if (insets != null) {
            if (android.os.Build.VERSION.SDK_INT >= 30) safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            else {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                safe = android.graphics.Insets.of(Math.max(insets.getSystemWindowInsetLeft(), cutout == null ? 0 : cutout.getSafeInsetLeft()),
                    Math.max(insets.getSystemWindowInsetTop(), cutout == null ? 0 : cutout.getSafeInsetTop()),
                    Math.max(insets.getSystemWindowInsetRight(), cutout == null ? 0 : cutout.getSafeInsetRight()),
                    Math.max(insets.getSystemWindowInsetBottom(), cutout == null ? 0 : cutout.getSafeInsetBottom()));
            }
        }
        left = safe.left / density; top = safe.top / density;
        right = (getWidth() - safe.right) / density; bottom = (getHeight() - safe.bottom) / density;
        buttons.clear();
        float k = hudScale;
        // Keep the movement and driving controls in the bottom corners.
        // Both the hit target and the painted circle use the same scale.
        if (!inCar) add(left + (right - left) * 0.33f, bottom - 68*k, 36*k, "FIRE", "fire");
        add(right - 62*k, bottom - 68*k, 40*k, inCar ? "GAS" : "FIRE", inCar ? "KeyW" : "fire");
        add(right - 158*k, bottom - 46*k, 31*k, inCar ? "BRAKE" : "AIM", inCar ? "KeyS" : "aim");
        add(right - 58*k, bottom - 168*k, 28*k, inCar ? "H.BRAKE" : "JUMP", "Space");
        add(right - 140*k, bottom - 132*k, 26*k, inCar ? "EXIT" : "ENTER", "KeyF");
        add(right - 222*k, bottom - 98*k, 24*k, inCar ? "HORN" : "RELOAD", inCar ? "horn" : "KeyR");
        // Tutorial text occupies the very top of the screen. Move the toolbar
        // below it and collapse secondary buttons behind MORE in compact mode.
        float toolbarY = top + 62*k;
        if (compactHud) {
            add(left + 36*k, toolbarY, 20*k, "PAUSE", "Escape");
            add(left + 94*k, toolbarY, 20*k, nav ? "LESS" : "MORE", "nav");
            add(right - 36*k, toolbarY, 20*k, "WEAPON", "Tab");
            add(right - 94*k, toolbarY, 20*k, "CAM", "KeyV");
            if (nav) {
                float sub = toolbarY + 54*k;
                add(left + 36*k, sub, 19*k, "PHONE", "ArrowUp");
                add(left + 94*k, sub, 19*k, "SWITCH", "AltLeft");
                add(right - 36*k, sub, 19*k, inCar ? "RADIO" : "COVER", "KeyQ");
                add(right - 94*k, sub, 19*k, inCar ? "LOOK BACK" : "CROUCH", inCar ? "KeyC" : "ControlLeft");
            }
        } else {
            add(right - 30*k, toolbarY, 22*k, "WEAPON", "Tab");
            add(right - 84*k, toolbarY, 22*k, inCar ? "RADIO" : "COVER", "KeyQ");
            add(right - 138*k, toolbarY, 22*k, inCar ? "LOOK BACK" : "CROUCH", inCar ? "KeyC" : "ControlLeft");
            add(right - 192*k, toolbarY, 22*k, "CAM", "KeyV");
            add(left + 30*k, toolbarY, 22*k, "PAUSE", "Escape");
            add(left + 84*k, toolbarY, 22*k, "PHONE", "ArrowUp");
            add(left + 138*k, toolbarY, 22*k, nav ? "LESS" : "MENU", "nav");
            add(left + 192*k, toolbarY, 22*k, "SWITCH", "AltLeft");
        }
        if (nav) {
            String[] labels = {"◀", "▲", "▼", "▶", "OK", "BACK"};
            String[] keys = {"ArrowLeft", "ArrowUp", "ArrowDown", "ArrowRight", "Enter", "Backspace"};
            float span = 46*k;
            float first = (left + right) * 0.5f - 2.5f * span;
            float navY = toolbarY + (compactHud ? 108 : 54)*k;
            for (int i = 0; i < keys.length; i++) {
                add(first + i * span, navY, 19*k, labels[i], keys[i]);
            }
        }
    }
    private void add(float x, float y, float radius, String text, String action) { buttons.add(new Button(x, y, radius, text, action)); }
    private int buttonAt(float x, float y) {
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i);
            if (Math.hypot(x - b.x, y - b.y) <= b.radius + 8) return i;
        }
        return -1;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked(), index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float x = event.getX(index) / density, y = event.getY(index) / density;
            int button = buttonAt(x, y);
            if (button >= 0) {
                String a = buttons.get(button).action;
                if (a.equals("aim")) aim = !aim;
                else if (a.equals("nav")) { grips.clear(); nav = !nav; layoutButtons(); button = -1; }
                else if (a.equals("fire")) fireUntil = System.nanoTime() + 120_000_000L;
                else if (!a.equals("horn")) keyUntil.put(a, System.nanoTime() + 120_000_000L);
            } else if (x < left + (right - left) * 0.4 && y > top + 110 && grips.values().stream().noneMatch(g -> g.button == -2)) button = -2;
            grips.put(event.getPointerId(index), new Grip(button, x, y));
        } else if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                Grip grip = grips.get(event.getPointerId(i));
                if (grip == null) continue;
                float x = event.getX(i) / density, y = event.getY(i) / density;
                if (grip.button != -2 && !isWheel(grip)) GameActivity.nativeLook((x - grip.x) * 3 * lookSensitivity, (y - grip.y) * 3 * lookSensitivity);
                grip.x = x; grip.y = y;
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) grips.remove(event.getPointerId(index));
        else if (action == MotionEvent.ACTION_CANCEL) release();
        invalidate(); return true;
    }
    private boolean isWheel(Grip grip) {
        if (grip.button < 0 || grip.button >= buttons.size()) return false;
        String a = buttons.get(grip.button).action;
        return a.equals("Tab") || a.equals("AltLeft") || (inCar && a.equals("KeyQ"));
    }
    Input input() {
        Input input = new Input();
        long now = System.nanoTime();
        keyUntil.entrySet().removeIf(e -> e.getValue() <= now);
        input.keys.addAll(keyUntil.keySet());
        if (fireUntil > now) input.buttons |= 1;
        if (aim && !inCar) input.buttons |= 2;
        for (Grip g : grips.values()) {
            if (g.button == -2) {
                float dx = (g.x - g.startX) / (RADIUS*hudScale), dy = (g.y - g.startY) / (RADIUS*hudScale);
                if (dx < -0.35) input.keys.add("KeyA"); if (dx > 0.35) input.keys.add("KeyD");
                if (dy < -0.35) input.keys.add(inCar ? "Numpad8" : "KeyW"); if (dy > 0.35) input.keys.add(inCar ? "Numpad2" : "KeyS");
                if (!inCar && Math.hypot(dx, dy) > 0.95) input.keys.add("ShiftLeft");
            } else if (g.button >= 0 && g.button < buttons.size()) {
                String a = buttons.get(g.button).action;
                if (a.equals("fire")) input.buttons |= 1;
                else if (a.equals("horn")) input.horn = true;
                else if (!a.equals("aim") && !a.equals("nav")) input.keys.add(a);
                if (isWheel(g)) {
                    float dx = g.x - g.startX, dy = g.y - g.startY;
                    double length = Math.hypot(dx, dy);
                    if (length > 10) {
                        float push = (float) (40 * lookSensitivity * Math.min((length - 10) / 45, 1) / length);
                        GameActivity.nativeLook(dx * push, dy * push);
                    }
                }
            }
        }
        return input;
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.save(); canvas.scale(density, density);
        float sx = left + 98*hudScale, sy = bottom - 88*hudScale, dx = 0, dy = 0;
        float stickRadius = RADIUS * hudScale;
        for (Grip g : grips.values()) if (g.button == -2) {
            sx = g.startX; sy = g.startY; dx = g.x - sx; dy = g.y - sy;
            double length = Math.hypot(dx, dy); if (length > stickRadius) { dx *= stickRadius / length; dy *= stickRadius / length; }
        }
        int idle = (Math.round(hudOpacity * 255) << 24) | 0x00ffffff;
        int activeColor = (Math.round(Math.min(0.85f, hudOpacity * 2.3f) * 255) << 24) | 0x00ffffff;
        paint.setColor(idle); canvas.drawCircle(sx, sy, stickRadius, paint);
        paint.setColor(activeColor); canvas.drawCircle(sx + dx, sy + dy, 26*hudScale, paint);
        for (int i = 0; i < buttons.size(); i++) {
            Button b = buttons.get(i); final int index = i;
            boolean held = grips.values().stream().anyMatch(g -> g.button == index) || (aim && b.action.equals("aim")) || (nav && b.action.equals("nav"));
            paint.setColor(held ? activeColor : idle); canvas.drawCircle(b.x, b.y, b.radius, paint);
            paint.setColor(0xeeffffff); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize((b.label.length() > 6 ? 8 : 10) * hudScale);
            canvas.drawText(b.label, b.x, b.y + 4, paint);
        }
        canvas.restore();
    }
}
