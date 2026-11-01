package com.luckystar.misakigoryo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Original Misaki poses; the offline review renderer reads the same JSON. */
public final class MisakiSpiritMotion {
    private static final Vec ZERO = new Vec(0, 0, 0);
    private final Map<String, Clip> clips = new LinkedHashMap<>();
    private final float walkAmplitude;
    private final float walkSpeed;

    public record Vec(float x, float y, float z) {
        Vec plus(Vec other) { return new Vec(x + other.x, y + other.y, z + other.z); }
        static Vec lerp(Vec left, Vec right, float amount) {
            return new Vec(left.x + (right.x - left.x) * amount,
                left.y + (right.y - left.y) * amount, left.z + (right.z - left.z) * amount);
        }
    }

    public record Pose(Map<String, Vec> rotations, Map<String, Vec> translations, Vec rootTranslation,
                       boolean closedEyes, boolean swordDrawn, boolean visible) {
        public Vec rotation(String bone) { return rotations.getOrDefault(bone, ZERO); }
        public Vec translation(String bone) { return translations.getOrDefault(bone, ZERO); }
    }

    private record Frame(float tick, Map<String, Vec> rotations, Map<String, Vec> translations, Vec translation,
                         boolean closedEyes, boolean swordDrawn, boolean visible) {}
    private record Clip(float duration, boolean loop, List<Frame> frames) {}

    public MisakiSpiritMotion() {
        JsonObject data;
        try (var stream = MisakiSpiritMotion.class.getResourceAsStream(
                "/assets/misaki_goryo/misaki_rig/misaki_motion.json")) {
            if (stream == null) throw new IllegalStateException("Missing Misaki motion contract");
            data = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (java.io.IOException error) {
            throw new IllegalStateException("Cannot read Misaki motion contract", error);
        }
        if (data.get("schemaVersion").getAsInt() != 1) throw new IllegalStateException("Unsupported Misaki motion schema");
        for (var entry : data.getAsJsonObject("clips").entrySet()) {
            JsonObject source = entry.getValue().getAsJsonObject();
            var frames = new ArrayList<Frame>();
            for (var element : source.getAsJsonArray("frames")) {
                var frame = element.getAsJsonObject();
                var rotations = new LinkedHashMap<String, Vec>();
                for (var rotation : frame.getAsJsonObject("rotations").entrySet()) {
                    rotations.put(rotation.getKey(), vector(rotation.getValue().getAsJsonArray()));
                }
                var translations = new LinkedHashMap<String, Vec>();
                for (var translation : frame.getAsJsonObject("translations").entrySet()) {
                    translations.put(translation.getKey(), vector(translation.getValue().getAsJsonArray()));
                }
                frames.add(new Frame(frame.get("tick").getAsFloat(), Map.copyOf(rotations), Map.copyOf(translations),
                    vector(frame.getAsJsonArray("rootTranslation")), frame.get("closedEyes").getAsBoolean(),
                    frame.get("swordDrawn").getAsBoolean(), frame.get("visible").getAsBoolean()));
            }
            float duration = source.get("durationTicks").getAsFloat();
            if (frames.size() < 2 || frames.size() > 32 || duration <= 0 || duration > 240
                    || frames.get(0).tick != 0 || frames.get(frames.size() - 1).tick != duration) {
                throw new IllegalStateException("Invalid bounded Misaki motion: " + entry.getKey());
            }
            for (int index = 1; index < frames.size(); index++) {
                if (frames.get(index).tick <= frames.get(index - 1).tick) {
                    throw new IllegalStateException("Unordered Misaki keyframes");
                }
            }
            clips.put(entry.getKey(), new Clip(duration, source.get("loop").getAsBoolean(), List.copyOf(frames)));
        }
        if (!clips.containsKey("idle") || clips.size() > 12) throw new IllegalStateException("Invalid Misaki clip collection");
        var walk = data.getAsJsonObject("walk");
        walkAmplitude = walk.get("amplitudeDegrees").getAsFloat();
        walkSpeed = walk.get("radiansPerTick").getAsFloat();
    }

    public Pose sample(String action, float actionTick, float idleTick, boolean blink,
                       float headYaw, float headPitch, float walkAmount) {
        Pose idle = sampleClip("idle", idleTick);
        var rotations = new LinkedHashMap<>(idle.rotations);
        var translations = new LinkedHashMap<>(idle.translations);
        Vec translation = idle.rootTranslation;
        boolean closed = idle.closedEyes;
        boolean sword = idle.swordDrawn;
        boolean visible = idle.visible;
        if (!action.equals("idle") && clips.containsKey(action)) {
            Pose event = sampleClip(action, actionTick);
            event.rotations.forEach((name, rotation) ->
                rotations.put(name, rotations.getOrDefault(name, ZERO).plus(rotation)));
            event.translations.forEach((name, translationOffset) ->
                translations.put(name, translations.getOrDefault(name, ZERO).plus(translationOffset)));
            translation = translation.plus(event.rootTranslation);
            closed = event.closedEyes;
            sword = event.swordDrawn;
            visible = event.visible;
        }
        Vec gaze = new Vec(clamp(headPitch, -30, 30), clamp(headYaw, -75, 75), 0);
        rotations.put("head", rotations.getOrDefault("head", ZERO).plus(gaze));
        float stride = (float)Math.sin(idleTick * walkSpeed) * walkAmplitude * clamp(walkAmount, 0, 1);
        for (String bone : List.of("right_arm", "left_arm", "right_leg", "left_leg")) {
            float sign = bone.equals("right_arm") || bone.equals("left_leg") ? 1 : -1;
            rotations.put(bone, rotations.getOrDefault(bone, ZERO).plus(new Vec(sign * stride, 0, 0)));
        }
        return new Pose(Map.copyOf(rotations), Map.copyOf(translations), translation, closed || blink, sword, visible);
    }

    private Pose sampleClip(String name, float tick) {
        Clip clip = clips.get(name);
        tick = clip.loop ? ((tick % clip.duration) + clip.duration) % clip.duration : clamp(tick, 0, clip.duration);
        Frame left = clip.frames.get(0);
        Frame right = clip.frames.get(clip.frames.size() - 1);
        for (int index = 1; index < clip.frames.size(); index++) {
            right = clip.frames.get(index);
            if (right.tick > tick) break;
            left = right;
        }
        float amount = right.tick == left.tick ? 0 : (tick - left.tick) / (right.tick - left.tick);
        var rotations = new LinkedHashMap<String, Vec>();
        for (String bone : left.rotations.keySet()) {
            rotations.put(bone, Vec.lerp(left.rotations.get(bone), right.rotations.getOrDefault(bone, ZERO), amount));
        }
        for (String bone : right.rotations.keySet()) {
            if (!rotations.containsKey(bone)) rotations.put(bone,
                Vec.lerp(ZERO, right.rotations.get(bone), amount));
        }
        var translations = new LinkedHashMap<String, Vec>();
        for (String bone : left.translations.keySet()) {
            translations.put(bone, Vec.lerp(left.translations.get(bone), right.translations.getOrDefault(bone, ZERO), amount));
        }
        for (String bone : right.translations.keySet()) {
            if (!translations.containsKey(bone)) translations.put(bone,
                Vec.lerp(ZERO, right.translations.get(bone), amount));
        }
        return new Pose(Map.copyOf(rotations), Map.copyOf(translations), Vec.lerp(left.translation, right.translation, amount),
            left.closedEyes, left.swordDrawn, left.visible);
    }

    private static Vec vector(JsonArray values) {
        return new Vec(values.get(0).getAsFloat(), values.get(1).getAsFloat(), values.get(2).getAsFloat());
    }
    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
