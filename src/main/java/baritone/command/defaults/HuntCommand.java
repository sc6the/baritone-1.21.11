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

package baritone.command.defaults;

import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.BaritoneAPI;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.exception.CommandException;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class HuntCommand extends Command {

    public HuntCommand(IBaritone baritone) {
        super(baritone, "hunt");
    }

    @Override
    public void execute(String label, IArgConsumer args) throws CommandException {
        args.requireMax(1);
        if (args.hasAny()) {
            String arg = args.getString().toLowerCase();
            if (arg.equals("stop") || arg.equals("off") || arg.equals("cancel")) {
                baritone.getHuntProcess().cancel();
                logDirect("Hunt stopped");
                return;
            }
            if (arg.equals("scan")) {
                List<String> lines = baritone.getHuntProcess().debugNearby(64.0);
                logDirect("Hunt waypoints loaded from ball-finder:");
                lines.forEach(this::logDirect);
                return;
            }
            if (!arg.equals("start") && !arg.equals("on")) {
                logDirect("Unknown argument. Usage: hunt [start|stop|scan]");
                return;
            }
        }
        baritone.getHuntProcess().hunt();
        Settings s = BaritoneAPI.getSettings();
        logDirect(String.format(
                "Hunting ball-finder heads. Auto-attack: %s, reach: %.1f.",
                s.huntAutoAttack.value ? "on" : "off",
                s.huntAttackReach.value
        ));
    }

    @Override
    public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
        if (args.hasExactlyOne()) {
            String prefix = args.getString().toLowerCase();
            return Stream.of("start", "stop", "scan").filter(s -> s.startsWith(prefix));
        }
        return Stream.empty();
    }

    @Override
    public String getShortDesc() {
        return "Walk to (and hit) the Hunt player-heads";
    }

    @Override
    public List<String> getLongDesc() {
        return Arrays.asList(
                "The hunt command reads the head coordinates that the ball-finder LabyMod addon saves",
                "to its waypoints file, paths to each head and (if huntAutoAttack is on) hits it +",
                "right-clicks it once when in reach, then ignores it and moves to the next.",
                "",
                "Coordinates come from huntWaypointsFile (auto-detected by default); only waypoints",
                "whose id starts with huntWaypointPrefix are used. Tune behaviour with the hunt* settings.",
                "",
                "Usage:",
                "> hunt       - start hunting",
                "> hunt start - start hunting",
                "> hunt stop  - stop hunting",
                "> hunt scan  - show the waypoints file path + loaded head coordinates"
        );
    }
}
