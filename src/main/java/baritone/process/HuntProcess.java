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
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ResolvableProfile;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
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
    /** UUIDs of heads we've already hit, so we ignore them and move on to the next one. */
    private final Set<UUID> alreadyHit = new HashSet<>();

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
            hitClosest();
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

    private void hitClosest() {
        Player player = ctx.player();
        if (player == null) {
            return;
        }
        double reach = Baritone.settings().huntAttackReach.value;
        // cache already excludes heads we've hit; just grab the nearest remaining one
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
        // face the target so the swing (and the server-side hit/interact) lands
        Rotation rotation = RotationUtils.calcRotationFromVec3d(eyes, aim, ctx.playerRotations());
        baritone.getLookBehavior().updateTarget(rotation, false);

        if (player.getAttackStrengthScale(0.0F) < 1.0F) {
            return; // wait for the attack cooldown to recharge for a full-strength hit
        }
        Minecraft mc = ctx.minecraft();
        if (mc.gameMode == null) {
            return;
        }
        // left-click hit
        mc.gameMode.attack(player, target);
        player.swing(InteractionHand.MAIN_HAND);
        // right-click the head once, just to make sure
        mc.gameMode.interact(player, target, InteractionHand.MAIN_HAND);
        // remember it so we ignore it from now on and move on to the next head
        alreadyHit.add(target.getUUID());
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
        if (alreadyHit.contains(entity.getUUID())) {
            return false; // we've already hit this head, ignore it
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
        for (String tex : texturesOf(entity)) {
            if (tex.contains(hash)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gathers every texture identifier we can find for an entity: the player's rendered skin URL,
     * its game-profile {@code textures} property, and the same for any worn player-head item (all
     * equipment slots). Returns raw and base64-decoded forms so a hash can be matched in either.
     */
    private static Set<String> texturesOf(Entity entity) {
        Set<String> out = new HashSet<>();
        // 1) the entity's own rendered skin (this is where a player-head NPC's texture actually lives)
        if (entity instanceof AbstractClientPlayer acp) {
            PlayerSkin skin = acp.getSkin();
            if (skin != null) {
                collectFromTexture(skin.body(), out);
                collectFromTexture(skin.cape(), out);
            }
        }
        if (entity instanceof Player player) {
            collectProfileTextures(player.getGameProfile(), out);
        }
        // 2) any worn player-head item in any equipment slot
        if (entity instanceof LivingEntity living) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = living.getItemBySlot(slot);
                if (stack.isEmpty()) {
                    continue;
                }
                ResolvableProfile profile = stack.get(DataComponents.PROFILE);
                if (profile != null) {
                    collectProfileTextures(profile.partialProfile(), out);
                    PlayerSkin.Patch patch = profile.skinPatch();
                    if (patch != null) {
                        patch.body().ifPresent(t -> collectFromTexture(t, out));
                    }
                }
            }
        }
        return out;
    }

    private static void collectFromTexture(ClientAsset.Texture texture, Set<String> out) {
        if (texture == null) {
            return;
        }
        if (texture instanceof ClientAsset.DownloadedTexture downloaded && downloaded.url() != null) {
            out.add(downloaded.url());
        }
        if (texture.texturePath() != null) {
            out.add(texture.texturePath().toString());
        }
    }

    private static void collectProfileTextures(GameProfile profile, Set<String> out) {
        if (profile == null) {
            return;
        }
        for (Property property : profile.properties().get("textures")) {
            String value = property.value();
            if (value == null || value.isEmpty()) {
                continue;
            }
            out.add(value);
            try {
                out.add(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // not base64; the raw value is already added above
            }
        }
    }

    /**
     * Diagnostic dump of nearby entities so the user can see exactly what the heads look like
     * (type, name, detected texture hashes) and whether the current filter matches them.
     */
    public List<String> debugNearby(double range) {
        List<String> lines = new ArrayList<>();
        if (ctx.player() == null) {
            return lines;
        }
        double rangeSq = range * range;
        for (Entity entity : ctx.entities()) {
            if (entity == ctx.player() || entity.distanceToSqr(ctx.player()) > rangeSq) {
                continue;
            }
            Set<String> textures = texturesOf(entity);
            Component custom = entity.getCustomName();
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
            // only bother reporting things that carry a texture or a custom name
            if (textures.isEmpty() && custom == null) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%s @%.0fm", type, Math.sqrt(entity.distanceToSqr(ctx.player()))));
            sb.append(" name=").append(entity.getName().getString());
            if (custom != null) {
                sb.append(" custom='").append(custom.getString()).append("'");
            }
            if (!textures.isEmpty()) {
                String hashes = textures.stream()
                        .map(HuntProcess::extractHash)
                        .filter(h -> h != null)
                        .distinct()
                        .collect(Collectors.joining(","));
                sb.append(" tex=").append(hashes.isEmpty() ? "(present)" : hashes);
            }
            sb.append(" match=").append(matches(entity));
            lines.add(sb.toString());
            if (lines.size() >= 30) {
                break;
            }
        }
        return lines;
    }

    /** Pulls the texture hash out of a skin URL / texture path for readable diagnostics. */
    private static String extractHash(String s) {
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash < s.length() - 1) {
            String tail = s.substring(slash + 1).replace("\"", "").replace("}", "");
            if (tail.length() >= 32) {
                return tail;
            }
        }
        return null;
    }

    private boolean matchesName(Entity entity) {
        String wanted = Baritone.settings().huntName.value;
        if (wanted == null || wanted.isEmpty()) {
            return false;
        }
        boolean requireAqua = Baritone.settings().huntNameColorAqua.value;
        for (Component name : new Component[]{entity.getCustomName(), entity.getDisplayName(), entity.getName()}) {
            if (name == null || !name.getString().contains(wanted)) {
                continue;
            }
            if (!requireAqua || hasAquaColor(name)) {
                return true;
            }
        }
        return false;
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
        alreadyHit.clear();
    }

    @Override
    public String displayName0() {
        return "Hunting " + (cache == null ? "" : cache.size() + " target(s)");
    }
}
