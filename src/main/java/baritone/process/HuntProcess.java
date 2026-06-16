/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IHuntProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.utils.BaritoneProcessHelper;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks to (and clicks) the Hunt "ball" player-heads.
 * <p>
 * The heads are player-head <i>blocks</i>, and their coordinates are already discovered and saved by
 * the ball-finder LabyMod addon into its waypoints {@code settings.json} (one waypoint per head, with
 * an id prefixed {@code ballfinder_}). Rather than rescan the world, this process reads those
 * coordinates, paths to each head and, when in reach, hits it (left-click) and right-clicks it once,
 * then ignores it and moves on to the next.
 *
 * @author baritone
 */
public final class HuntProcess extends BaritoneProcessHelper implements IHuntProcess {

    private static final int RELOAD_INTERVAL_TICKS = 20;

    private boolean hunting;
    private List<BlockPos> cache = new ArrayList<>();
    /** Coordinates we've already reached/clicked, so we ignore them and move on. */
    private final Set<BlockPos> alreadyHit = new HashSet<>();
    /** Last set of waypoint coordinates loaded from the ball-finder file. */
    private Set<BlockPos> waypoints = new LinkedHashSet<>();
    private int sinceReload;
    private boolean warnedMissingFile;

    public HuntProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void hunt() {
        this.hunting = true;
        this.sinceReload = RELOAD_INTERVAL_TICKS; // force a reload on the next scan
    }

    @Override
    public boolean isHunting() {
        return hunting;
    }

    @Override
    public boolean isActive() {
        if (!hunting) {
            return false;
        }
        scanWorld();
        return !cache.isEmpty();
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        scanWorld();

        if (Baritone.settings().huntAutoAttack.value) {
            hitClosest();
        }

        int radius = Baritone.settings().huntFollowRadius.value;
        Goal goal = new GoalComposite(cache.stream().map(p -> toGoal(p, radius)).toArray(Goal[]::new));
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private Goal toGoal(BlockPos target, int radius) {
        if (radius <= 0) {
            return new GoalGetToBlock(target);
        }
        return new GoalNear(target, radius);
    }

    private void hitClosest() {
        LocalPlayer player = ctx.player();
        if (player == null) {
            return;
        }
        Vec3 eyes = ctx.playerHead();
        double reach = Baritone.settings().huntAttackReach.value;
        BlockPos target = cache.stream()
                .min(Comparator.comparingDouble(p -> distSq(eyes, p)))
                .orElse(null);
        if (target == null) {
            return;
        }
        Vec3 center = Vec3.atCenterOf(target);
        if (eyes.distanceTo(center) > reach) {
            return; // keep walking until we're close enough to reach it
        }
        Minecraft mc = ctx.minecraft();
        if (mc.gameMode == null) {
            return;
        }
        // we've reached this head; from now on ignore it regardless of what happens this tick
        alreadyHit.add(target);

        BlockEntity be = ctx.world().getBlockEntity(target);
        if (!(be instanceof SkullBlockEntity)) {
            return; // already collected / not a head anymore, just move on
        }

        Direction face = Direction.getApproximateNearest(eyes.x - center.x, eyes.y - center.y, eyes.z - center.z);
        // look at the head so the click is aimed at it
        Rotation rotation = RotationUtils.calcRotationFromVec3d(eyes, center, ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(rotation, true);

        // left-click (hit) the head
        mc.gameMode.startDestroyBlock(target, face);
        player.swing(InteractionHand.MAIN_HAND);
        // right-click the head once, just to make sure
        BlockHitResult hit = new BlockHitResult(center, face, target, false);
        mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
    }

    private static double distSq(Vec3 from, BlockPos pos) {
        double dx = (pos.getX() + 0.5) - from.x;
        double dy = (pos.getY() + 0.5) - from.y;
        double dz = (pos.getZ() + 0.5) - from.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private void scanWorld() {
        if (++sinceReload >= RELOAD_INTERVAL_TICKS) {
            sinceReload = 0;
            waypoints = loadWaypoints();
        }
        List<BlockPos> list = new ArrayList<>();
        int maxDist = Baritone.settings().huntTargetMaxDistance.value;
        Vec3 eyes = ctx.player() == null ? null : ctx.playerHead();
        for (BlockPos pos : waypoints) {
            if (alreadyHit.contains(pos)) {
                continue;
            }
            if (maxDist != 0 && eyes != null && distSq(eyes, pos) > (double) maxDist * maxDist) {
                continue;
            }
            list.add(pos);
        }
        cache = list;
    }

    /**
     * Reads the ball-finder waypoints file and returns the block coordinate of every head waypoint
     * in the current dimension.
     */
    private Set<BlockPos> loadWaypoints() {
        Set<BlockPos> result = new LinkedHashSet<>();
        Path file = resolveWaypointsFile();
        if (file == null || !Files.isRegularFile(file)) {
            if (!warnedMissingFile) {
                warnedMissingFile = true;
                logDirect("Hunt: ball-finder waypoints file not found" + (file == null ? "" : " at " + file));
            }
            return result;
        }
        warnedMissingFile = false;
        String currentDim = currentDimension();
        try {
            String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return result;
            }
            JsonElement wps = root.getAsJsonObject().get("waypoints");
            if (wps == null || !wps.isJsonArray()) {
                return result;
            }
            String prefix = Baritone.settings().huntWaypointPrefix.value;
            for (JsonElement el : wps.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject wp = el.getAsJsonObject();
                if (prefix != null && !prefix.isEmpty()) {
                    JsonElement id = wp.get("id");
                    if (id == null || !id.getAsString().startsWith(prefix)) {
                        continue;
                    }
                }
                if (currentDim != null && wp.has("dimension") && !currentDim.equals(wp.get("dimension").getAsString())) {
                    continue;
                }
                JsonElement loc = wp.get("location");
                if (loc == null || !loc.isJsonObject()) {
                    continue;
                }
                JsonObject l = loc.getAsJsonObject();
                if (!l.has("x") || !l.has("y") || !l.has("z")) {
                    continue;
                }
                BlockPos pos = new BlockPos(
                        (int) Math.floor(l.get("x").getAsDouble()),
                        (int) Math.floor(l.get("y").getAsDouble()),
                        (int) Math.floor(l.get("z").getAsDouble())
                );
                result.add(pos);
            }
        } catch (Exception e) {
            if (!warnedMissingFile) {
                warnedMissingFile = true;
                logDirect("Hunt: failed to read waypoints file: " + e.getMessage());
            }
        }
        return result;
    }

    private Path resolveWaypointsFile() {
        String override = Baritone.settings().huntWaypointsFile.value;
        if (override != null && !override.trim().isEmpty()) {
            return Paths.get(override.trim());
        }
        String appdata = System.getenv("APPDATA");
        Path base = (appdata != null && !appdata.isEmpty())
                ? Paths.get(appdata)
                : Paths.get(System.getProperty("user.home"), "AppData", "Roaming");
        return base.resolve(".labymod").resolve("labymod-neo").resolve("configs")
                .resolve("labyswaypoints").resolve("settings.json");
    }

    private String currentDimension() {
        try {
            return ctx.world().dimension().identifier().toString();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<String> debugNearby(double range) {
        List<String> lines = new ArrayList<>();
        Path file = resolveWaypointsFile();
        Set<BlockPos> wps = loadWaypoints();
        lines.add("waypoints file: " + (file == null ? "?" : file.toString()));
        lines.add("dimension: " + currentDimension() + ", loaded heads: " + wps.size()
                + ", alreadyHit: " + alreadyHit.size());
        if (ctx.player() == null) {
            return lines;
        }
        Vec3 eyes = ctx.playerHead();
        wps.stream()
                .sorted(Comparator.comparingDouble(p -> distSq(eyes, p)))
                .limit(10)
                .forEach(p -> {
                    boolean skull = ctx.world().getBlockEntity(p) instanceof SkullBlockEntity;
                    lines.add(String.format("(%d,%d,%d) %.0fm skullLoaded=%s hit=%s",
                            p.getX(), p.getY(), p.getZ(), Math.sqrt(distSq(eyes, p)),
                            skull, alreadyHit.contains(p)));
                });
        return lines;
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    @Override
    public void onLostControl() {
        hunting = false;
        cache = new ArrayList<>();
        alreadyHit.clear();
    }

    @Override
    public String displayName0() {
        return "Hunting " + cache.size() + " head(s)";
    }
}
