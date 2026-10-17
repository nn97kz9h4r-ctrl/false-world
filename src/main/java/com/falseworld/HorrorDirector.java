package com.falseworld;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import java.util.Random;
import java.util.UUID;

/** Pure server-side Mimic state machine. No client mod required. */
public final class HorrorDirector {
    public enum Mode { DORMANT, OBSERVING, STALKING, CHASING, COOLDOWN }
    public record Snapshot(Mode mode, Vec3 position, float yaw, UUID stolenIdentity,
                           int lifetime, int cooldown, int attacks) {}
    private final Random random = new Random();
    private Mode mode = Mode.DORMANT;
    private Vec3 position = Vec3.ZERO;
    private UUID stolenIdentity;
    private int lifetime, cooldown, attacks;
    private float yaw;

    public Snapshot snapshot() { return new Snapshot(mode, position, yaw, stolenIdentity, lifetime, cooldown, attacks); }
    public void reset() { mode=Mode.DORMANT; stolenIdentity=null; lifetime=0; cooldown=0; attacks=0; }
    public boolean active() { return mode == Mode.OBSERVING || mode == Mode.STALKING || mode == Mode.CHASING; }

    public void begin(ServerPlayer target, UUID identity, int phase) {
        if (mode != Mode.DORMANT || identity == null) return;
        stolenIdentity=identity;
        double a=random.nextDouble()*Math.PI*2;
        double d=10+random.nextDouble()*8;
        position=findGround(target.level(), target.position().add(Math.cos(a)*d, 0, Math.sin(a)*d));
        mode=phase>=3 ? Mode.STALKING : Mode.OBSERVING;
        lifetime=20*(7+random.nextInt(8));
    }

    /** Returns true only when a chase has reached melee range and cooldown elapsed. */
    private static boolean clear(Level level, Vec3 point) {
        BlockPos feet = BlockPos.containing(point);
        return level.isLoaded(feet) && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }
    private static Vec3 findGround(Level level, Vec3 start) {
        BlockPos pos = BlockPos.containing(start);
        for (int dy = 5; dy >= -8; dy--) {
            BlockPos candidate = pos.offset(0, dy, 0);
            if (clear(level, Vec3.atBottomCenterOf(candidate)) &&
                !level.getBlockState(candidate.below()).getCollisionShape(level, candidate.below()).isEmpty())
                return Vec3.atBottomCenterOf(candidate);
        }
        return start;
    }
    public boolean tick(ServerPlayer target, int phase) {
        if (mode==Mode.DORMANT) return false;
        if (mode==Mode.COOLDOWN) {
            if (--cooldown<=0) reset();
            return false;
        }
        if (--lifetime<=0 || !target.isAlive() || position.distanceToSqr(target.position())>80*80) {
            mode=Mode.COOLDOWN; cooldown=20*12; return false;
        }
        Vec3 delta=target.position().subtract(position);
        double distance=delta.length();
        yaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
        if (mode==Mode.OBSERVING && phase>=2 && lifetime<20*7) mode=Mode.STALKING;
        if (mode==Mode.STALKING && phase>=3 && distance<14) mode=Mode.CHASING;
        if (mode==Mode.STALKING || mode==Mode.CHASING) {
            double speed=mode==Mode.CHASING ? .24 : .105;
            if (distance>1.5) {
                Vec3 step = delta.normalize().scale(Math.min(speed,distance));
                Vec3 direct = position.add(step);
                if (clear(target.level(), direct)) position = direct;
                else {
                    Vec3 left = position.add(-step.z, 0, step.x);
                    Vec3 right = position.add(step.z, 0, -step.x);
                    if (clear(target.level(), left)) position = left;
                    else if (clear(target.level(), right)) position = right;
                }
            }
        }
        if (mode==Mode.CHASING && distance<2.2 && cooldown<=0 && attacks<2) {
            attacks++; cooldown=20*3;
            return true;
        }
        if (cooldown>0 && mode!=Mode.COOLDOWN) cooldown--;
        return false;
    }
}
