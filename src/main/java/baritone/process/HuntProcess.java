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
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.process.IHuntProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.utils.BaritoneProcessHelper;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Walks to (and optionally attacks) the "Hunt" player-heads.
 * <p>
 * A target is any living entity that either wears / is rendered with the head texture configured by
 * {@code Settings#huntTextureHash}, or whose display name matches {@code Settings#huntName}
 * (optionally requiring aqua coloring). When in reach, and if {@code Settings#huntAutoAttack} is on,
 * the target is hit automatically.
 *
 * @author baritone
 */
public final class HuntProcess extends BaritoneProcessHelper implements IHuntProcess {

    private static final TextColor AQUA = TextColor.fromLegacyFormat(ChatFormatting.AQUA);

    private boolean hunting;
    private List<Entity> cache;

    public HuntProcess(Baritone baritone) {
        super(baritone);
    }

    @Override
    public void hunt() {
        this.hunting = true;
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
            attackClosest();
        }

        int radius = Baritone.settings().huntFollowRadius.value;
        Goal goal = new GoalComposite(cache.stream().map(e -> toGoal(e, radius)).toArray(Goal[]::new));
        return new PathingCommand(goal, PathingCommandType.REVALIDATE_GOAL_AND_PATH);
    }

    private Goal toGoal(Entity target, int radius) {
        if (radius <= 0) {
            return new GoalBlock(target.blockPosition());
        }
        return new GoalNear(target.blockPosition(), radius);
    }

    private void attackClosest() {
        Player player = ctx.player();
        if (player == null) {
            return;
        }
        double reach = Baritone.settings().huntAttackReach.value;
        Entity target = cache.stream()
                .min(Comparator.comparingDouble(e -> e.distanceToSqr(player)))
                .orElse(null);
        if (target == null) {
            return;
        }
        // closest point of the target's hitbox to the player's eyes
        Vec3 eyes = ctx.playerHead();
        Vec3 aim = closestPointOnBox(target, eyes);
        if (eyes.distanceTo(aim) > reach) {
            return;
        }
        // face the target so the swing (and the server-side hit) lands
        Rotation rotation = RotationUtils.calcRotationFromVec3d(eyes, aim, ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(rotation, false);

        if (player.getAttackStrengthScale(0.0F) < 1.0F) {
            return; // wait for the attack cooldown to recharge for a full-strength hit
        }
        Minecraft mc = ctx.minecraft();
        if (mc.gameMode == null) {
            return;
        }
        mc.gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private static Vec3 closestPointOnBox(Entity entity, Vec3 from) {
        var box = entity.getBoundingBox();
        double x = clamp(from.x, box.minX, box.maxX);
        double y = clamp(from.y, box.minY, box.maxY);
        double z = clamp(from.z, box.minZ, box.maxZ);
        return new Vec3(x, y, z);
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : (v > max ? max : v);
    }

    private void scanWorld() {
        cache = ctx.entitiesStream()
                .filter(this::huntable)
                .filter(this::matches)
                .distinct()
                .collect(Collectors.toList());
    }

    private boolean huntable(Entity entity) {
        if (entity == null || !entity.isAlive()) {
            return false;
        }
        if (entity == ctx.player()) {
            return false;
        }
        if (!(entity instanceof LivingEntity)) {
            return false;
        }
        int maxDist = Baritone.settings().huntTargetMaxDistance.value;
        if (maxDist != 0 && entity.distanceToSqr(ctx.player()) > (double) maxDist * maxDist) {
            return false;
        }
        return true;
    }

    /**
     * Matches a Hunt target either by its head texture or by its (aqua) name.
     */
    private boolean matches(Entity entity) {
        return matchesTexture(entity) || matchesName(entity);
    }

    private boolean matchesTexture(Entity entity) {
        String hash = Baritone.settings().huntTextureHash.value;
        if (hash == null || hash.isEmpty()) {
            return false;
        }
        // a player entity carries its skin texture on its own profile
        if (entity instanceof Player player && profileHasTexture(player.getGameProfile(), hash)) {
            return true;
        }
        // anything else (e.g. an armor stand) may be wearing a player-head item
        if (entity instanceof LivingEntity living) {
            ItemStack head = living.getItemBySlot(EquipmentSlot.HEAD);
            ResolvableProfile profile = head.get(DataComponents.PROFILE);
            if (profile != null && profileHasTexture(profile.partialProfile(), hash)) {
                return true;
            }
        }
        return false;
    }

    private static boolean profileHasTexture(GameProfile profile, String hash) {
        if (profile == null) {
            return false;
        }
        for (Property property : profile.getProperties().get("textures")) {
            String value = property.value();
            if (value == null || value.isEmpty()) {
                continue;
            }
            String decoded;
            try {
                decoded = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                decoded = value; // not base64, match against the raw value just in case
            }
            if (decoded.contains(hash) || value.contains(hash)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesName(Entity entity) {
        String wanted = Baritone.settings().huntName.value;
        if (wanted == null || wanted.isEmpty()) {
            return false;
        }
        Component name = entity.getCustomName();
        if (name == null) {
            name = entity.getName();
        }
        if (name == null || !name.getString().contains(wanted)) {
            return false;
        }
        return !Baritone.settings().huntNameColorAqua.value || hasAquaColor(name);
    }

    private static boolean hasAquaColor(Component component) {
        if (component.getStyle() != null && AQUA.equals(component.getStyle().getColor())) {
            return true;
        }
        for (Component sibling : component.getSiblings()) {
            if (hasAquaColor(sibling)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isTemporary() {
        return false;
    }

    @Override
    public void onLostControl() {
        hunting = false;
        cache = null;
    }

    @Override
    public String displayName0() {
        return "Hunting " + (cache == null ? "" : cache.size() + " target(s)");
    }
}
