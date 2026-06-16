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

package baritone.api.process;

/**
 * Walks to (and optionally attacks) the "Hunt" player-heads used by the Hunt event.
 * <p>
 * Targets are entities that either carry the configured head texture or are named
 * "Hunt" in aqua. See {@code Settings#huntTextureHash}, {@code Settings#huntName} and
 * {@code Settings#huntAutoAttack}.
 */
public interface IHuntProcess extends IBaritoneProcess {

    /**
     * Start hunting. Baritone will path toward every matching target and, if
     * {@code Settings#huntAutoAttack} is set, hit them when in reach.
     */
    void hunt();

    /**
     * @return {@code true} if the hunt has been started (regardless of whether a target is currently visible)
     */
    boolean isHunting();

    /**
     * Stop hunting and release pathing control.
     */
    default void cancel() {
        onLostControl();
    }
}
